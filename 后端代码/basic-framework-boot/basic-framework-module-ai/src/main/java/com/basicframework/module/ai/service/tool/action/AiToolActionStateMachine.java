package com.basicframework.module.ai.service.tool.action;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_ACTION_ARGUMENTS_CHANGED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_ACTION_CHALLENGE_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_ACTION_EXPIRED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_ACTION_NOT_PENDING;

import com.basicframework.module.ai.dal.dataobject.action.AiToolActionDO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.util.StringUtils;

/**
 * 确认状态机（D09）：PENDING → CONFIRMED → EXECUTED（或 CANCELLED/EXPIRED/FAILED）。
 *
 * <p>三条守卫（对应 AT-020/021）：
 * <ol>
 *   <li><b>同一主体</b>：确认者必须是动作发起主体（换用户不执行）；</li>
 *   <li><b>同一参数</b>：确认必须携带与创建时一致的参数哈希（改参数即拒绝，要求重新确认）；</li>
 *   <li><b>同一挑战且未过期</b>：challenge 与动作绑定、一次性；过期动作不可确认也不可执行。</li>
 * </ol>
 *
 * <p>状态转移全部通过 CAS（乐观锁）完成：重复确认与重复执行只会有一个赢家，
 * 因此"重放确认"不会产生第二次副作用。
 */
public final class AiToolActionStateMachine {

    /** 确认挑战长度（hex）。 */
    public static final int CHALLENGE_LENGTH = 32;

    private AiToolActionStateMachine() {}

    /** 生成一次性确认挑战。 */
    public static String newChallenge() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, CHALLENGE_LENGTH);
    }

    /** 参数规范化哈希（键排序后的 JSON 文本；与执行时的参数一一对应）。 */
    public static String argumentsHash(java.util.Map<String, Object> arguments) {
        java.util.Map<String, Object> sorted =
                new java.util.TreeMap<>(arguments == null ? java.util.Map.of() : arguments);
        String canonical = com.basicframework.framework.common.util.json.JsonUtils.toJsonString(sorted);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    /**
     * 校验确认请求（不改状态）：主体、挑战、参数哈希、状态与过期时间。
     *
     * <p>调用方在通过校验后必须用 CAS 完成转移，不能"校验通过就直接执行"。
     */
    public static void requireConfirmable(
            AiToolActionDO action,
            Long applicationId,
            String subjectType,
            String externalUserId,
            String challenge,
            String argumentsHash,
            LocalDateTime now) {
        if (action == null) {
            throw exception(AI_TOOL_ACTION_NOT_PENDING);
        }
        requireSameSubject(action, applicationId, subjectType, externalUserId);
        if (!StringUtils.hasText(challenge) || !challenge.equals(action.getChallenge())) {
            // 挑战不符：可能是伪造请求，也可能是别人的动作
            throw exception(AI_TOOL_ACTION_CHALLENGE_INVALID);
        }
        if (expired(action, now)) {
            throw exception(AI_TOOL_ACTION_EXPIRED);
        }
        if (!AiToolActionDO.STATUS_PENDING.equals(action.getStatus())) {
            throw exception(AI_TOOL_ACTION_NOT_PENDING);
        }
        if (!StringUtils.hasText(argumentsHash) || !argumentsHash.equals(action.getArgumentsHash())) {
            // 参数在确认请求里被改过：拒绝并要求重新发起（AT-020）
            throw exception(AI_TOOL_ACTION_ARGUMENTS_CHANGED);
        }
    }

    /** 同一主体守卫（应用 + 主体类型 + 外部用户标识；越权与不存在同语义由调用方保证）。 */
    public static void requireSameSubject(
            AiToolActionDO action, Long applicationId, String subjectType, String externalUserId) {
        String normalizedUser = externalUserId == null ? "" : externalUserId;
        if (action.getApplicationId() == null
                || applicationId == null
                || !action.getApplicationId().equals(applicationId)
                || !StringUtils.hasText(action.getSubjectType())
                || !action.getSubjectType().equals(subjectType)
                || !normalizedUser.equals(action.getExternalUserId())) {
            throw exception(AI_TOOL_ACTION_CHALLENGE_INVALID);
        }
    }

    /** 是否已过期（到期即不可确认/执行）。 */
    public static boolean expired(AiToolActionDO action, LocalDateTime now) {
        return action.getExpiresAt() != null && now != null && !now.isBefore(action.getExpiresAt());
    }

    /** 执行前守卫：只有 CONFIRMED 且未过期、且政策仍为 CONFIRM 才可执行一次。 */
    public static void requireExecutable(AiToolActionDO action, String currentPolicy, LocalDateTime now) {
        if (action == null || !AiToolActionDO.STATUS_CONFIRMED.equals(action.getStatus())) {
            throw exception(AI_TOOL_ACTION_NOT_PENDING);
        }
        if (expired(action, now)) {
            throw exception(AI_TOOL_ACTION_EXPIRED);
        }
        if (!"CONFIRM".equals(currentPolicy)) {
            // 政策在确认后发生变化：按当前政策处理（不再允许沿用旧确认）
            throw exception(AI_TOOL_ACTION_NOT_PENDING);
        }
    }
}
