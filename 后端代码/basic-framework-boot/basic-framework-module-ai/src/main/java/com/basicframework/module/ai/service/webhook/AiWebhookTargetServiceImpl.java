package com.basicframework.module.ai.service.webhook;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WEBHOOK_EVENT_TYPE_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WEBHOOK_TARGET_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WEBHOOK_TARGET_URL_INVALID;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookTargetDO;
import com.basicframework.module.ai.dal.mysql.webhook.AiWebhookTargetMapper;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.webhook.dto.AiWebhookTargetSaveDTO;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Webhook 目标实现（X10）：写入前把"能不能投"收窄到可判定的形状，读取永不回显密钥。
 *
 * <p>地址校验只做**形状**收窄（绝对 http/https、有主机、无 URL 凭据信息、长度有界）：
 * 目标是否真的可出站由受控出站边界在发送前判定（允许清单 + 私网策略），
 * 登记期不把"允许清单里有没有"复制成第二份真值——两份真值必然漂移。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiWebhookTargetServiceImpl implements AiWebhookTargetService {

    /** 目标标识：小写字母数字与连字符，3-64 位（与列宽一致）。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{2,63}$");

    /** 标识与名称长度上限。 */
    private static final int MAX_NAME_LENGTH = 128;

    /** 地址长度上限（与列宽一致，避免截断）。 */
    private static final int MAX_URL_LENGTH = 1024;

    /** 尝试次数上限（有界重试：下限 1、上限 10）。 */
    private static final int MAX_ATTEMPTS_LIMIT = 10;

    /** 密钥 AAD 前缀：与目标编号组合，密文不可挪到别的目标行。 */
    private static final String CREDENTIAL_CONTEXT_PREFIX = "ai_webhook_target:";

    private final AiWebhookTargetMapper targetMapper;

    private final AiApplicationService applicationService;

    private final CredentialCipher credentialCipher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AiWebhookTargetSaveDTO saveDTO) {
        requireSave(saveDTO, true);
        AiWebhookTargetDO existing = targetMapper.selectByCode(saveDTO.getApplicationId(), saveDTO.getCode());
        if (existing != null) {
            throw exception(AI_STATE_CONFLICT, "该应用下已存在同名 Webhook 目标标识");
        }
        AiWebhookTargetDO target = new AiWebhookTargetDO()
                .setApplicationId(saveDTO.getApplicationId())
                .setCode(saveDTO.getCode())
                .setName(saveDTO.getName().trim())
                .setTargetUrl(saveDTO.getTargetUrl().trim())
                .setEventTypes(AiWebhookEventTypes.serialize(saveDTO.getEventTypes()))
                .setStatus(AiWebhookTargetDO.STATUS_ENABLED)
                .setMaxAttempts(effectiveMaxAttempts(saveDTO.getMaxAttempts()))
                .setSecretRevision(0)
                .setVersion(0);
        targetMapper.insert(target);
        // 密钥密文需要目标编号作为 AAD：插入后再写入（与模型端点/连接器同一约定）
        applySecret(target, saveDTO.getSecret(), 1);
        return target.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(AiWebhookTargetSaveDTO saveDTO) {
        requireSave(saveDTO, false);
        AiWebhookTargetDO existing = requireTarget(saveDTO.getId());
        if (StringUtils.hasText(saveDTO.getCode()) && !existing.getCode().equals(saveDTO.getCode())) {
            // 标识是接收方与运维的稳定键：创建后不可修改（改标识请新建目标）
            throw exception(AI_STATE_CONFLICT, "Webhook 目标标识创建后不可修改");
        }
        String eventTypes = AiWebhookEventTypes.serialize(saveDTO.getEventTypes());
        AiWebhookTargetDO update = new AiWebhookTargetDO()
                .setId(existing.getId())
                .setName(saveDTO.getName().trim())
                .setTargetUrl(saveDTO.getTargetUrl().trim())
                .setEventTypes(eventTypes)
                .setMaxAttempts(effectiveMaxAttempts(saveDTO.getMaxAttempts()))
                .setVersion(saveDTO.getVersion() + 1);
        if (backfillsHistory(existing.getEventTypes(), eventTypes)) {
            // 订阅范围扩大：新订阅的事件类型在**历史**终态运行上同样要补齐（补漏语义不因水位而变窄）。
            // 水位回到起点后由补漏扫描按每轮有界的一段重新覆盖，而不是让历史事件永远沉默。
            update.setEnqueueWatermark(AiWebhookTargetDO.ENQUEUE_WATERMARK_EPOCH);
        }
        // 修改携带密钥时才轮换：留空表示保留已有密钥
        if (StringUtils.hasText(saveDTO.getSecret())) {
            requireAcceptableSecret(saveDTO.getSecret());
            update.setSecretCiphertext(
                            credentialCipher.encrypt(saveDTO.getSecret(), credentialContext(existing.getId())))
                    .setSecretRevision(currentRevision(existing) + 1);
        }
        if (targetMapper.updateWithVersion(update, saveDTO.getVersion()) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rotateSecret(Long id, Integer version, String secret) {
        requireVersion(version);
        requireAcceptableSecret(secret);
        AiWebhookTargetDO existing = requireTarget(id);
        if (targetMapper.updateWithVersion(
                        new AiWebhookTargetDO()
                                .setId(id)
                                .setSecretCiphertext(credentialCipher.encrypt(secret, credentialContext(id)))
                                .setSecretRevision(currentRevision(existing) + 1)
                                .setVersion(version + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer version, Boolean enabled) {
        requireVersion(version);
        if (enabled == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        requireTarget(id);
        if (targetMapper.updateWithVersion(
                        new AiWebhookTargetDO()
                                .setId(id)
                                .setStatus(
                                        enabled ? AiWebhookTargetDO.STATUS_ENABLED : AiWebhookTargetDO.STATUS_DISABLED)
                                .setVersion(version + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Integer version) {
        requireVersion(version);
        requireTarget(id);
        if (targetMapper.updateWithVersion(new AiWebhookTargetDO().setId(id).setVersion(version + 1), version) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        targetMapper.deleteById(id);
    }

    @Override
    public AiWebhookTargetDO get(Long id) {
        return requireTarget(id);
    }

    @Override
    public PageResult<AiWebhookTargetDO> getPage(PageParam pageParam, Long applicationId, String code, String status) {
        return targetMapper.selectPage(pageParam, applicationId, code, status);
    }

    @Override
    public String decryptSecret(Long targetId) {
        AiWebhookTargetDO target = targetId == null ? null : targetMapper.selectById(targetId);
        if (target == null || !StringUtils.hasText(target.getSecretCiphertext())) {
            return null;
        }
        try {
            return credentialCipher.decrypt(target.getSecretCiphertext(), credentialContext(target.getId()));
        } catch (RuntimeException exception) {
            // 解密失败按"密钥不可用"处理：不把密文/异常正文带出去，也不假装能签名
            log.warn("Webhook 目标 {} 的签名密钥不可用（原因已脱敏）", targetId);
            return null;
        }
    }

    @Override
    public List<AiWebhookTargetDO> listEnabled() {
        return targetMapper.selectEnabled();
    }

    @Override
    public void advanceEnqueueWatermark(Long targetId, LocalDateTime watermark) {
        if (targetId == null || watermark == null) {
            return;
        }
        targetMapper.advanceEnqueueWatermark(targetId, watermark);
    }

    private void applySecret(AiWebhookTargetDO target, String secret, int revision) {
        requireAcceptableSecret(secret);
        targetMapper.updateWithVersion(
                new AiWebhookTargetDO()
                        .setId(target.getId())
                        .setSecretCiphertext(credentialCipher.encrypt(secret, credentialContext(target.getId())))
                        .setSecretRevision(revision)
                        .setVersion(1),
                0);
    }

    /** 写入前置校验：形状与白名单都收窄，避免把"投不出去"的目标登记成启用状态。 */
    private void requireSave(AiWebhookTargetSaveDTO saveDTO, boolean create) {
        if (saveDTO == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (create) {
            applicationService.getApplication(saveDTO.getApplicationId());
            if (!StringUtils.hasText(saveDTO.getCode())
                    || !CODE_PATTERN.matcher(saveDTO.getCode()).matches()) {
                throw exception(AI_REQUEST_INVALID);
            }
        } else {
            requireVersion(saveDTO.getVersion());
        }
        if (!StringUtils.hasText(saveDTO.getName()) || saveDTO.getName().trim().length() > MAX_NAME_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
        requireTargetUrl(saveDTO.getTargetUrl());
        requireEventTypes(saveDTO.getEventTypes());
        effectiveMaxAttempts(saveDTO.getMaxAttempts());
    }

    /** 地址只做形状收窄：真正的出站许可由受控边界判定。 */
    private void requireTargetUrl(String targetUrl) {
        if (!StringUtils.hasText(targetUrl) || targetUrl.trim().length() > MAX_URL_LENGTH) {
            throw exception(AI_WEBHOOK_TARGET_URL_INVALID);
        }
        String value = targetUrl.trim();
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw exception(AI_WEBHOOK_TARGET_URL_INVALID);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!("https".equals(scheme) || "http".equals(scheme))
                || !StringUtils.hasText(uri.getHost())
                // URL 里带凭据信息会把密钥写进日志与接收端访问日志：一律拒绝
                || uri.getUserInfo() != null) {
            throw exception(AI_WEBHOOK_TARGET_URL_INVALID);
        }
    }

    private void requireEventTypes(List<String> eventTypes) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            throw exception(AI_WEBHOOK_EVENT_TYPE_UNSUPPORTED);
        }
        for (String eventType : eventTypes) {
            if (!AiWebhookEventTypes.isSupported(eventType)) {
                throw exception(AI_WEBHOOK_EVENT_TYPE_UNSUPPORTED);
            }
        }
    }

    private static int effectiveMaxAttempts(Integer maxAttempts) {
        int value = maxAttempts == null ? AiWebhookTargetDO.DEFAULT_MAX_ATTEMPTS : maxAttempts;
        if (value < 1 || value > MAX_ATTEMPTS_LIMIT) {
            throw exception(AI_REQUEST_INVALID);
        }
        return value;
    }

    private static void requireAcceptableSecret(String secret) {
        if (!AiWebhookSignature.isAcceptableSecret(secret)) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private static void requireVersion(Integer version) {
        if (version == null || version < 0) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private static int currentRevision(AiWebhookTargetDO target) {
        return target.getSecretRevision() == null ? 0 : target.getSecretRevision();
    }

    /** 新白名单是否包含旧白名单之外的事件（扩大订阅 → 需要让补漏扫描重新覆盖历史终态运行）。 */
    private static boolean backfillsHistory(String previousJson, String updatedJson) {
        List<String> previous = AiWebhookEventTypes.parse(previousJson);
        return !previous.containsAll(AiWebhookEventTypes.parse(updatedJson));
    }

    private static String credentialContext(Long targetId) {
        return CREDENTIAL_CONTEXT_PREFIX + targetId;
    }

    private AiWebhookTargetDO requireTarget(Long id) {
        AiWebhookTargetDO target = id == null ? null : targetMapper.selectById(id);
        if (target == null) {
            throw exception(AI_WEBHOOK_TARGET_NOT_FOUND);
        }
        return target;
    }
}
