package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetVersionDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.connector.AiConnectorService;
import com.basicframework.module.ai.service.connector.AiMysqlConnectorService;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetSaveDTO;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetVersionSaveDTO;
import com.basicframework.module.ai.service.query.planner.AiQueryPlanModel;
import com.basicframework.module.ai.service.query.planner.AiQueryPlanner;
import com.basicframework.module.ai.service.query.planner.dto.AiQueryPlanRequestDTO;
import com.basicframework.module.ai.service.query.planner.dto.AiQueryPlanResultDTO;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D05 查询规划端到端（真实 MySQL + 真实已发布数据集版本 + **固定模型评测夹具**）。
 *
 * <p>覆盖卡片验收：只给授权摘要、PLAN/CLARIFICATION 分流、SQL 片段拒绝、未知指标/歧义追问、
 * 超修复次数结束、时间基于固定时钟，以及"模型不能扩大数据集范围"。
 * 模型由夹具脚本驱动（生产模型不可复现，评测结论必须来自固定输入）。
 */
@Import(AiQueryPlanIT.ScriptedModelConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiQueryPlanIT extends AbstractPersistenceIntegrationTest {

    /** 固定模型评测夹具：按当前脚本返回夹具文件内容（占位符按真实数据集/时钟替换）。 */
    @TestConfiguration
    static class ScriptedModelConfiguration {

        static final AtomicReference<String> SCRIPT = new AtomicReference<>("plan.valid.json");

        static final AtomicReference<Integer> CALLS = new AtomicReference<>(0);

        @Bean
        @org.springframework.context.annotation.Primary
        AiQueryPlanModel scriptedQueryPlanModel(Clock aiQueryPlannerClock) {
            return (endpointId, prompt, jsonSchema) -> {
                CALLS.set(CALLS.get() + 1);
                String template = readFixture(SCRIPT.get());
                // 截断到天：同一测试内多次调用必须得到同一份计划（否则计划哈希会随毫秒变化）
                Instant now = aiQueryPlannerClock.instant().truncatedTo(ChronoUnit.DAYS);
                return template.replace("${datasetId}", "dset_" + AiQueryPlanIT.DATASET_CODE)
                        .replace("${datasetVersion}", String.valueOf(AiQueryPlanIT.DATASET_VERSION_NO))
                        .replace(
                                "${windowStart}",
                                DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                                        now.minus(30, ChronoUnit.DAYS).atOffset(java.time.ZoneOffset.ofHours(8))))
                        .replace(
                                "${windowEnd}",
                                DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                                        now.atOffset(java.time.ZoneOffset.ofHours(8))));
            };
        }

        private static String readFixture(String name) {
            try (InputStream stream = ScriptedModelConfiguration.class
                    .getClassLoader()
                    .getResourceAsStream("ai/query-plan-fixtures/" + name)) {
                if (stream == null) {
                    throw new IllegalStateException("夹具不存在：" + name);
                }
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new IllegalStateException("夹具读取失败：" + name, failure);
            }
        }
    }

    private static final String CONNECTOR_CODE = "it-query-connector";

    /** 数据集标识（计划里的 dset_ 前缀由它派生）。 */
    static final String DATASET_CODE = "it-query-orders";

    /** 语义版本号（夹具里引用）。 */
    static final Integer DATASET_VERSION_NO = 1;

    /** 只读账号口令（仅集成容器内的测试值）。 */
    private static final String READ_ONLY_PASSWORD = "d05-it-readonly-password";

    private static final String CATALOG = "d05_catalog";

    private static final String DEFINITION =
            """
            {"grain": "一行一单",
             "time": {"field": "created_at", "granularity": "DAY", "timezone": "Asia/Shanghai"},
             "fields": [
               {"name": "order_id", "sourceColumn": "id", "type": "NUMBER", "unit": "COUNT",
                "aliases": ["订单号"], "visibility": "PUBLIC"},
               {"name": "amount", "sourceColumn": "amount", "type": "DECIMAL", "unit": "CURRENCY",
                "aliases": ["销售额"], "visibility": "INTERNAL"},
               {"name": "created_at", "sourceColumn": "created_at", "type": "DATETIME",
                "visibility": "INTERNAL"},
               {"name": "region", "sourceColumn": "region", "type": "STRING", "aliases": ["区域"],
                "visibility": "INTERNAL"},
               {"name": "customer", "sourceColumn": "customer_id", "type": "STRING",
                "visibility": "INTERNAL"}],
             "metrics": [{"name": "net_amount", "field": "amount", "aggregation": "SUM",
                          "unit": "CURRENCY", "visibility": "INTERNAL"}],
             "dimensions": [{"name": "customer_name", "field": "customer"}]}
            """;

    @Autowired
    private AiConnectorService connectorService;

    @Autowired
    private AiMysqlConnectorService mysqlConnectorService;

    @Autowired
    private AiDatasetService datasetService;

    @Autowired
    private AiQueryPlanner queryPlanner;

    @Autowired
    private Clock aiQueryPlannerClock;

    private Long connectorId;

    private Long datasetId;

    private Long publishedVersionId;

    @BeforeEach
    void prepareDataset() {
        jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS " + CATALOG);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".orders");
        jdbcTemplate.execute("CREATE TABLE " + CATALOG + ".orders ("
                + "id BIGINT PRIMARY KEY, amount DECIMAL(12,2) NOT NULL,"
                + " created_at DATETIME NOT NULL, region VARCHAR(16) NOT NULL, customer_id VARCHAR(16) NOT NULL)");
        jdbcTemplate.update("INSERT INTO " + CATALOG + ".orders (id, amount, created_at, region, customer_id)"
                + " VALUES (1, 12.50, '2026-09-01 10:00:00', 'EAST', 'C001')");
        jdbcTemplate.execute("DROP USER IF EXISTS 'd05_ro'@'%'");
        jdbcTemplate.execute("CREATE USER 'd05_ro'@'%' IDENTIFIED BY '" + READ_ONLY_PASSWORD + "'");
        jdbcTemplate.execute("GRANT SELECT ON " + CATALOG + ".* TO 'd05_ro'@'%'");
        jdbcTemplate.execute("FLUSH PRIVILEGES");

        connectorId = connectorService.create(new AiConnectorSaveDTO()
                .setCode(CONNECTOR_CODE)
                .setName("IT 查询连接器")
                .setConnectorType(AiConnectorDO.TYPE_MYSQL)
                .setConfigJson("{\"host\":\"localhost\",\"port\":" + mysqlPort() + ",\"database\":\"" + CATALOG
                        + "\",\"username\":\"d05_ro\",\"sslMode\":\"REQUIRED\",\"allowedObjects\":[\"" + CATALOG
                        + ".orders\"]}")
                .setCredential(READ_ONLY_PASSWORD));
        datasetId = datasetService.create(new AiDatasetSaveDTO()
                .setCode(DATASET_CODE)
                .setName("IT 查询数据集")
                .setConnectorId(connectorId)
                .setSourceObject(CATALOG + ".orders"));

        publishedVersionId = datasetService.createVersion(
                new AiDatasetVersionSaveDTO().setDatasetId(datasetId).setDefinitionJson(DEFINITION));
        datasetService.verifyVersion(
                publishedVersionId,
                datasetService.getVersion(publishedVersionId).getVersion());
        datasetService.publishVersion(
                publishedVersionId,
                datasetService.getVersion(publishedVersionId).getVersion());

        ScriptedModelConfiguration.SCRIPT.set("plan.valid.json");
        ScriptedModelConfiguration.CALLS.set(0);
    }

    @AfterEach
    void cleanUp() {
        mysqlConnectorService.closePool(connectorId);
        jdbcTemplate.update(
                "DELETE FROM ai_dataset_version WHERE dataset_id IN" + " (SELECT id FROM ai_dataset WHERE code = ?)",
                DATASET_CODE);
        jdbcTemplate.update("DELETE FROM ai_dataset WHERE code = ?", DATASET_CODE);
        jdbcTemplate.update("DELETE FROM ai_connector WHERE code = ?", CONNECTOR_CODE);
        jdbcTemplate.execute("DROP USER IF EXISTS 'd05_ro'@'%'");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".orders");
        jdbcTemplate.execute("DROP DATABASE IF EXISTS " + CATALOG);
        connectorId = null;
        datasetId = null;
        publishedVersionId = null;
    }

    private static String mysqlPort() {
        return String.valueOf(AbstractPersistenceIntegrationTest.mysqlMappedPort());
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private AiQueryPlanRequestDTO request() {
        return new AiQueryPlanRequestDTO()
                .setDatasetId(datasetId)
                .setDatasetVersionId(publishedVersionId)
                .setEndpointId(1L)
                .setQuestion("上个月华东的销售额");
    }

    @Test
    void plansAgainstPublishedVersionWithStableHash() {
        AiQueryPlanResultDTO result = queryPlanner.plan(request());

        assertThat(result.getKind()).isEqualTo("PLAN");
        assertThat(result.getAttempts()).isEqualTo(1);
        assertThat(result.getPlanDatasetId()).isEqualTo("dset_" + DATASET_CODE);
        assertThat(result.getDatasetVersionNo()).isEqualTo(DATASET_VERSION_NO);
        assertThat(result.getSchemaHash())
                .isEqualTo(datasetService.getVersion(publishedVersionId).getSchemaHash());
        assertThat(result.getPlanHash()).hasSize(64);
        assertThat(result.getPlanJson())
                .contains("\"code\":\"net_amount\"")
                .contains("\"code\":\"customer_name\"")
                .contains("\"timezone\":\"Asia/Shanghai\"");

        // 同一问题、同一版本、同一夹具：计划哈希稳定（可用于回归比对与审计）
        AiQueryPlanResultDTO again = queryPlanner.plan(request());
        assertThat(again.getPlanHash()).isEqualTo(result.getPlanHash());
    }

    @Test
    void asksForClarificationInsteadOfGuessing() {
        ScriptedModelConfiguration.SCRIPT.set("clarification.ambiguous.json");

        AiQueryPlanResultDTO result = queryPlanner.plan(request());

        assertThat(result.getKind()).isEqualTo("CLARIFICATION");
        assertThat(result.getReason()).isEqualTo("AMBIGUOUS");
        assertThat(result.getQuestion()).contains("口径");
        assertThat(result.getCandidates())
                .as("候选只保留目录里真实存在的码")
                .extracting(AiQueryPlanResultDTO.Candidate::code)
                .containsExactly("net_amount");
        assertThat(result.getPlanHash()).isNull();
    }

    @Test
    void rejectsSqlFragmentFromModel() {
        ScriptedModelConfiguration.SCRIPT.set("plan.sql-fragment.json");

        assertThatThrownBy(() -> queryPlanner.plan(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_SQL_REJECTED));
        assertThat(ScriptedModelConfiguration.CALLS.get()).as("SQL 片段不给修复机会").isEqualTo(1);
    }

    @Test
    void rejectsUnknownMetricAndStopsAfterRepairLimit() {
        ScriptedModelConfiguration.SCRIPT.set("plan.unknown-field.json");

        assertThatThrownBy(() -> queryPlanner.plan(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_REPAIR_EXHAUSTED));
        assertThat(ScriptedModelConfiguration.CALLS.get())
                .as("默认修复上限 2 → 最多 3 次模型输出")
                .isEqualTo(3);
    }

    @Test
    void summaryExposesOnlyAuthorizedMetadata() {
        String summary = queryPlanner.datasetSummary(datasetId, publishedVersionId, null);

        assertThat(summary)
                .contains("\"datasetId\":\"dset_" + DATASET_CODE + "\"")
                .contains("\"code\":\"net_amount\"")
                .contains("\"granularity\":\"DAY\"")
                .contains("\"currentTime\":");
        assertThat(summary)
                .as("摘要不得暴露物理对象、库名与实现细节")
                .doesNotContain(CATALOG)
                .doesNotContain("sourceColumn")
                .doesNotContain("it_query")
                .doesNotContain("d05_ro");
        assertThat(summary)
                .as("当前时间来自注入时钟（固定时钟可用）")
                .contains(String.valueOf(aiQueryPlannerClock
                        .instant()
                        .atOffset(java.time.ZoneOffset.ofHours(8))
                        .getYear()));
    }

    @Test
    void refusesDraftVersionAndDisabledDataset() {
        Long draftVersion = datasetService.createVersion(
                new AiDatasetVersionSaveDTO().setDatasetId(datasetId).setDefinitionJson(DEFINITION));
        assertThatThrownBy(() -> queryPlanner.plan(request().setDatasetVersionId(draftVersion)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_VERSION_NOT_PUBLISHED));

        datasetService.updateStatus(
                datasetId, datasetService.getDataset(datasetId).getVersion(), false);
        assertThatThrownBy(() -> queryPlanner.plan(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_DISABLED));
        assertThat(datasetService.getVersion(publishedVersionId).getStatus())
                .as("停用不影响历史版本快照")
                .isEqualTo(AiDatasetVersionDO.STATUS_PUBLISHED);

        // 恢复启用后仍可规划（停用只是拦住新计划）
        datasetService.updateStatus(
                datasetId, datasetService.getDataset(datasetId).getVersion(), true);
        assertThat(queryPlanner.plan(request()).getKind()).isEqualTo("PLAN");
        assertThat(List.of(AiErrorCodeConstants.AI_QUERY_PLAN_INVALID.getCode()))
                .isNotEmpty();
    }
}
