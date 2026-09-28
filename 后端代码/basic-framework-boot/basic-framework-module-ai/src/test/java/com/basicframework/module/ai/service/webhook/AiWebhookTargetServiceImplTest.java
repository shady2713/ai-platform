package com.basicframework.module.ai.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.config.SecurityProperties;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookTargetDO;
import com.basicframework.module.ai.dal.mysql.webhook.AiWebhookTargetMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.webhook.dto.AiWebhookTargetSaveDTO;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Webhook 目标服务（X10）：形状收窄、密钥只落密文、轮换递增版本、停用即停发可判定。
 *
 * <p>断言以对外承诺的事实为准：落库行的取值、密钥密文不可被别的目标行解密、
 * 响应里没有任何密钥材料。
 */
@ExtendWith(MockitoExtension.class)
class AiWebhookTargetServiceImplTest {

    private static final Long TARGET_ID = 91L;

    private static final Long APPLICATION_ID = 7L;

    private static final String SECRET = "s3cret-signing-key-0123456789";

    @Mock
    private AiWebhookTargetMapper targetMapper;

    @Mock
    private AiApplicationService applicationService;

    private AiWebhookTargetServiceImpl service;

    @BeforeEach
    void setUp() {
        SecurityProperties properties = new SecurityProperties();
        properties.setCredentialEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        service = new AiWebhookTargetServiceImpl(targetMapper, applicationService, new CredentialCipher(properties));
    }

    @Test
    void createStoresEncryptedSecretAndNeverReturnsIt() {
        when(applicationService.getApplication(APPLICATION_ID)).thenReturn(new AiApplicationDO().setId(APPLICATION_ID));
        when(targetMapper.selectByCode(APPLICATION_ID, "erp-callback")).thenReturn(null);
        when(targetMapper.insert(any(AiWebhookTargetDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AiWebhookTargetDO.class).setId(TARGET_ID);
            return 1;
        });

        Long id = service.create(saveDTO());

        assertThat(id).isEqualTo(TARGET_ID);
        ArgumentCaptor<AiWebhookTargetDO> captor = ArgumentCaptor.forClass(AiWebhookTargetDO.class);
        verify(targetMapper).insert(captor.capture());
        AiWebhookTargetDO inserted = captor.getValue();
        assertThat(inserted.getStatus()).isEqualTo(AiWebhookTargetDO.STATUS_ENABLED);
        assertThat(inserted.getMaxAttempts()).isEqualTo(AiWebhookTargetDO.DEFAULT_MAX_ATTEMPTS);
        // 事件白名单已归一化为 JSON 数组文本，且写入行不含明文密钥
        assertThat(inserted.getEventTypes()).isEqualTo("[\"RUN.SUCCEEDED\",\"RUN.FAILED\"]");
        assertThat(inserted.toString()).doesNotContain(SECRET);
        // 密钥密文在插入后写入（AAD 绑行编号），明文只作为入参出现
        ArgumentCaptor<AiWebhookTargetDO> secretCaptor = ArgumentCaptor.forClass(AiWebhookTargetDO.class);
        verify(targetMapper).updateWithVersion(secretCaptor.capture(), eq(0));
        assertThat(secretCaptor.getValue().getSecretCiphertext())
                .startsWith("v1.")
                .doesNotContain(SECRET);
        assertThat(secretCaptor.getValue().getSecretRevision()).isEqualTo(1);
    }

    @Test
    void createRejectsInvalidCodeUrlEventsSecretAndAttempts() {
        when(applicationService.getApplication(APPLICATION_ID)).thenReturn(new AiApplicationDO().setId(APPLICATION_ID));

        AiWebhookTargetSaveDTO invalidCode = saveDTO().setCode("Bad Code");
        assertThatThrownBy(() -> service.create(invalidCode))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());

        AiWebhookTargetSaveDTO invalidUrl = saveDTO().setTargetUrl("ftp://erp.example.com/hook");
        assertThatThrownBy(() -> service.create(invalidUrl))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiWebhookErrorCode.URL_INVALID);

        AiWebhookTargetSaveDTO urlWithCredential = saveDTO().setTargetUrl("https://user:pass@erp.example.com/hook");
        assertThatThrownBy(() -> service.create(urlWithCredential))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiWebhookErrorCode.URL_INVALID);

        AiWebhookTargetSaveDTO blankEvents = saveDTO().setEventTypes(List.of());
        assertThatThrownBy(() -> service.create(blankEvents))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiWebhookErrorCode.EVENT_UNSUPPORTED);

        AiWebhookTargetSaveDTO unknownEvent = saveDTO().setEventTypes(List.of("RUN.RUNNING"));
        assertThatThrownBy(() -> service.create(unknownEvent))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiWebhookErrorCode.EVENT_UNSUPPORTED);

        AiWebhookTargetSaveDTO shortSecret = saveDTO().setSecret("short");
        assertThatThrownBy(() -> service.create(shortSecret))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());

        AiWebhookTargetSaveDTO tooManyAttempts = saveDTO().setMaxAttempts(11);
        assertThatThrownBy(() -> service.create(tooManyAttempts))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());

        AiWebhookTargetSaveDTO nullEvents = saveDTO().setEventTypes(null);
        assertThatThrownBy(() -> service.create(nullEvents))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiWebhookErrorCode.EVENT_UNSUPPORTED);
    }

    @Test
    void createRejectsDuplicateCodeInsideTheSameApplication() {
        when(applicationService.getApplication(APPLICATION_ID)).thenReturn(new AiApplicationDO().setId(APPLICATION_ID));
        when(targetMapper.selectByCode(APPLICATION_ID, "erp-callback"))
                .thenReturn(new AiWebhookTargetDO().setId(TARGET_ID));

        assertThatThrownBy(() -> service.create(saveDTO()))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());
        verify(targetMapper, never()).insert(any(AiWebhookTargetDO.class));
    }

    @Test
    void updateKeepsSecretWhenBlankAndRotatesWhenProvided() {
        AiWebhookTargetDO existing =
                existingTarget().setSecretCiphertext("v1.a.b").setSecretRevision(1);
        when(targetMapper.selectById(TARGET_ID)).thenReturn(existing);
        when(targetMapper.updateWithVersion(any(AiWebhookTargetDO.class), eq(3)))
                .thenReturn(1);

        service.update(saveDTO().setId(TARGET_ID).setSecret(null).setVersion(3));

        ArgumentCaptor<AiWebhookTargetDO> captor = ArgumentCaptor.forClass(AiWebhookTargetDO.class);
        verify(targetMapper).updateWithVersion(captor.capture(), eq(3));
        assertThat(captor.getValue().getSecretCiphertext()).isNull();
        assertThat(captor.getValue().getSecretRevision()).isNull();
        assertThat(captor.getValue().getMaxAttempts()).isEqualTo(3);

        when(targetMapper.updateWithVersion(any(AiWebhookTargetDO.class), eq(4)))
                .thenReturn(1);
        service.update(saveDTO().setId(TARGET_ID).setVersion(4));

        ArgumentCaptor<AiWebhookTargetDO> rotated = ArgumentCaptor.forClass(AiWebhookTargetDO.class);
        verify(targetMapper, org.mockito.Mockito.times(2)).updateWithVersion(rotated.capture(), anyInt());
        assertThat(rotated.getAllValues().get(1).getSecretRevision()).isEqualTo(2);
        assertThat(rotated.getAllValues().get(1).getSecretCiphertext()).startsWith("v1.");
    }

    @Test
    void updateRejectsCodeChangeAndStaleVersion() {
        when(targetMapper.selectById(TARGET_ID)).thenReturn(existingTarget());

        assertThatThrownBy(() -> service.update(
                        saveDTO().setId(TARGET_ID).setCode("other-code").setVersion(3)))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());

        when(targetMapper.updateWithVersion(any(AiWebhookTargetDO.class), eq(3)))
                .thenReturn(0);
        assertThatThrownBy(() -> service.update(saveDTO().setId(TARGET_ID).setVersion(3)))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());
    }

    @Test
    void rotateSecretRejectsWeakSecretAndIncrementsRevision() {
        when(targetMapper.selectById(TARGET_ID)).thenReturn(existingTarget().setSecretRevision(4));

        assertThatThrownBy(() -> service.rotateSecret(TARGET_ID, 3, "short"))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertThatThrownBy(() -> service.rotateSecret(TARGET_ID, null, SECRET))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());

        when(targetMapper.updateWithVersion(any(AiWebhookTargetDO.class), eq(3)))
                .thenReturn(1);
        service.rotateSecret(TARGET_ID, 3, SECRET);

        ArgumentCaptor<AiWebhookTargetDO> captor = ArgumentCaptor.forClass(AiWebhookTargetDO.class);
        verify(targetMapper).updateWithVersion(captor.capture(), eq(3));
        assertThat(captor.getValue().getSecretRevision()).isEqualTo(5);
        assertThat(captor.getValue().getSecretCiphertext()).doesNotContain(SECRET);
    }

    @Test
    void updateStatusDeleteAndGetFollowTheRecordedFacts() {
        when(targetMapper.selectById(TARGET_ID)).thenReturn(existingTarget());

        assertThatThrownBy(() -> service.updateStatus(TARGET_ID, 3, null))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());

        when(targetMapper.updateWithVersion(any(AiWebhookTargetDO.class), eq(3)))
                .thenReturn(1);
        service.updateStatus(TARGET_ID, 3, Boolean.FALSE);

        ArgumentCaptor<AiWebhookTargetDO> statusCaptor = ArgumentCaptor.forClass(AiWebhookTargetDO.class);
        verify(targetMapper).updateWithVersion(statusCaptor.capture(), eq(3));
        assertThat(statusCaptor.getValue().getStatus()).isEqualTo(AiWebhookTargetDO.STATUS_DISABLED);

        when(targetMapper.updateWithVersion(any(AiWebhookTargetDO.class), eq(4)))
                .thenReturn(1);
        service.delete(TARGET_ID, 4);
        verify(targetMapper).deleteById(TARGET_ID);

        assertThat(service.get(TARGET_ID).getId()).isEqualTo(TARGET_ID);
        when(targetMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.get(404L))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiErrorCodeConstants.AI_WEBHOOK_TARGET_NOT_FOUND.getCode());
    }

    @Test
    void decryptSecretRoundTripsOnlyForTheSameTargetRow() {
        AiWebhookTargetDO existing = existingTarget().setSecretRevision(1);
        when(targetMapper.selectById(TARGET_ID)).thenReturn(existing);
        // 直接把密文写进行：模拟 create 之后的读取
        String ciphertext = encrypted(SECRET, TARGET_ID);
        existing.setSecretCiphertext(ciphertext);

        assertThat(service.decryptSecret(TARGET_ID)).isEqualTo(SECRET);
        assertThat(service.decryptSecret(null)).isNull();
        when(targetMapper.selectById(404L)).thenReturn(null);
        assertThat(service.decryptSecret(404L)).isNull();
        // 密文不可解密（AAD 不匹配/损坏）时返回 null，既不放行也不抛出密钥材料
        existing.setSecretCiphertext(encrypted(SECRET, 999L));
        assertThat(service.decryptSecret(TARGET_ID)).isNull();
        existing.setSecretCiphertext(null);
        assertThat(service.decryptSecret(TARGET_ID)).isNull();
    }

    private String encrypted(String plaintext, Long targetId) {
        SecurityProperties properties = new SecurityProperties();
        properties.setCredentialEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        return new CredentialCipher(properties).encrypt(plaintext, "ai_webhook_target:" + targetId);
    }

    private static AiWebhookTargetDO existingTarget() {
        return new AiWebhookTargetDO()
                .setId(TARGET_ID)
                .setApplicationId(APPLICATION_ID)
                .setCode("erp-callback")
                .setName("ERP 回调")
                .setTargetUrl("https://erp.example.com/hook")
                .setEventTypes("[\"RUN.SUCCEEDED\"]")
                .setStatus(AiWebhookTargetDO.STATUS_ENABLED)
                .setMaxAttempts(3)
                .setVersion(3);
    }

    private static AiWebhookTargetSaveDTO saveDTO() {
        return new AiWebhookTargetSaveDTO()
                .setApplicationId(APPLICATION_ID)
                .setCode("erp-callback")
                .setName("ERP 回调")
                .setTargetUrl("https://erp.example.com/hook")
                .setEventTypes(List.of("RUN.SUCCEEDED", "RUN.FAILED"))
                .setSecret(SECRET);
    }

    /** 错误码断言用的常量别名：避免测试里散落魔法数字。 */
    private static final class AiWebhookErrorCode {
        private static final int URL_INVALID = AiErrorCodeConstants.AI_WEBHOOK_TARGET_URL_INVALID.getCode();
        private static final int EVENT_UNSUPPORTED = AiErrorCodeConstants.AI_WEBHOOK_EVENT_TYPE_UNSUPPORTED.getCode();
    }
}
