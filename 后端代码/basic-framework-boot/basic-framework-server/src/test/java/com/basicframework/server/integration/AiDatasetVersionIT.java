package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetVersionDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.connector.AiConnectorService;
import com.basicframework.module.ai.service.connector.AiMysqlConnectorService;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.dataset.AiDatasetVersionReferenceChecker;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetSaveDTO;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetVersionSaveDTO;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetVersionVerifyResultDTO;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
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
 * D04 数据集语义版本端到端（真实 MySQL + 真实只读账号 + 真实上游结构变化）。
 *
 * <p>覆盖卡片验收：未知列/无权限策略不可发布、结构漂移后置待验证并在发布前重校验、
 * 旧报表引用的版本可追溯（版本快照不可变 + 引用保护），以及来源对象必须在连接器授权白名单内。
 */
@Import(AiDatasetVersionIT.ReferenceConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiDatasetVersionIT extends AbstractPersistenceIntegrationTest {

    /** 测试用版本引用检查器：模拟报表（R04）在各自卡片里注册的实现。 */
    @TestConfiguration
    static class ReferenceConfiguration {

        static final AtomicLong REFERENCED_VERSION = new AtomicLong(-1);

        @Bean
        AiDatasetVersionReferenceChecker testVersionReferenceChecker() {
            return datasetVersionId -> datasetVersionId != null && datasetVersionId == REFERENCED_VERSION.get()
                    ? Optional.of("测试报表 r-1 的版本 2 正在使用该数据集版本")
                    : Optional.empty();
        }
    }

    private static final String CONNECTOR_CODE = "it-dataset-connector";

    private static final String DATASET_CODE = "it-dataset-orders";

    /** 只读账号口令（仅集成容器内的测试值）。 */
    private static final String READ_ONLY_PASSWORD = "d04-it-readonly-password";

    private static final String CATALOG = "d04_catalog";

    private static final String DEFINITION_V1 =
            """
            {"grain": "一行一单",
             "time": {"field": "created_at", "granularity": "DAY", "timezone": "Asia/Shanghai"},
             "fields": [
               {"name": "order_id", "sourceColumn": "id", "type": "NUMBER", "unit": "COUNT",
                "visibility": "PUBLIC"},
               {"name": "amount", "sourceColumn": "amount", "type": "DECIMAL", "unit": "CURRENCY",
                "visibility": "INTERNAL"},
               {"name": "created_at", "sourceColumn": "created_at", "type": "DATETIME",
                "visibility": "INTERNAL"},
               {"name": "status", "sourceColumn": "status", "type": "ENUM",
                "enumValues": ["PAID", "REFUNDED"], "visibility": "RESTRICTED",
                "permission": "ai:dataset:field:status"}],
             "metrics": [{"name": "total_amount", "field": "amount", "aggregation": "SUM",
                          "unit": "CURRENCY", "visibility": "INTERNAL"}],
             "dimensions": [{"name": "order_status", "field": "status"}]}
            """;

    /** 两个字段共用同一别名：定义层必须识别为歧义。 */
    private static final String AMBIGUOUS_ALIAS_DEFINITION = "{\"grain\": \"一行一单\", \"fields\": ["
            + "{\"name\": \"order_id\", \"sourceColumn\": \"id\", \"type\": \"NUMBER\","
            + " \"aliases\": [\"金额\"], \"visibility\": \"PUBLIC\"},"
            + "{\"name\": \"amount\", \"sourceColumn\": \"amount\", \"type\": \"DECIMAL\","
            + " \"aliases\": [\"金额\"], \"visibility\": \"PUBLIC\"}]}";

    @Autowired
    private AiConnectorService connectorService;

    @Autowired
    private AiMysqlConnectorService mysqlConnectorService;

    @Autowired
    private AiDatasetService datasetService;

    private Long connectorId;

    private Long datasetId;

    @BeforeEach
    void prepareCatalog() {
        jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS " + CATALOG);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".orders");
        jdbcTemplate.execute("CREATE TABLE " + CATALOG + ".orders ("
                + "id BIGINT PRIMARY KEY, amount DECIMAL(12,2) NOT NULL,"
                + " created_at DATETIME NOT NULL, status VARCHAR(16) NOT NULL)");
        jdbcTemplate.update("INSERT INTO " + CATALOG + ".orders (id, amount, created_at, status) VALUES"
                + " (1, 12.50, '2026-09-01 10:00:00', 'PAID'), (2, 7.00, '2026-09-02 10:00:00', 'REFUNDED')");
        jdbcTemplate.execute("DROP USER IF EXISTS 'd04_ro'@'%'");
        jdbcTemplate.execute("CREATE USER 'd04_ro'@'%' IDENTIFIED BY '" + READ_ONLY_PASSWORD + "'");
        jdbcTemplate.execute("GRANT SELECT ON " + CATALOG + ".* TO 'd04_ro'@'%'");
        jdbcTemplate.execute("FLUSH PRIVILEGES");

        connectorId = connectorService.create(new AiConnectorSaveDTO()
                .setCode(CONNECTOR_CODE)
                .setName("IT 数据集连接器")
                .setConnectorType(AiConnectorDO.TYPE_MYSQL)
                .setConfigJson("{\"host\":\"localhost\",\"port\":" + mysqlPort() + ",\"database\":\"" + CATALOG
                        + "\",\"username\":\"d04_ro\",\"sslMode\":\"REQUIRED\",\"allowedObjects\":[\"" + CATALOG
                        + ".orders\"]}")
                .setCredential(READ_ONLY_PASSWORD));
        datasetId = datasetService.create(new AiDatasetSaveDTO()
                .setCode(DATASET_CODE)
                .setName("IT 订单数据集")
                .setConnectorId(connectorId)
                .setSourceObject(CATALOG + ".orders"));
    }

    @AfterEach
    void cleanUp() {
        ReferenceConfiguration.REFERENCED_VERSION.set(-1);
        mysqlConnectorService.closePool(connectorId);
        jdbcTemplate.update(
                "DELETE FROM ai_dataset_version WHERE dataset_id IN" + " (SELECT id FROM ai_dataset WHERE code = ?)",
                DATASET_CODE);
        jdbcTemplate.update("DELETE FROM ai_dataset WHERE code = ?", DATASET_CODE);
        jdbcTemplate.update("DELETE FROM ai_connector WHERE code = ?", CONNECTOR_CODE);
        jdbcTemplate.execute("DROP USER IF EXISTS 'd04_ro'@'%'");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".orders");
        jdbcTemplate.execute("DROP DATABASE IF EXISTS " + CATALOG);
        connectorId = null;
        datasetId = null;
    }

    private static String mysqlPort() {
        return String.valueOf(AbstractPersistenceIntegrationTest.mysqlMappedPort());
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private Long createVersion(String definitionJson) {
        return datasetService.createVersion(
                new AiDatasetVersionSaveDTO().setDatasetId(datasetId).setDefinitionJson(definitionJson));
    }

    @Test
    void publishesVerifiedVersionAndKeepsOlderSnapshotTraceable() {
        Long firstVersion = createVersion(DEFINITION_V1);

        AiDatasetVersionVerifyResultDTO verified = datasetService.verifyVersion(
                firstVersion, datasetService.getVersion(firstVersion).getVersion());
        assertThat(verified.getVerificationStatus()).isEqualTo(AiDatasetVersionDO.VERIFICATION_VERIFIED);
        assertThat(verified.isPublishable()).isTrue();
        assertThat(verified.getMissingColumns()).isEmpty();
        assertThat(verified.getAddedColumns()).isEmpty();
        assertThat(verified.getSourceSchemaHash()).hasSize(64);

        datasetService.publishVersion(
                firstVersion, datasetService.getVersion(firstVersion).getVersion());
        assertThat(datasetService.getVersion(firstVersion).getStatus()).isEqualTo(AiDatasetVersionDO.STATUS_PUBLISHED);
        assertThat(datasetService.getDataset(datasetId).getPublishedVersionNo()).isEqualTo(1);

        // 第二个版本（新增一个字段）发布后，第一个版本的快照与哈希保持不变（旧报表可追溯）
        String withNote = DEFINITION_V1.replace(
                "{\"name\": \"order_id\"",
                "{\"name\": \"note\", \"sourceColumn\": \"amount\", \"type\": \"DECIMAL\","
                        + " \"visibility\": \"INTERNAL\"}, {\"name\": \"order_id\"");
        Long secondVersion = createVersion(withNote);
        assertThat(secondVersion).isNotEqualTo(firstVersion);

        AiDatasetVersionDO firstSnapshot = datasetService.getVersion(firstVersion);
        assertThat(firstSnapshot.getVersionNo()).isEqualTo(1);
        assertThat(firstSnapshot.getDefinitionJson()).doesNotContain("\"name\": \"note\"");
        assertThat(firstSnapshot.getSchemaHash()).isEqualTo(verified.getSchemaHash());

        // 已发布版本不可修改：再次验证/发布都被拒绝
        assertThatThrownBy(() -> datasetService.verifyVersion(firstVersion, firstSnapshot.getVersion()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_VERSION_IMMUTABLE));

        assertThat(datasetService
                        .getVersionPage(datasetId, new com.basicframework.framework.common.pojo.PageParam())
                        .getTotal())
                .isEqualTo(2L);
        assertThat(datasetService
                        .getDatasetPage(
                                new com.basicframework.framework.common.pojo.PageParam(),
                                connectorId,
                                AiDatasetDO.STATUS_ENABLED)
                        .getList())
                .extracting(AiDatasetDO::getCode)
                .contains(DATASET_CODE);
    }

    @Test
    void detectsDriftAndRequiresFreshVerificationBeforePublishing() {
        Long versionId = createVersion(DEFINITION_V1);
        datasetService.verifyVersion(
                versionId, datasetService.getVersion(versionId).getVersion());

        // 上游新增列：仍可发布，但记录为漂移信息
        jdbcTemplate.execute("ALTER TABLE " + CATALOG + ".orders ADD COLUMN channel VARCHAR(16) NULL");
        AiDatasetVersionVerifyResultDTO addedOnly = datasetService.verifyVersion(
                versionId, datasetService.getVersion(versionId).getVersion());
        assertThat(addedOnly.getAddedColumns()).containsExactly("channel");
        assertThat(addedOnly.isPublishable()).isTrue();

        // 上游删列：定义引用的列缺失 → 待验证，且不可发布
        jdbcTemplate.execute("ALTER TABLE " + CATALOG + ".orders DROP COLUMN status");
        AiDatasetVersionVerifyResultDTO drifted = datasetService.verifyVersion(
                versionId, datasetService.getVersion(versionId).getVersion());
        assertThat(drifted.getVerificationStatus()).isEqualTo(AiDatasetVersionDO.VERIFICATION_DRIFTED);
        assertThat(drifted.getMissingColumns()).containsExactly("status");
        assertThat(drifted.isPublishable()).isFalse();
        assertThat(datasetService.getVersion(versionId).getDriftJson()).contains("missing=status");

        assertThatThrownBy(() -> datasetService.publishVersion(
                        versionId, datasetService.getVersion(versionId).getVersion()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_VERSION_NOT_VERIFIED));

        // 上游类型变化：同样不可发布（varchar -> bigint 不再兼容 STRING）
        jdbcTemplate.execute("ALTER TABLE " + CATALOG + ".orders ADD COLUMN status VARCHAR(16) NULL");
        jdbcTemplate.execute("ALTER TABLE " + CATALOG + ".orders MODIFY COLUMN amount VARCHAR(32) NOT NULL");
        AiDatasetVersionVerifyResultDTO typeChanged = datasetService.verifyVersion(
                versionId, datasetService.getVersion(versionId).getVersion());
        assertThat(typeChanged.getTypeChangedColumns()).containsExactly("amount");
        assertThat(typeChanged.isPublishable()).isFalse();
    }

    @Test
    void refusesPublishWhenUpstreamChangedAfterVerification() {
        Long versionId = createVersion(DEFINITION_V1);
        datasetService.verifyVersion(
                versionId, datasetService.getVersion(versionId).getVersion());

        // 验证通过之后上游结构再变化：发布时必须重新确认，置 DRIFTED 并拒绝
        jdbcTemplate.execute("ALTER TABLE " + CATALOG + ".orders DROP COLUMN amount");
        assertThatThrownBy(() -> datasetService.publishVersion(
                        versionId, datasetService.getVersion(versionId).getVersion()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_VERSION_DRIFTED));
        assertThat(datasetService.getVersion(versionId).getVerificationStatus())
                .isEqualTo(AiDatasetVersionDO.VERIFICATION_DRIFTED);
    }

    @Test
    void rejectsUnauthorizedSourceAndUnknownColumns() {
        // 来源对象不在连接器白名单内
        assertThatThrownBy(() -> datasetService.create(new AiDatasetSaveDTO()
                        .setCode("it-dataset-other")
                        .setName("未授权来源")
                        .setConnectorId(connectorId)
                        .setSourceObject(CATALOG + ".customers")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_SOURCE_NOT_AUTHORIZED));

        // 未知列：验证即判定不可发布（未知列不可发布）
        Long versionId =
                createVersion(DEFINITION_V1.replace("\"sourceColumn\": \"amount\"", "\"sourceColumn\": \"nope\""));
        AiDatasetVersionVerifyResultDTO unknown = datasetService.verifyVersion(
                versionId, datasetService.getVersion(versionId).getVersion());
        assertThat(unknown.getMissingColumns()).containsExactly("nope");
        assertThat(unknown.isPublishable()).isFalse();
        assertThatThrownBy(() -> datasetService.publishVersion(
                        versionId, datasetService.getVersion(versionId).getVersion()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_VERSION_NOT_VERIFIED));

        // 无权限策略（RESTRICTED 缺权限码）在定义层就被拒绝
        assertThatThrownBy(() -> createVersion(
                        DEFINITION_V1.replace("\"permission\": \"ai:dataset:field:status\"", "\"permission\": \"\"")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_DEFINITION_INVALID));

        // 别名歧义在定义层被识别
        assertThatThrownBy(() -> createVersion(AMBIGUOUS_ALIAS_DEFINITION))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_ALIAS_AMBIGUOUS));
    }

    @Test
    void protectsReferencedVersionsAndConnectorDeletion() {
        Long versionId = createVersion(DEFINITION_V1);
        datasetService.verifyVersion(
                versionId, datasetService.getVersion(versionId).getVersion());
        datasetService.publishVersion(
                versionId, datasetService.getVersion(versionId).getVersion());

        // 报表引用版本后，数据集删除被拒绝（版本行保留，历史引用可追溯）
        ReferenceConfiguration.REFERENCED_VERSION.set(versionId);
        assertThatThrownBy(() -> datasetService.delete(
                        datasetId, datasetService.getDataset(datasetId).getVersion()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_REFERENCED));

        // 连接器被数据集引用：删除同样被拒绝（D04 注册的引用检查生效）
        assertThatThrownBy(() -> connectorService.delete(
                        connectorId, connectorService.getConnector(connectorId).getVersion()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_REFERENCED));

        ReferenceConfiguration.REFERENCED_VERSION.set(-1);
        datasetService.delete(datasetId, datasetService.getDataset(datasetId).getVersion());
        assertThat(datasetService.getVersion(versionId)).as("数据集删除后版本快照仍可追溯").isNotNull();
    }

    @Test
    void disabledDatasetCannotCreateVerifyOrPublishVersions() {
        Long versionId = createVersion(DEFINITION_V1);
        AiDatasetDO dataset = datasetService.getDataset(datasetId);
        datasetService.updateStatus(datasetId, dataset.getVersion(), false);

        assertThatThrownBy(() -> createVersion(DEFINITION_V1))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_DISABLED));
        assertThatThrownBy(() -> datasetService.verifyVersion(
                        versionId, datasetService.getVersion(versionId).getVersion()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_DISABLED));
        assertThatThrownBy(() -> datasetService.publishVersion(
                        versionId, datasetService.getVersion(versionId).getVersion()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_DISABLED));

        // 停用不影响已存在的版本快照读取
        assertThat(datasetService.getVersion(versionId).getStatus()).isEqualTo(AiDatasetVersionDO.STATUS_DRAFT);
    }
}
