package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.adapter.knowledge.KnowledgeFilter;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeIndexPort;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeChunker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Q09 恢复演练的共享装配与工具（{@link Q09RestoreDrillIT} 拆分的支持类，不含任何用例）。
 *
 * <p>包含三部分：
 * <ol>
 *   <li><b>容器与动态属性</b>：MySQL 8.4.11 / Redis 7.4.11 / Qdrant v1.19.1（镜像按 digest 固定），
 *       以及 Testcontainers 到 Spring 的动态连接属性；</li>
 *   <li><b>演练常量与跨阶段状态</b>：备份/破坏/恢复各阶段的时间戳与度量，跨 {@code @DirtiesContext}
 *       的上下文重启保留（静态字段）；</li>
 *   <li><b>与 Spring 无关的工具</b>：全库表行数指纹、Qdrant 快照 HTTP 调用、SHA-256、指标输出。</li>
 * </ol>
 *
 * <p>口径与偏差说明见 {@link Q09RestoreDrillIT} 类注释与 {@code docs/operations/q09-restore-drill.md}。
 */
final class Q09RestoreDrillSupport {

    private Q09RestoreDrillSupport() {}

    // ===== 容器（镜像按 F02 台账/K01 目录固定 digest） =====

    /** 与 F02 台账/K01 目录一致的镜像（按 digest 固定）。 */
    static final DockerImageName MYSQL_IMAGE = DockerImageName.parse(
                    "mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
            .asCompatibleSubstituteFor("mysql");

    static final DockerImageName REDIS_IMAGE = DockerImageName.parse(
                    "redis:7.4.11@sha256:71da9275c5f3fcb97d0fa0c8c5b36cc995327265420f17a04bfd544f458059f7")
            .asCompatibleSubstituteFor("redis");

    static final DockerImageName QDRANT_IMAGE = DockerImageName.parse(
                    "qdrant/qdrant:v1.19.1@sha256:12364fe851b9f17356fc88189fc06d1b521262e04659ec7345975b00c9246a10")
            .asCompatibleSubstituteFor("qdrant/qdrant");

    static final String REDIS_PASSWORD = "q09-drill-only";

    static final String QDRANT_API_KEY = "q09-drill-key";

    /** 演练用 MySQL：独立容器，恢复过程不影响同 JVM 其它 IT 的共享库。 */
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("basic_framework")
            .withUsername("root")
            .withPassword("integration-only");

    static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
            .withExposedPorts(6379);

    static final GenericContainer<?> QDRANT = new GenericContainer<>(QDRANT_IMAGE)
            .withExposedPorts(6333)
            .withEnv("QDRANT__SERVICE__API_KEY", QDRANT_API_KEY);

    static {
        MYSQL.start();
        REDIS.start();
        QDRANT.start();
    }

    /** 供演练用例的 {@code @DynamicPropertySource} 转发（容器地址由 Testcontainers 随机映射）。 */
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.dynamic.datasource.master.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.dynamic.datasource.master.username", MYSQL::getUsername);
        registry.add("spring.datasource.dynamic.datasource.master.password", MYSQL::getPassword);
        registry.add("spring.datasource.dynamic.datasource.master.name", MYSQL::getDatabaseName);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    /** 演练用的可信范围解析器：与 A08 授权矩阵同口径（组织 10 + 对象白名单）。 */
    @TestConfiguration
    static class DrillScopeResolver {

        @Bean
        SubjectScopeResolver drillScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L), Set.of("report-1", "kb-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    // ===== 演练常量 =====

    static final String APP_CODE = "it-q09-drill-app";

    static final String ALICE = "alice";

    static final String BOB = "bob";

    static final String REPORT_KEY = "report-1";

    static final String KNOWLEDGE_BASE_CODE = "it-q09-kb";

    static final String REPORT_CODE = "it_q09_report";

    static final String RUN_KEY = "run_q09_drill";

    static final int EMBEDDING_DIMENSION = 8;

    static final List<String> CHUNK_TEXTS =
            List.of("华东区域 8 月净额 740.00 元，其中 C001 客户 290.00 元。", "C002 客户 450.00 元，口径为含退款净额。");

    static final String ORIGINAL_REPORT_DATA = "{\"blocks\":[{\"blockId\":\"b1\",\"value\":740.00}]}";

    /** 模型端点凭据明文：加密后落库，恢复后必须能用**同一密钥版本**解开。 */
    static final String ENDPOINT_CREDENTIAL = "q09-endpoint-secret";

    /** 知识文档绑定的真实文件（DB 存储形态）：恢复后必须能按 A07 语义读回原文。 */
    static final byte[] FILE_CONTENT = "Q09 文件内容：华东 8 月净额 740.00 元。".getBytes(StandardCharsets.UTF_8);

    /** 另一个 32 字节 Base64 主密钥：用于证明"换了密钥版本就解不开"（fail-closed）。 */
    static final String WRONG_KEY_BASE64 = "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=";

    // ===== 行数指纹口径：业务真相表 vs 运行期/易变表 =====

    /**
     * 不进入"业务真相行数指纹"的运行期/易变表（排除清单，逐表理由见下）。
     *
     * <p>排除的原因只有一条：这些表的行数由运行期设施在后台持续写入而变化，不代表"备份-恢复是否保真"。
     * 特别是整套件下同一 forked JVM 中其它测试类遗留的 Spring 上下文（test profile 的
     * {@code spring.quartz.auto-startup=true}）仍可能把调度器状态写进本演练库：2026-09-27 的整套件失败中，
     * 本库 dump 里的 {@code QRTZ_SCHEDULER_STATE}/{@code QRTZ_FIRED_TRIGGERS} 行来自两个创建时刻早于
     * 本用例窗口、且不属于本用例上下文（本用例自己的两个上下文设 {@code auto-startup=false}，从未注册）的
     * 调度器实例，其中一次 {@code aiKnowledgeIngestionJob} 触发正好落在"读取指纹 → mysqldump"之间。
     * 逐表口径与证据见 {@code docs/operations/q09-restore-drill.md} §1.1。
     */
    static final Set<String> RUNTIME_VOLATILE_TABLES = Set.of(
            // ---- Quartz 调度器运行期存储：触发、检查点（每 15s）、锁与集群恢复都会增删行 ----
            "QRTZ_BLOB_TRIGGERS",
            "QRTZ_CALENDARS",
            "QRTZ_CRON_TRIGGERS",
            "QRTZ_FIRED_TRIGGERS",
            "QRTZ_JOB_DETAILS",
            "QRTZ_LOCKS",
            "QRTZ_PAUSED_TRIGGER_GRPS",
            "QRTZ_SCHEDULER_STATE",
            "QRTZ_SIMPLE_TRIGGERS",
            "QRTZ_SIMPROP_TRIGGERS",
            "QRTZ_TRIGGERS",
            // ---- 任务执行日志：JobHandlerInvoker 每次任务执行都会插入一行（不是业务真相） ----
            "infra_job_log",
            // ---- 会话/票据类：取票即写、按 TTL 由 AiTicketCleanupJob 清理 ----
            "ai_access_ticket",
            "system_user_session",
            // ---- 审计/访问日志类：运行期追加、按保留期清理 ----
            "system_operate_log",
            "system_login_log",
            "infra_api_access_log",
            "infra_api_error_log",
            // ---- 运行事件流：运行生命周期写入、按保留期清理 ----
            "ai_run_event");

    /**
     * 演练真正破坏/恢复、必须留在指纹里逐表严格比对的业务真相表。
     *
     * <p>该清单是"不得削弱断言"的运行期护栏：排除清单与它不允许有任何交集，且它必须全部出现在指纹键集合里。
     */
    static final Set<String> DRILLED_BUSINESS_TABLES = Set.of(
            "ai_resource_grant",
            "ai_knowledge_base",
            "ai_knowledge_document",
            "ai_knowledge_document_version",
            "ai_knowledge_chunk",
            "ai_knowledge_index_generation",
            "ai_file_binding",
            "infra_file",
            "infra_file_content",
            "ai_model_endpoint",
            "ai_model_endpoint_revision",
            "ai_report_version");

    // ===== 跨上下文重启保留的演练状态 =====

    static Long applicationId;

    static String applicationSecret;

    static Long aliceGrantId;

    static Long aliceKnowledgeGrantId;

    static Long knowledgeBaseId;

    static Long documentId;

    static Long documentVersionId;

    static Integer generationNo;

    static String collectionName;

    static Long reportId;

    static Long modelEndpointId;

    static Long fileId;

    static Long fileConfigId;

    static KnowledgeIndexPort indexPort;

    static Map<String, Long> preBackupTableCounts;

    static long backupBytes;

    static String backupSha256;

    static Path backupFile;

    static String indexSnapshotName;

    static long indexSnapshotBytes;

    static long tBackupStartMillis;

    static long tBackupEndMillis;

    static long tPostBackupWriteMillis;

    static long tDestroyStartMillis;

    static long tDestroyEndMillis;

    static long tRestoreStartMillis;

    static long tRestoreEndMillis;

    static long tIndexRecoverEndMillis;

    static int destroyedRows;

    static long tVerifiedEndMillis;

    static final AtomicReference<String> METRICS = new AtomicReference<>("");

    // ===== 与 Spring 无关的工具 =====

    static List<String> expectedVectorIds() {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < CHUNK_TEXTS.size(); index++) {
            ids.add(AiKnowledgeChunker.deterministicVectorId(documentVersionId, index));
        }
        return ids;
    }

    static float[] chunkVector(int index) {
        float[] vector = new float[EMBEDDING_DIMENSION];
        vector[index] = 1f;
        return vector;
    }

    static KnowledgeFilter knowledgeBaseFilter() {
        return KnowledgeFilter.of("knowledge_base_id", List.of(String.valueOf(knowledgeBaseId)));
    }

    static String createIndexSnapshot() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(qdrantBaseUrl() + "/collections/" + collectionName + "/snapshots"))
                                .header("api-key", QDRANT_API_KEY)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("创建索引快照必须成功：%s", response.body()).isEqualTo(200);
        JsonNode body = new ObjectMapper().readTree(response.body());
        return body.path("result").path("name").asText();
    }

    /** 快照文件在 Qdrant 容器内的体积（宿主机拿不到，直接在容器里量）。 */
    static long snapshotBytes() throws Exception {
        ExecResult size = QDRANT.execInContainer(
                "sh",
                "-c",
                "wc -c < /qdrant/snapshots/" + collectionName + "/" + indexSnapshotName + " 2>/dev/null || echo 0");
        return Long.parseLong(size.getStdout().replaceAll("[^0-9]", ""));
    }

    static void recoverIndexSnapshot() throws Exception {
        String location = "file:///qdrant/snapshots/" + collectionName + "/" + indexSnapshotName;
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(
                                        qdrantBaseUrl() + "/collections/" + collectionName + "/snapshots/recover"))
                                .header("api-key", QDRANT_API_KEY)
                                .header("Content-Type", "application/json")
                                .PUT(HttpRequest.BodyPublishers.ofString(
                                        "{\"location\":\"" + location + "\",\"priority\":\"snapshot\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("索引快照恢复必须成功：%s", response.body()).isEqualTo(200);
    }

    static String qdrantBaseUrl() {
        return "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333);
    }

    /**
     * 全库表行数（含运行期表）：仅用于口径度量/诊断，不作为恢复保真断言（运行期表会抖动）。
     *
     * <p>恢复保真断言请用 {@link #businessTruthFingerprint(Map)}。
     */
    static Map<String, Long> tableRowCounts(JdbcTemplate jdbcTemplate) {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'"
                        + " ORDER BY table_name",
                String.class,
                MYSQL.getDatabaseName());
        Map<String, Long> counts = new TreeMap<>();
        for (String table : tables) {
            counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Long.class));
        }
        return counts;
    }

    /**
     * 业务真相行数指纹：从全库表行数中剔除 {@link #RUNTIME_VOLATILE_TABLES}，其余表逐表严格比对。
     *
     * <p>运行期护栏（防止口径被悄悄放宽）：
     * <ol>
     *   <li>排除清单必须命中真实存在的表——表改名/下线后必须同步维护，不能"排了个不存在的表"而静默失效；</li>
     *   <li>被演练的业务真相表（{@link #DRILLED_BUSINESS_TABLES}）必须全部留在指纹里——排除它们就是削弱演练证据；</li>
     *   <li>排除清单与业务真相表不允许交集。</li>
     * </ol>
     */
    static Map<String, Long> businessTruthFingerprint(Map<String, Long> allTableCounts) {
        assertThat(RUNTIME_VOLATILE_TABLES)
                .as("排除清单与业务真相表不得有交集（排除被演练表即削弱演练证据）")
                .doesNotContainAnyElementsOf(DRILLED_BUSINESS_TABLES);
        assertThat(allTableCounts.keySet())
                .as("排除清单必须全部命中真实表（表改名/删除后必须同步维护，禁止静默失效）")
                .containsAll(RUNTIME_VOLATILE_TABLES);
        Map<String, Long> fingerprint = new TreeMap<>(allTableCounts);
        RUNTIME_VOLATILE_TABLES.forEach(fingerprint::remove);
        assertThat(fingerprint.keySet())
                .as("被演练的业务真相表必须留在指纹内逐表严格比对")
                .containsAll(DRILLED_BUSINESS_TABLES)
                .doesNotContainAnyElementsOf(RUNTIME_VOLATILE_TABLES);
        return fingerprint;
    }

    static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    static String seconds(long fromMillis, long toMillis) {
        return String.format("%.3f", (toMillis - fromMillis) / 1000.0);
    }

    static void metric(String key, Object value) {
        METRICS.set(METRICS.get() + "Q09-DRILL-METRIC " + key + "=" + value + "\n");
    }

    static String metricsSoFar() {
        return METRICS.get();
    }

    static void recordEnvironment() {
        metric("java", System.getProperty("java.version"));
        metric(
                "os",
                System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                        + System.getProperty("os.arch"));
        metric("cpus", Runtime.getRuntime().availableProcessors());
        metric("mysql_container", MYSQL.getDockerImageName());
        metric("redis_container", REDIS.getDockerImageName());
        metric("qdrant_container", QDRANT.getDockerImageName());
        metric("host_memory_bytes", hostMemoryBytes());
    }

    static String hostMemoryBytes() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemTotal:")) {
                    return line.replaceAll("[^0-9]", "");
                }
            }
        } catch (IOException ignored) {
            // 非 Linux 环境不采集
        }
        return "unknown";
    }
}
