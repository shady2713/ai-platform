package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.controller.admin.connector.vo.AiConnectorRespVO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorProbeDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.connector.AiConnectorReferenceChecker;
import com.basicframework.module.ai.service.connector.AiConnectorService;
import com.basicframework.module.ai.service.connector.dto.AiConnectorProbeResultDTO;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * D01 连接器端到端（真实 MySQL）：声明式配置落库、秘密只存密文且不回显、轮换递增版本、
 * 引用保护（引用方注册检查器后删除被拒）、MySQL 连接测试（真实连接 + 稳定失败原因码）、停用后拒绝探测。
 */
@Import(AiConnectorIT.ReferenceConfiguration.class)
class AiConnectorIT extends AbstractPersistenceIntegrationTest {

    /** 测试用引用检查器：模拟数据集/工具在各自卡片里注册的实现。 */
    @TestConfiguration
    static class ReferenceConfiguration {

        static final AtomicLong REFERENCED_CONNECTOR = new AtomicLong(-1);

        @Bean
        AiConnectorReferenceChecker testReferenceChecker() {
            return connectorId -> connectorId != null && connectorId == REFERENCED_CONNECTOR.get()
                    ? Optional.of("测试数据集 ds-1 正在使用该连接器")
                    : Optional.empty();
        }
    }

    private static final String CODE = "it-connector";

    @Autowired
    private AiConnectorService connectorService;

    @AfterEach
    void cleanUp() {
        ReferenceConfiguration.REFERENCED_CONNECTOR.set(-1);
        java.util.List<Long> connectorIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_connector WHERE code = ?", Long.class, CODE);
        for (Long connectorId : connectorIds) {
            jdbcTemplate.update("DELETE FROM ai_connector_probe WHERE connector_id = ?", connectorId);
            jdbcTemplate.update("DELETE FROM ai_connector WHERE id = ?", connectorId);
        }
    }

    /** 探测成功路径必须用容器的真实 root 密码：错误密码只能验证失败分支。 */
    private static AiConnectorSaveDTO mysqlSave() {
        return mysqlSave(mysqlRootPassword());
    }

    private static AiConnectorSaveDTO mysqlSave(String password) {
        return new AiConnectorSaveDTO()
                .setCode(CODE)
                .setName("IT MySQL 连接器")
                .setConnectorType("MYSQL")
                .setConfigJson("{\"host\":\"localhost\",\"port\":" + mysqlPort()
                        + ",\"database\":\"basic_framework\",\"username\":\"root\",\"sslMode\":\"DISABLED\"}")
                .setCredential(password);
    }

    private static String mysqlPort() {
        return String.valueOf(AbstractPersistenceIntegrationTest.mysqlMappedPort());
    }

    private static void assertCode(
            Throwable throwable, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void secretIsEncryptedAtRestAndNeverReturned() {
        Long id = connectorService.create(mysqlSave());

        String ciphertext = jdbcTemplate.queryForObject(
                "SELECT credential_ciphertext FROM ai_connector WHERE id = ?", String.class, id);
        assertThat(ciphertext)
                .as("库里只有密文，且不是明文")
                .isNotEqualTo("it-connector-password")
                .doesNotContain("it-connector-password");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT credential_revision FROM ai_connector WHERE id = ?", Integer.class, id))
                .isEqualTo(1);

        AiConnectorDO loaded = connectorService.getConnector(id);
        assertThat(loaded.getCredentialCiphertext()).isNotEqualTo("it-connector-password");
        assertThat(loaded.getConfigJson()).as("配置里不含秘密").doesNotContain("it-connector-password");

        // 轮换：秘密版本递增，旧密文被替换（create 写入密文时推进过一次版本，因此以当前版本为准）
        int currentVersion = connectorService.getConnector(id).getVersion();
        connectorService.rotateCredential(id, currentVersion, "it-connector-password-2");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT credential_revision FROM ai_connector WHERE id = ?", Integer.class, id))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT credential_ciphertext FROM ai_connector WHERE id = ?", String.class, id))
                .isNotEqualTo(ciphertext);

        // 声明式配置：连接串参数无法从外部注入（服务层拒绝）
        assertThatThrownBy(() -> connectorService.update(new AiConnectorSaveDTO()
                        .setId(id)
                        .setCode(CODE)
                        .setName("IT MySQL 连接器")
                        .setConnectorType("MYSQL")
                        .setConfigJson("{\"host\":\"localhost\",\"port\":" + mysqlPort()
                                + ",\"database\":\"basic_framework\",\"username\":\"root\","
                                + "\"allowLoadLocalInfile\":\"true\"}")
                        .setVersion(connectorService.getConnector(id).getVersion())))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_CONFIG_INVALID));
    }

    @Test
    void referencedConnectorCannotBeDeleted() {
        Long id = connectorService.create(mysqlSave());

        // 未被引用时可以删除
        connectorService.delete(id, connectorService.getConnector(id).getVersion());
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM ai_connector WHERE id = ?", Boolean.class, id))
                .isTrue();

        // 引用方注册后：删除被拒绝（引用保护）
        Long second = connectorService.create(mysqlSave());
        ReferenceConfiguration.REFERENCED_CONNECTOR.set(second);
        assertThatThrownBy(() -> connectorService.delete(
                        second, connectorService.getConnector(second).getVersion()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_REFERENCED));
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM ai_connector WHERE id = ?", Boolean.class, second))
                .as("被引用时不得删除")
                .isFalse();

        ReferenceConfiguration.REFERENCED_CONNECTOR.set(-1);
        connectorService.delete(second, connectorService.getConnector(second).getVersion());
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM ai_connector WHERE id = ?", Boolean.class, second))
                .isTrue();
    }

    @Test
    void mysqlProbeReportsStableOutcomeAndDisabledConnectorIsRejected() {
        Long id = connectorService.create(mysqlSave());

        // 正确凭据：真实连接到集成测试的 MySQL 容器 → SUPPORTED
        AiConnectorProbeResultDTO supported = connectorService.probe(id);
        assertThat(supported.getProbeKind()).isEqualTo(AiConnectorProbeDO.KIND_MYSQL_CONNECTIVITY);
        assertThat(supported.getStatus()).isEqualTo(AiConnectorProbeDO.STATUS_SUPPORTED);
        assertThat(supported.getDetailCode()).isNull();
        assertThat(supported.getLatencyMs()).isNotNull();

        // 错误凭据：FAILED + 稳定原因码（不含异常正文与主机）
        connectorService.rotateCredential(id, connectorService.getConnector(id).getVersion(), "wrong-password");
        AiConnectorProbeResultDTO failed = connectorService.probe(id);
        assertThat(failed.getStatus()).isEqualTo(AiConnectorProbeDO.STATUS_FAILED);
        assertThat(failed.getDetailCode()).isEqualTo("MYSQL_CONNECT_FAILED");

        assertThat(connectorService.listProbes(id))
                .extracting(AiConnectorProbeDO::getStatus)
                .containsExactly(AiConnectorProbeDO.STATUS_FAILED, AiConnectorProbeDO.STATUS_SUPPORTED);

        // 停用后不允许探测（避免停用后仍对外发起连接）
        connectorService.updateStatus(id, connectorService.getConnector(id).getVersion(), false);
        assertThatThrownBy(() -> connectorService.probe(id))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_DISABLED));
    }

    @Test
    void declarationAndResponsesKeepSecretsOutOfTheApi() {
        Long id = connectorService.create(mysqlSave());

        AiConnectorDO connector = connectorService.getConnector(id);
        assertThat(connector.getCode()).isEqualTo(CODE);
        assertThat(connector.getConnectorType()).isEqualTo(AiConnectorDO.TYPE_MYSQL);
        assertThat(connector.getStatus()).isEqualTo(AiConnectorDO.STATUS_ENABLED);
        assertThat(connector.getReferenced()).isFalse();

        // 协议层响应只回"是否已配置"（字段名单里没有秘密与密文）
        AiConnectorRespVO respVO = new AiConnectorRespVO()
                .setId(connector.getId())
                .setCredentialConfigured(connector.getCredentialCiphertext() != null)
                .setConfigJson(connector.getConfigJson());
        assertThat(respVO.getCredentialConfigured()).isTrue();
        assertThat(respVO.toString()).doesNotContain("it-connector-password");

        // 分页与过滤：只回当前连接器，且响应不含秘密
        assertThat(connectorService
                        .getPage(new com.basicframework.framework.common.pojo.PageParam(), "MYSQL", null)
                        .getList())
                .extracting(AiConnectorDO::getCode)
                .contains(CODE);
        assertThat(connectorService
                        .getPage(new com.basicframework.framework.common.pojo.PageParam(), "HTTP", null)
                        .getList())
                .extracting(AiConnectorDO::getCode)
                .doesNotContain(CODE);

        // 标识唯一
        assertThatThrownBy(() -> connectorService.create(mysqlSave()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_CODE_DUPLICATE));
    }
}
