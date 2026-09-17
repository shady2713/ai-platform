package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 模型端点持久化的真实 MySQL 集成验证（M01）：
 * 版本化、凭据加密存储与独立轮换、乐观锁 CAS、引用保护、启停与分页/版本查询，
 * 同时覆盖 Mapper 默认查询方法（单测中这些方法被 mock）。
 */
class AiModelEndpointPersistenceIT extends AbstractPersistenceIntegrationTest {

    private static final String NAME = "it-openai-endpoint";

    @Autowired
    private AiModelEndpointService endpointService;

    @Autowired
    private SqlSessionTemplate sqlSessionTemplate;

    @AfterEach
    void cleanUp() {
        // 先删版本（外键 RESTRICT），再删端点
        Long endpointId = queryEndpointId();
        if (endpointId != null) {
            jdbcTemplate.update("DELETE FROM ai_model_endpoint_revision WHERE endpoint_id = ?", endpointId);
            jdbcTemplate.update("DELETE FROM ai_model_endpoint WHERE id = ?", endpointId);
        }
    }

    private Long queryEndpointId() {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM ai_model_endpoint WHERE name = ?", Long.class, NAME);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static AiModelEndpointSaveDTO saveReq(String modelId, List<String> capabilities, String credential) {
        AiModelEndpointSaveDTO reqVO = new AiModelEndpointSaveDTO();
        reqVO.setName(NAME);
        reqVO.setProvider("openai_compatible");
        reqVO.setBaseUrl("https://api.example.com/v1");
        reqVO.setModelId(modelId);
        reqVO.setCapabilities(capabilities);
        reqVO.setCredential(credential);
        return reqVO;
    }

    @Test
    void endpointPersistenceFollowsVersioningAndCredentialRules() {
        // 1) 创建：版本 1 + 凭据版本 1，密文不含明文
        Long endpointId = endpointService.createEndpoint(saveReq("gpt-4o-mini", List.of("TEXT"), "sk-plain-secret"));
        AiModelEndpointDO created = endpointService.getEndpoint(endpointId);
        assertThat(created.getConfigRevision()).isEqualTo(1);
        assertThat(created.getCredentialRevision()).isEqualTo(1);
        assertThat(created.getEnabled()).isFalse();
        assertThat(created.getCredentialCiphertext()).doesNotContain("sk-plain-secret");

        List<AiModelEndpointRevisionDO> revisions = endpointService.getRevisions(endpointId);
        assertThat(revisions).hasSize(1);
        assertThat(revisions.get(0).getModelId()).isEqualTo("gpt-4o-mini");
        // 历史版本表不含任何凭据列
        assertThat(jdbcTemplate.queryForList(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema = DATABASE() AND table_name = 'ai_model_endpoint_revision'",
                        String.class))
                .noneMatch(column -> column.toLowerCase().contains("credential"));

        // 2) 修改非秘密配置：产生不可变版本 2，版本 1 内容不变
        AiModelEndpointSaveDTO update = saveReq("gpt-4o", List.of("TEXT", "EMBEDDING"), null);
        update.setId(endpointId);
        update.setVersion(created.getVersion());
        endpointService.updateEndpoint(update);
        AiModelEndpointDO updated = endpointService.getEndpoint(endpointId);
        assertThat(updated.getConfigRevision()).isEqualTo(2);
        assertThat(updated.getCredentialRevision()).isEqualTo(1);
        assertThat(endpointService.getRevisions(endpointId)).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT model_id FROM ai_model_endpoint_revision WHERE endpoint_id = ? AND revision = 1",
                        String.class,
                        endpointId))
                .isEqualTo("gpt-4o-mini");

        // 3) 乐观锁：过期版本被拒绝
        AiModelEndpointSaveDTO stale = saveReq("gpt-4o", List.of("TEXT"), null);
        stale.setId(endpointId);
        stale.setVersion(0);
        assertServiceCode(AiErrorCodeConstants.AI_STATE_CONFLICT, () -> endpointService.updateEndpoint(stale));
        // 被拒绝的更新不得留下孤儿版本行（事务回滚 + 版本号必须仍是 [1, 2]）
        assertThat(endpointService.getRevisions(endpointId))
                .extracting(AiModelEndpointRevisionDO::getRevision)
                .containsExactly(2, 1);

        // 4) 凭据轮换：只递增凭据版本，配置版本与历史不变
        String cipherBefore = updated.getCredentialCiphertext();
        endpointService.rotateCredential(endpointId, updated.getVersion(), "sk-rotated-secret");
        AiModelEndpointDO rotated = endpointService.getEndpoint(endpointId);
        assertThat(rotated.getCredentialRevision()).isEqualTo(2);
        assertThat(rotated.getConfigRevision()).isEqualTo(2);
        assertThat(rotated.getCredentialCiphertext()).isNotEqualTo(cipherBefore);
        assertThat(endpointService.getRevisions(endpointId)).hasSize(2);

        // 5) 引用后 provider/base_url 冻结
        endpointService.markReferenced(endpointId, rotated.getVersion());
        AiModelEndpointSaveDTO addressChange = saveReq("gpt-4o", List.of("TEXT"), null);
        addressChange.setId(endpointId);
        addressChange.setVersion(rotated.getVersion() + 1);
        addressChange.setBaseUrl("https://api.other.com/v1");
        assertServiceCode(AiErrorCodeConstants.AI_STATE_CONFLICT, () -> endpointService.updateEndpoint(addressChange));
        assertServiceCode(
                AiErrorCodeConstants.AI_STATE_CONFLICT,
                () -> endpointService.deleteEndpoint(endpointId, rotated.getVersion() + 1));

        // 6) 启停与启用校验（Mapper 分页与版本查询走真实 SQL）
        AiModelEndpointDO referenced = endpointService.getEndpoint(endpointId);
        endpointService.updateEndpointStatus(endpointId, referenced.getVersion(), true);
        assertThat(endpointService.getEnabledEndpoint(endpointId).getEnabled()).isTrue();
        endpointService.updateEndpointStatus(endpointId, referenced.getVersion() + 1, false);
        assertServiceCode(
                AiErrorCodeConstants.AI_MODEL_ENDPOINT_DISABLED, () -> endpointService.getEnabledEndpoint(endpointId));

        var pageParam = new com.basicframework.framework.common.pojo.PageParam()
                .setPageNo(1)
                .setPageSize(10);
        assertThat(endpointService
                        .getEndpointPage(pageParam, NAME, "openai_compatible")
                        .getList())
                .anyMatch(endpoint -> endpoint.getId().equals(endpointId));

        // 7) 清理并删除（未引用状态下可删）——先解除引用
        jdbcTemplate.update("UPDATE ai_model_endpoint SET referenced = b'0' WHERE id = ?", endpointId);
        // 原生 SQL 绕过 MyBatis 一级缓存：后续读取前显式清理，避免读到旧的 referenced=true
        sqlSessionTemplate.clearCache();
        AiModelEndpointDO beforeDelete = endpointService.getEndpoint(endpointId);
        endpointService.deleteEndpoint(endpointId, beforeDelete.getVersion());
        assertThat(jdbcTemplate.queryForList(
                        "SELECT id FROM ai_model_endpoint WHERE id = ? AND deleted = b'0'", Long.class, endpointId))
                .isEmpty();
    }

    @Test
    void databaseCollationAndColumnsMatchSnapshotExpectations() {
        assertThat(jdbcTemplate.queryForList(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema = DATABASE() AND table_name = 'ai_model_endpoint' "
                                + "AND column_name IN ('credential_ciphertext', 'referenced', 'version')",
                        String.class))
                .containsExactlyInAnyOrder("credential_ciphertext", "referenced", "version");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT table_collation FROM information_schema.tables "
                                + "WHERE table_schema = DATABASE() AND table_name = 'ai_model_endpoint'",
                        String.class))
                .isEqualTo("utf8mb4_unicode_ci");
    }

    private static void assertServiceCode(
            com.basicframework.framework.common.exception.ErrorCode expected, ThrowingOperation action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }
}
