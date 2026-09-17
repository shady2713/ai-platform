package com.basicframework.module.ai.service.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationCredentialDO;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.dal.mysql.application.AiApplicationCredentialMapper;
import com.basicframework.module.ai.dal.mysql.application.AiApplicationMapper;
import com.basicframework.module.ai.domain.application.ApplicationOrigins;
import com.basicframework.module.ai.domain.application.ApplicationSecrets;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A01 应用与凭据服务：appCode 唯一、精确 Origin、秘密只存摘要、轮换/吊销无重叠且立即生效。
 */
class AiApplicationServiceImplTest {

    private AiApplicationMapper applicationMapper;

    private AiApplicationCredentialMapper credentialMapper;

    private AiApplicationServiceImpl service;

    @BeforeEach
    void setUp() {
        applicationMapper = mock(AiApplicationMapper.class);
        credentialMapper = mock(AiApplicationCredentialMapper.class);
        service = new AiApplicationServiceImpl(applicationMapper, credentialMapper);
    }

    private static AiApplicationSaveDTO saveDTO(List<String> origins) {
        return new AiApplicationSaveDTO()
                .setAppCode("crm-portal")
                .setName("CRM 门户")
                .setDescription("对接 CRM")
                .setOrigins(origins);
    }

    private static AiApplicationDO application(Long id, int version, boolean enabled) {
        return new AiApplicationDO()
                .setId(id)
                .setAppCode("crm-portal")
                .setName("CRM 门户")
                .setDescription("对接 CRM")
                .setOrigins(ApplicationOrigins.normalizeToJson(List.of("https://crm.example.com")))
                .setEnabled(enabled)
                .setVersion(version);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, int code) {
        assertThatThrownBy(callable)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(code);
    }

    @Test
    void createNormalizesOriginsIssuesSecretAndStoresOnlyDigest() {
        when(applicationMapper.selectByAppCode("crm-portal")).thenReturn(null);
        when(applicationMapper.insert(any(AiApplicationDO.class))).thenAnswer(invocation -> {
            ((AiApplicationDO) invocation.getArgument(0)).setId(5L);
            return 1;
        });
        ArgumentCaptor<AiApplicationCredentialDO> credentialCaptor =
                ArgumentCaptor.forClass(AiApplicationCredentialDO.class);

        AiApplicationCredentialIssueDTO issue =
                service.createApplication(saveDTO(List.of("HTTPS://CRM.Example.com:443", "https://crm.example.com")));

        assertThat(issue.getSecret()).startsWith(ApplicationSecrets.PREFIX);
        assertThat(issue.getApplication().getId()).isEqualTo(5L);

        ArgumentCaptor<AiApplicationDO> applicationCaptor = ArgumentCaptor.forClass(AiApplicationDO.class);
        verify(applicationMapper).insert(applicationCaptor.capture());
        assertThat(applicationCaptor.getValue().getOrigins())
                .as("默认端口被去掉且大小写归一化，重复来源只保留一条")
                .isEqualTo("[\"https://crm.example.com\"]");

        verify(credentialMapper).insert(credentialCaptor.capture());
        assertThat(credentialCaptor.getValue().getSecretDigest())
                .isNotEmpty()
                .isNotEqualTo(issue.getSecret())
                .hasSize(64);
        assertThat(credentialCaptor.getValue().getStatus()).isEqualTo(AiApplicationCredentialDO.STATUS_ACTIVE);
        assertThat(issue.getSecret()).isNotEqualTo(credentialCaptor.getValue().getSecretDigest());
    }

    @Test
    void createRejectsDuplicateAppCodeAndInvalidOrigins() {
        when(applicationMapper.selectByAppCode("crm-portal")).thenReturn(application(5L, 0, false));

        assertCode(
                () -> service.createApplication(saveDTO(List.of("https://crm.example.com"))),
                AiErrorCodeConstants.AI_APPLICATION_CODE_DUPLICATE.getCode());
        verify(applicationMapper, never()).insert(any(AiApplicationDO.class));

        when(applicationMapper.selectByAppCode("crm-portal")).thenReturn(null);
        for (String invalid : List.of(
                "https://crm.example.com/path",
                "https://*.example.com",
                "https://crm.example.com?x=1",
                "ftp://crm.example.com",
                "https://user:pass@crm.example.com",
                "crm.example.com")) {
            assertCode(
                    () -> service.createApplication(saveDTO(List.of(invalid))),
                    AiErrorCodeConstants.AI_APPLICATION_ORIGIN_INVALID.getCode());
        }
        assertCode(
                () -> service.createApplication(saveDTO(List.of())),
                AiErrorCodeConstants.AI_APPLICATION_ORIGIN_INVALID.getCode());
    }

    @Test
    void rotateRevokesOldCredentialImmediatelyAndIssuesNewOne() {
        when(applicationMapper.selectById(5L)).thenReturn(application(5L, 3, true));
        when(applicationMapper.updateWithVersion(any(AiApplicationDO.class), any()))
                .thenReturn(1);
        AiApplicationCredentialDO oldCredential = new AiApplicationCredentialDO()
                .setId(11L)
                .setApplicationId(5L)
                .setSecretDigest(ApplicationSecrets.digest("aiapp_old"))
                .setStatus(AiApplicationCredentialDO.STATUS_ACTIVE);
        when(credentialMapper.selectActiveByApplication(5L)).thenReturn(List.of(oldCredential));

        AiApplicationCredentialIssueDTO issue = service.rotateCredential(5L, 3);

        ArgumentCaptor<AiApplicationCredentialDO> revoked = ArgumentCaptor.forClass(AiApplicationCredentialDO.class);
        verify(credentialMapper).updateById(revoked.capture());
        assertThat(revoked.getValue().getStatus()).isEqualTo(AiApplicationCredentialDO.STATUS_REVOKED);
        assertThat(revoked.getValue().getRevokedTime()).isNotNull();
        // 新秘密与旧秘密不同，且旧秘密的摘要不再出现在可用列表里
        assertThat(issue.getSecret()).isNotEqualTo("aiapp_old");
        assertThat(credentialMapper.selectActiveByDigest(ApplicationSecrets.digest("aiapp_old")))
                .isNull();
    }

    @Test
    void revokeAndStatusAndDeleteFollowCasAndCredentialRules() {
        when(applicationMapper.selectById(5L)).thenReturn(application(5L, 1, true));
        when(applicationMapper.updateWithVersion(any(AiApplicationDO.class), any()))
                .thenReturn(1);
        when(credentialMapper.selectActiveByApplication(5L)).thenReturn(List.of());

        service.revokeCredential(5L, 1);
        verify(applicationMapper).updateWithVersion(any(AiApplicationDO.class), any());

        // 有可用凭据时不允许删除
        when(credentialMapper.selectActiveByApplication(5L))
                .thenReturn(List.of(new AiApplicationCredentialDO().setId(1L)));
        assertCode(() -> service.deleteApplication(5L, 2), AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());

        when(credentialMapper.selectActiveByApplication(5L)).thenReturn(List.of());
        service.deleteApplication(5L, 2);
        verify(applicationMapper).deleteById(5L);

        // CAS 冲突
        when(applicationMapper.updateWithVersion(any(AiApplicationDO.class), any()))
                .thenReturn(0);
        assertCode(() -> service.updateStatus(5L, 9, true), AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());
    }

    @Test
    void updateKeepsAppCodeImmutable() {
        when(applicationMapper.selectById(5L)).thenReturn(application(5L, 2, true));

        assertCode(
                () -> service.updateApplication(new AiApplicationSaveDTO()
                        .setId(5L)
                        .setAppCode("other-code")
                        .setName("改名")
                        .setOrigins(List.of("https://crm.example.com"))
                        .setVersion(2)),
                AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());
        verify(applicationMapper, never()).updateWithVersion(any(), any());
    }

    @Test
    void authenticateRequiresEnabledApplicationActiveCredentialAndMatchingSecret() {
        String secret = "aiapp_secret";
        AiApplicationDO enabled = application(5L, 1, true);
        AiApplicationCredentialDO credential = new AiApplicationCredentialDO()
                .setId(11L)
                .setApplicationId(5L)
                .setSecretDigest(ApplicationSecrets.digest(secret))
                .setStatus(AiApplicationCredentialDO.STATUS_ACTIVE);
        when(applicationMapper.selectByAppCode("crm-portal")).thenReturn(enabled);
        when(credentialMapper.selectActiveByDigest(ApplicationSecrets.digest(secret)))
                .thenReturn(credential);

        assertThat(service.authenticate("crm-portal", secret)).isSameAs(enabled);

        // 凭据错误、应用停用、应用不存在、参数缺失：统一 401 语义
        assertCode(
                () -> service.authenticate("crm-portal", "aiapp_wrong"),
                AiErrorCodeConstants.AI_APPLICATION_CREDENTIAL_INVALID.getCode());
        assertCode(
                () -> service.authenticate("crm-portal", null),
                AiErrorCodeConstants.AI_APPLICATION_CREDENTIAL_INVALID.getCode());
        when(applicationMapper.selectByAppCode("crm-portal")).thenReturn(application(5L, 1, false));
        assertCode(
                () -> service.authenticate("crm-portal", secret),
                AiErrorCodeConstants.AI_APPLICATION_CREDENTIAL_INVALID.getCode());
        when(applicationMapper.selectByAppCode("crm-portal")).thenReturn(null);
        assertCode(
                () -> service.authenticate("crm-portal", secret),
                AiErrorCodeConstants.AI_APPLICATION_CREDENTIAL_INVALID.getCode());
    }

    @Test
    void authenticateRejectsCredentialFromAnotherApplication() {
        String secret = "aiapp_secret";
        when(applicationMapper.selectByAppCode("crm-portal")).thenReturn(application(5L, 1, true));
        // 摘要命中但属于另一应用：不得通过
        when(credentialMapper.selectActiveByDigest(ApplicationSecrets.digest(secret)))
                .thenReturn(new AiApplicationCredentialDO()
                        .setId(11L)
                        .setApplicationId(6L)
                        .setSecretDigest(ApplicationSecrets.digest(secret))
                        .setStatus(AiApplicationCredentialDO.STATUS_ACTIVE));

        assertCode(
                () -> service.authenticate("crm-portal", secret),
                AiErrorCodeConstants.AI_APPLICATION_CREDENTIAL_INVALID.getCode());
    }
}
