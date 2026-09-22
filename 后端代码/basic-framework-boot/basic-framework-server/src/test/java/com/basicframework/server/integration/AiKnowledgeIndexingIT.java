package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.adapter.knowledge.KnowledgeIndexPort;
import com.basicframework.module.ai.adapter.knowledge.QdrantRestKnowledgeIndexAdapter;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import com.basicframework.module.ai.job.AiKnowledgeIngestionJob;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeChunkService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeIndexGenerationService;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeBaseSaveDTO;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeEmbeddingClient;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeEmbeddingException;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeSourceReader;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionService;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionTaskDO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionRequestDTO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * K05 索引流水线端到端（真实 MySQL + 真实 Qdrant 容器）。
 *
 * <p>嵌入模型用**确定性替身**（同样的文本永远同样的向量，且可被检索命中）：
 * 真实模型效果由 Q04/Q10 评测负责，本用例验证的是流水线语义——
 * 全部切片成功才切 active、失败保留旧版本、维度不一致拒绝混用（AT-024/AT-029）。
 *
 * <p>原文读取与嵌入客户端在测试里替换为替身（`@Primary`）：前者避免依赖后台线程的主体上下文
 * （见 K05 证据的未验证项），后者避免依赖外部模型端点。
 */
@Import(AiKnowledgeIndexingIT.IndexingTestConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiKnowledgeIndexingIT extends AbstractPersistenceIntegrationTest {

    /** Qdrant 容器（镜像按 digest 固定，与 K01 的目录一致）。 */
    private static final DockerImageName QDRANT_IMAGE = DockerImageName.parse(
                    "qdrant/qdrant:v1.19.1@sha256:12364fe851b9f17356fc88189fc06d1b521262e04659ec7345975b00c9246a10")
            .asCompatibleSubstituteFor("qdrant/qdrant");

    private static final GenericContainer<?> QDRANT =
            new GenericContainer<>(QDRANT_IMAGE).withExposedPorts(6333).withEnv("QDRANT__SERVICE__API_KEY", "it-key");

    static {
        QDRANT.start();
    }

    /** 是否让替身在本次嵌入时失败（由用例显式打开，避免跨用例串扰）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean FAIL_EMBEDDING =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 测试替身：确定性向量 + 可注入失败/维度。 */
    @TestConfiguration
    static class IndexingTestConfiguration {

        /** 替换生产嵌入客户端：同样的文本永远同样的向量（可被检索命中）。 */
        @Bean
        @Primary
        AiKnowledgeEmbeddingClient fixtureEmbeddingClient() {
            return (embeddingModel, texts) -> {
                for (String text : texts) {
                    if (text.contains("EMBEDDING-FAIL")) {
                        throw new AiKnowledgeEmbeddingException(
                                AiKnowledgeEmbeddingException.Reason.UPSTREAM_FAILED, "fixture");
                    }
                }
                List<float[]> vectors = texts.stream()
                        .map(AiKnowledgeIndexingIT::deterministicVector)
                        .toList();
                return new AiKnowledgeEmbeddingClient.EmbeddingBatch(
                        vectors, embeddingModel, deterministicVector("x").length);
            };
        }

        /**
         * /**
         * 替换生产原文读取器：直接给出测试内容（后台线程没有主体上下文）。
         * 只有用例显式打开 {@link #FAIL_EMBEDDING} 时才返回带失败标记的正文，
         * 避免替身状态跨用例串扰。
         */
        @Bean
        @Primary
        AiKnowledgeSourceReader fixtureSourceReader() {
            return fileId -> {
                String body = FAIL_EMBEDDING.get()
                        ? "EMBEDDING-FAIL 之后的正文不应被索引。"
                        : "华东区域 8 月净额 740.00 元。\n\nC001 客户 290.00 元。\n\nC002 客户 450.00 元。";
                return new AiKnowledgeSourceReader.KnowledgeSource(
                        body.getBytes(StandardCharsets.UTF_8), "handbook.txt");
            };
        }

        /** 真实向量服务：按容器地址构造适配器（K01 的 REST 适配器）。 */
        @Bean
        @Primary
        KnowledgeIndexPort knowledgeIndexPort() {
            return new QdrantRestKnowledgeIndexAdapter(
                    "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333),
                    "it-key",
                    Duration.ofSeconds(20),
                    true);
        }
    }

    /** 确定性向量：sha256 前 8 字节归一化（同文本同向量，可被检索命中）。 */
    private static float[] deterministicVector(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            float[] vector = new float[8];
            for (int index = 0; index < vector.length; index++) {
                vector[index] = ((digest[index] & 0xFF) / 255.0f) - 0.5f;
            }
            return vector;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 每个用例独立知识库标识：向量集合的维度创建后不可变，跨用例必须用不同集合。 */
    private static final java.util.concurrent.atomic.AtomicInteger SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger();

    private String code = "it-indexing";

    @Autowired
    private AiKnowledgeBaseService baseService;

    @Autowired
    private AiKnowledgeDocumentService documentService;

    @Autowired
    private AiKnowledgeChunkService chunkService;

    @Autowired
    private AiKnowledgeIndexGenerationService generationService;

    @Autowired
    private AiKnowledgeIngestionService ingestionService;

    @Autowired
    private AiKnowledgeIngestionJob ingestionJob;

    @Autowired
    private KnowledgeIndexPort indexPort;

    private Long baseId;

    @BeforeEach
    void prepare() {
        code = "it-indexing-" + SEQUENCE.incrementAndGet();
        FAIL_EMBEDDING.set(false);
        cleanUp();
        baseId = baseService.create(new AiKnowledgeBaseSaveDTO()
                .setCode(code)
                .setName("IT 索引知识库")
                .setVisibility(AiKnowledgeBaseDO.VISIBILITY_SHARED)
                .setEmbeddingModel("text-embedding-3-small")
                .setEmbeddingDimension(8));
        jdbcTemplate.update(
                "INSERT INTO ai_file_binding (file_id, business_type, business_key, application_id, subject_type,"
                        + " external_user_id, status, version, deleted) VALUES (9101, 'ai_knowledge_document', ?,"
                        + " 1, 'USER', 'alice', 'ACTIVE', 0, b'0')",
                code);
    }

    @AfterEach
    void cleanUp() {
        deleteCollectionIfPresent("kb_" + code + "_g1");
        deleteCollectionIfPresent("kb_" + code + "_g2");
        jdbcTemplate.update("DELETE FROM ai_knowledge_ingestion_task");
        jdbcTemplate.update("DELETE FROM ai_knowledge_chunk");
        jdbcTemplate.update("DELETE FROM ai_knowledge_document_version");
        jdbcTemplate.update("DELETE FROM ai_knowledge_document");
        jdbcTemplate.update("DELETE FROM ai_knowledge_index_generation");
        jdbcTemplate.update("DELETE FROM ai_knowledge_base");
        jdbcTemplate.update("DELETE FROM ai_file_binding WHERE file_id = 9101");
        baseId = null;
    }

    /** 集合不存在时清理会失败（K01 适配器的语义）：测试清理忽略这种失败。 */
    private void deleteCollectionIfPresent(String collection) {
        try {
            indexPort.deleteAll(collection);
        } catch (RuntimeException missing) {
            // 集合本来就不存在：无需清理
        }
    }

    private Long ingest(String sourceKey) {
        return ingestionService
                .ingest(new AiKnowledgeIngestionRequestDTO()
                        .setKnowledgeBaseId(baseId)
                        .setSourceKey(sourceKey)
                        .setTitle("员工手册")
                        .setSourceType(AiKnowledgeDocumentDO.SOURCE_UPLOAD)
                        .setFileId(9101L)
                        .setContentHash("a".repeat(64)))
                .getDocumentId();
    }

    @Test
    void indexesDocumentIntoRealVectorServiceAndSwitchesActiveVersion() {
        Long documentId = ingest("handbook/v1.txt");

        String summary = ingestionJob.execute(null);

        assertThat(summary).contains("成功 1");
        AiKnowledgeDocumentDO document = documentService.getDocument(documentId);
        assertThat(document.getStatus()).isEqualTo(AiKnowledgeDocumentDO.STATUS_READY);
        assertThat(document.getActiveVersionNo()).isEqualTo(1);
        AiKnowledgeDocumentVersionDO version = documentService.getActiveVersion(documentId);
        assertThat(version.getStatus()).isEqualTo(AiKnowledgeDocumentVersionDO.STATUS_READY);
        assertThat(version.getChunkCount()).isPositive();
        assertThat(chunkService.listVersionChunks(version.getId())).hasSize(version.getChunkCount());
        AiKnowledgeIndexGenerationDO generation = generationService.getActiveGeneration(baseId);
        assertThat(generation.getStatus()).isEqualTo(AiKnowledgeIndexGenerationDO.STATUS_ACTIVE);
        assertThat(generation.getCollectionName()).isEqualTo("kb_" + code + "_g1");
        assertThat(generation.getChunkCount()).isEqualTo(version.getChunkCount());
        // 真实向量服务里能按同一文本的向量检索到该切片（检索与引用可核验）
        List<KnowledgeIndexPort.SearchHit> hits =
                indexPort.search(generation.getCollectionName(), deterministicVector("C001 客户 290.00 元。"), 3, null);
        assertThat(hits).isNotEmpty();
        assertThat(String.valueOf(hits.get(0).payload().get("text"))).contains("C001");
    }

    @Test
    void failedIndexingKeepsTheOldActiveVersion() {
        Long documentId = ingest("handbook/v1.txt");
        assertThat(ingestionJob.execute(null)).contains("成功 1");
        assertThat(documentService.getDocument(documentId).getActiveVersionNo()).isEqualTo(1);

        // 第二版：替身在第二次读取时给出带失败标记的正文 → 嵌入失败，旧版本必须继续可用（AT-024）
        AiKnowledgeDocumentDO firstDocument = documentService.getDocument(documentId);
        assertThat(firstDocument.getStatus()).isEqualTo(AiKnowledgeDocumentDO.STATUS_READY);
        assertThat(documentService.getActiveVersion(documentId).getStatus())
                .isEqualTo(AiKnowledgeDocumentVersionDO.STATUS_READY);

        // 同一文档入库新指纹 → 新版本；替身从下一次读取开始返回失败标记 → 嵌入失败
        FAIL_EMBEDDING.set(true);
        ingestionService.ingest(new AiKnowledgeIngestionRequestDTO()
                .setKnowledgeBaseId(baseId)
                .setSourceKey("handbook/v1.txt")
                .setTitle("员工手册")
                .setSourceType(AiKnowledgeDocumentDO.SOURCE_UPLOAD)
                .setFileId(9101L)
                .setContentHash("b".repeat(64)));
        assertThat(ingestionJob.execute(null)).contains("失败 1");

        assertThat(documentService.getDocument(documentId).getActiveVersionNo())
                .as("失败不改 active 指针")
                .isEqualTo(1);
        assertThat(documentService.getActiveVersion(documentId).getStatus())
                .isEqualTo(AiKnowledgeDocumentVersionDO.STATUS_READY);
        assertThat(documentService
                        .getVersionPage(new com.basicframework.framework.common.pojo.PageParam(), documentId)
                        .getList())
                .anySatisfy(version ->
                        assertThat(version.getStatus()).isEqualTo(AiKnowledgeDocumentVersionDO.STATUS_FAILED));
    }

    @Test
    void dimensionMismatchBetweenModelAndKnowledgeBaseIsRejected() {
        // 知识库声明 8 维，替身也返回 8 维；这里把知识库改成 16 维模拟"换模型未换代"
        jdbcTemplate.update("UPDATE ai_knowledge_base SET embedding_dimension = 16 WHERE id = ?", baseId);
        Long documentId = ingest("handbook/v1.txt");

        ingestionJob.execute(null);

        AiKnowledgeDocumentDO document = documentService.getDocument(documentId);
        assertThat(document.getStatus()).isEqualTo(AiKnowledgeDocumentDO.STATUS_FAILED);
        assertThat(document.getActiveVersionNo()).isZero();
        assertThat(generationService.getActiveGeneration(baseId))
                .as("维度不一致时不允许激活索引代")
                .isNull();
        assertThat(ingestionService
                        .getTaskPage(new com.basicframework.framework.common.pojo.PageParam(), baseId, null)
                        .getList())
                .allSatisfy(task -> {
                    assertThat(task.getStatus()).isEqualTo(AiKnowledgeIngestionTaskDO.STATUS_FAILED);
                    assertThat(task.getLastErrorCode()).isEqualTo("embed-dimension_mismatch");
                });
    }
}
