package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A01 应用与凭据的真实 MySQL 集成验证：
 * appCode 唯一约束、Origin 归一化落库、**只存摘要**（库里没有明文）、启停生效、
 * 轮换/吊销默认无重叠且旧秘密立即失效（AT-005 的凭据前提、AT-011 的"无密文/明文"）。
 */
class AiApplicationPersistenceIT extends AbstractPersistenceIntegrationTest {

    private static final String APP_CODE = "it-crm-portal";

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private SqlSessionTemplate sqlSessionTemplate;

    @AfterEach
    void cleanUp() {
        Long applicationId = queryApplicationId();
        if (applicationId != null) {
            jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", applicationId);
            jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", applicationId);
        }
    }

    /** 清理用：含逻辑删除行。 */
    private Long queryApplicationId() {
        List<Long> ids =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 断言用：只查仍然有效（未逻辑删除）的行。 */
    private Long queryActiveApplicationId() {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM ai_application WHERE app_code = ? AND deleted = 0", Long.class, APP_CODE);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static AiApplicationSaveDTO saveDTO(List<String> origins) {
        return new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT CRM 门户")
                .setDescription("集成用例")
                .setOrigins(origins);
    }

    private AiApplicationCredentialIssueDTO createEnabledApplication(List<String> origins) {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(saveDTO(origins));
        applicationService.updateStatus(issue.getApplication().getId(), 0, true);
        return issue;
    }

    @Test
    void applicationPersistsNormalizedOriginsAndOnlySecretDigest() {
        // 大小写与默认端口归一化；路径型 Origin 非法，见下一个用例
        AiApplicationCredentialIssueDTO issue =
                createEnabledApplication(List.of("HTTPS://CRM.Example.com:443", "https://crm.example.com"));
        String secret = issue.getSecret();

        assertThat(secret).startsWith("aiapp_");
        // 库里只有摘要：直接查表也拿不到明文
        List<String> digests = jdbcTemplate.queryForList(
                "SELECT secret_digest FROM ai_application_credential WHERE application_id = ?",
                String.class,
                issue.getApplication().getId());
        assertThat(digests).hasSize(1);
        assertThat(digests.get(0)).hasSize(64).isNotEqualTo(secret);
        assertThat(digests.get(0)).doesNotContain(secret);

        String origins = jdbcTemplate.queryForObject(
                "SELECT origins FROM ai_application WHERE id = ?",
                String.class,
                issue.getApplication().getId());
        assertThat(origins).isEqualTo("[\"https://crm.example.com\"]");

        // 换票校验：启用 + ACTIVE + 摘要匹配
        assertThat(applicationService.authenticate(APP_CODE, secret).getAppCode())
                .isEqualTo(APP_CODE);
        assertThatThrownBy(() -> applicationService.authenticate(APP_CODE, secret + "x"))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_APPLICATION_CREDENTIAL_INVALID.getCode());
    }

    @Test
    void originWithPathOrWildcardIsRejectedBeforePersisting() {
        for (String invalid : List.of(
                "https://crm.example.com/path",
                "https://*.example.com",
                "https://crm.example.com?x=1",
                "ftp://crm.example.com")) {
            assertThatThrownBy(() -> applicationService.createApplication(saveDTO(List.of(invalid))))
                    .isInstanceOf(ServiceException.class);
        }
        sqlSessionTemplate.clearCache();
        assertThat(queryActiveApplicationId()).as("非法来源的应用不得落库").isNull();
    }

    @Test
    void applicationPageQueryFiltersByAppCodeAndStatus() {
        createEnabledApplication(List.of("https://crm.example.com"));

        PageResult<AiApplicationDO> page = applicationService.getApplicationPage(new PageParam(), "it-crm", true);

        assertThat(page.getList()).hasSize(1);
        assertThat(page.getList().get(0).getAppCode()).isEqualTo(APP_CODE);
        // 过滤条件不匹配时不返回
        assertThat(applicationService
                        .getApplicationPage(new PageParam(), "it-crm", false)
                        .getList())
                .isEmpty();
    }

    @Test
    void duplicateAppCodeIsRejectedByServiceGuard() {
        createEnabledApplication(List.of("https://crm.example.com"));

        assertThatThrownBy(() -> applicationService.createApplication(saveDTO(List.of("https://crm.example.com"))))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_APPLICATION_CODE_DUPLICATE.getCode());
    }

    @Test
    void rotatingAndRevokingInvalidatesOldSecretImmediatelyWithoutOverlap() {
        AiApplicationCredentialIssueDTO first = createEnabledApplication(List.of("https://crm.example.com"));
        String oldSecret = first.getSecret();
        AiApplicationDO application =
                applicationService.getApplication(first.getApplication().getId());

        AiApplicationCredentialIssueDTO rotated =
                applicationService.rotateCredential(application.getId(), application.getVersion());

        // 旧秘密立即失效，新秘密可用，且库中同时最多一条 ACTIVE
        assertThatThrownBy(() -> applicationService.authenticate(APP_CODE, oldSecret))
                .isInstanceOf(ServiceException.class);
        assertThat(applicationService.authenticate(APP_CODE, rotated.getSecret()))
                .isNotNull();
        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_application_credential WHERE application_id = ? AND status = 'ACTIVE'",
                Integer.class,
                application.getId());
        assertThat(activeCount).isEqualTo(1);

        // 吊销后新秘密也立即失效，且删除应用前必须先吊销（有可用凭据时拒绝删除）
        AiApplicationDO afterRotate = applicationService.getApplication(application.getId());
        assertThatThrownBy(() -> applicationService.deleteApplication(application.getId(), afterRotate.getVersion()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());

        applicationService.revokeCredential(application.getId(), afterRotate.getVersion());
        assertThatThrownBy(() -> applicationService.authenticate(APP_CODE, rotated.getSecret()))
                .isInstanceOf(ServiceException.class);

        AiApplicationDO revoked = applicationService.getApplication(application.getId());
        applicationService.deleteApplication(application.getId(), revoked.getVersion());
        sqlSessionTemplate.clearCache();
        assertThat(queryActiveApplicationId()).as("逻辑删除后按 appCode 查不到").isNull();
    }

    @Test
    void disablingApplicationBlocksAuthenticationEvenWithValidSecret() {
        AiApplicationCredentialIssueDTO issue = createEnabledApplication(List.of("https://crm.example.com"));
        AiApplicationDO application =
                applicationService.getApplication(issue.getApplication().getId());

        applicationService.updateStatus(application.getId(), application.getVersion(), false);

        assertThatThrownBy(() -> applicationService.authenticate(APP_CODE, issue.getSecret()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_APPLICATION_CREDENTIAL_INVALID.getCode());
    }
}
