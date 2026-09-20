package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeFilter;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeIndexException;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeIndexPort;
import com.basicframework.module.ai.adapter.knowledge.QdrantRestKnowledgeIndexAdapter;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * K01 向量索引候选验证（真实 Qdrant 容器，镜像 digest 与 F02 台账一致）。
 *
 * <p>验证最小能力集合：集合与维度、upsert、服务端过滤（ACL）、删除可验证、API Key 认证、快照恢复。
 * 容器以 API Key 启动，适配器以 http 基址连接（本地验证放开 https 强制；生产基址必须 https）。
 */
class AiKnowledgeIndexQdrantIT {

    /** 与 F02 台账锁定的候选组合一致：Qdrant v1.19.1（按 digest 固定）。 */
    private static final DockerImageName QDRANT_IMAGE = DockerImageName.parse(
                    "qdrant/qdrant:v1.19.1@sha256:12364fe851b9f17356fc88189fc06d1b521262e04659ec7345975b00c9246a10")
            .asCompatibleSubstituteFor("qdrant/qdrant");

    private static final String API_KEY = "k01-it-api-key";

    private static final String COLLECTION = "k01_it_knowledge";

    private static final GenericContainer<?> QDRANT =
            new GenericContainer<>(QDRANT_IMAGE).withExposedPorts(6333).withEnv("QDRANT__SERVICE__API_KEY", API_KEY);

    static {
        QDRANT.start();
    }

    private KnowledgeIndexPort index;

    private String baseUrl() {
        return "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333);
    }

    @BeforeEach
    void setUp() {
        index = new QdrantRestKnowledgeIndexAdapter(baseUrl(), API_KEY, Duration.ofSeconds(20), true);
        index.ensureCollection(COLLECTION, 4);
        index.deleteAll(COLLECTION);
    }

    @AfterEach
    void tearDown() throws Exception {
        // 集合按测试隔离：删除集合避免跨用例互相影响
        HttpClient client = HttpClient.newHttpClient();
        client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + "/collections/" + COLLECTION))
                        .header("api-key", API_KEY)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private static float[] vector(float a, float b, float c, float d) {
        return new float[] {a, b, c, d};
    }

    @Test
    void upsertSearchAndServerSideFilterRespectAcl() {
        KnowledgeIndexPort.CollectionInfo info = index.ensureCollection(COLLECTION, 4);
        assertThat(info.dimension()).isEqualTo(4);

        index.upsert(
                COLLECTION,
                List.of(
                        new KnowledgeIndexPort.IndexDocument(
                                "doc-a-1", vector(1f, 0f, 0f, 0f), Map.of("tenant", "tenant-a", "title", "订单退款规则")),
                        new KnowledgeIndexPort.IndexDocument(
                                "doc-a-2", vector(0.9f, 0.1f, 0f, 0f), Map.of("tenant", "tenant-a", "title", "发货时效说明")),
                        new KnowledgeIndexPort.IndexDocument(
                                "doc-b-1", vector(1f, 0f, 0f, 0f), Map.of("tenant", "tenant-b", "title", "另一个租户的文档"))));

        // 服务端过滤：tenant-a 只看到自己的两条（即使向量完全相同也不会跨租户命中）
        List<KnowledgeIndexPort.SearchHit> hits =
                index.search(COLLECTION, vector(1f, 0f, 0f, 0f), 10, KnowledgeFilter.of("tenant", List.of("tenant-a")));
        assertThat(hits).extracting(KnowledgeIndexPort.SearchHit::id).containsExactlyInAnyOrder("doc-a-1", "doc-a-2");
        assertThat(hits).allSatisfy(hit -> assertThat(hit.payload()).containsEntry("tenant", "tenant-a"));

        // 中文载荷可检索（中文 fixture 写入与读取一致）
        assertThat(hits.stream()
                        .map(hit -> String.valueOf(hit.payload().get("title")))
                        .toList())
                .contains("订单退款规则", "发货时效说明");

        // 删除按同一条件可验证：删掉 tenant-a 后该过滤条件检索为空，tenant-b 不受影响
        long deleted = index.delete(COLLECTION, KnowledgeFilter.of("tenant", List.of("tenant-a")));
        assertThat(deleted).isPositive();
        assertThat(index.search(
                        COLLECTION, vector(1f, 0f, 0f, 0f), 10, KnowledgeFilter.of("tenant", List.of("tenant-a"))))
                .as("删除后按同一条件检索必须为空")
                .isEmpty();
        assertThat(index.search(
                        COLLECTION, vector(1f, 0f, 0f, 0f), 10, KnowledgeFilter.of("tenant", List.of("tenant-b"))))
                .extracting(KnowledgeIndexPort.SearchHit::id)
                .containsExactly("doc-b-1");
    }

    @Test
    void dimensionMismatchIsRejectedOnWriteQueryAndCollection() {
        index.ensureCollection(COLLECTION, 4);

        // 写入维度不一致：拒绝（不依赖服务端行为）
        assertThatThrownBy(() -> index.upsert(
                        COLLECTION,
                        List.of(new KnowledgeIndexPort.IndexDocument(
                                "bad", new float[] {1f, 0f, 0f, 0f, 0f}, Map.of()))))
                .isInstanceOf(KnowledgeIndexException.class)
                .satisfies(failure -> assertThat(((KnowledgeIndexException) failure).getReason())
                        .isEqualTo(KnowledgeIndexException.Reason.DIMENSION_MISMATCH));

        // 查询维度不一致：拒绝
        assertThatThrownBy(() -> index.search(COLLECTION, new float[] {1f, 0f}, 5, null))
                .isInstanceOf(KnowledgeIndexException.class)
                .satisfies(failure -> assertThat(((KnowledgeIndexException) failure).getReason())
                        .isEqualTo(KnowledgeIndexException.Reason.DIMENSION_MISMATCH));

        // 集合维度不一致：拒绝（已有集合维度不可变）
        assertThatThrownBy(() -> index.ensureCollection(COLLECTION, 8))
                .isInstanceOf(KnowledgeIndexException.class)
                .satisfies(failure -> assertThat(((KnowledgeIndexException) failure).getReason())
                        .isEqualTo(KnowledgeIndexException.Reason.DIMENSION_MISMATCH));
    }

    @Test
    void apiKeyIsRequiredAndProductionBaseUrlMustBeHttps() {
        KnowledgeIndexPort wrongKey =
                new QdrantRestKnowledgeIndexAdapter(baseUrl(), "wrong-key", Duration.ofSeconds(10), true);
        assertThatThrownBy(() -> wrongKey.describe(COLLECTION))
                .isInstanceOf(KnowledgeIndexException.class)
                .satisfies(failure -> assertThat(((KnowledgeIndexException) failure).getReason())
                        .isEqualTo(KnowledgeIndexException.Reason.UNAUTHORIZED));

        // 生产构造只接受 https：明文基址直接拒绝
        assertThatThrownBy(() -> new QdrantRestKnowledgeIndexAdapter(baseUrl(), API_KEY, Duration.ofSeconds(10)))
                .isInstanceOf(ServiceException.class)
                .satisfies(failure -> assertThat(((ServiceException) failure).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode()));

        // 不存在的集合：稳定原因码（不泄露上游报文）
        assertThatThrownBy(() -> index.describe("k01_missing_collection"))
                .isInstanceOf(KnowledgeIndexException.class)
                .satisfies(failure -> assertThat(((KnowledgeIndexException) failure).getReason())
                        .isEqualTo(KnowledgeIndexException.Reason.COLLECTION_NOT_FOUND));
    }

    @Test
    void snapshotRecoveryRestoresDeletedPoints() throws Exception {
        index.ensureCollection(COLLECTION, 4);
        index.upsert(
                COLLECTION,
                List.of(
                        new KnowledgeIndexPort.IndexDocument(
                                "doc-1", vector(1f, 0f, 0f, 0f), Map.of("tenant", "tenant-a")),
                        new KnowledgeIndexPort.IndexDocument(
                                "doc-2", vector(0f, 1f, 0f, 0f), Map.of("tenant", "tenant-a"))));

        String snapshot = createSnapshot();
        index.deleteAll(COLLECTION);
        assertThat(index.search(COLLECTION, vector(1f, 0f, 0f, 0f), 10, null)).isEmpty();

        // 快照恢复：删除的点在恢复后重新可检索（快照可验证）
        recoverSnapshot(snapshot);
        assertThat(index.search(COLLECTION, vector(1f, 0f, 0f, 0f), 10, null))
                .extracting(KnowledgeIndexPort.SearchHit::id)
                .containsExactlyInAnyOrder("doc-1", "doc-2");
    }

    private String createSnapshot() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl() + "/collections/" + COLLECTION + "/snapshots"))
                                .header("api-key", API_KEY)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("创建快照必须成功").isEqualTo(200);
        com.fasterxml.jackson.databind.JsonNode body =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
        return body.path("result").path("name").asText();
    }

    private void recoverSnapshot(String snapshotName) throws Exception {
        String location = "file:///qdrant/snapshots/" + COLLECTION + "/" + snapshotName;
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl() + "/collections/" + COLLECTION + "/snapshots/recover"))
                                .header("api-key", API_KEY)
                                .header("Content-Type", "application/json")
                                .PUT(HttpRequest.BodyPublishers.ofString(
                                        "{\"location\":\"" + location + "\",\"priority\":\"snapshot\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("快照恢复必须成功").isEqualTo(200);
    }
}
