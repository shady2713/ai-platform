package com.basicframework.module.ai.service.tool.action;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_ACTION_ARGUMENTS_CHANGED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_ACTION_CHALLENGE_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_ACTION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_ACTION_NOT_PENDING;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import com.basicframework.module.ai.dal.dataobject.action.AiToolActionDO;
import com.basicframework.module.ai.dal.mysql.action.AiToolActionMapper;
import com.basicframework.module.ai.service.tool.AiToolDecision;
import com.basicframework.module.ai.service.tool.AiToolExecutor;
import com.basicframework.module.ai.service.tool.AiToolPolicyGate;
import com.basicframework.module.ai.service.tool.AiToolService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 工具动作服务（D09）：确认状态机的落库与执行。
 *
 * <p>要点：
 * <ul>
 *   <li>创建：由 CONFIRM 判定产生，参数被冻结（哈希 + JSON）、生成一次性 challenge 与到期时间；</li>
 *   <li>确认：主体 + 挑战 + 参数哈希 + 未过期 + 当前政策仍为 CONFIRM，全部通过后用 CAS 置 CONFIRMED；</li>
 *   <li>执行：只有 CONFIRMED 能执行，且 CAS 置 EXECUTED（重复执行被挡，不产生第二次副作用）；</li>
 *   <li>查询：按主体过滤，越权与不存在同语义。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AiToolActionServiceImpl implements AiToolActionService {

    /** 动作默认有效期（分钟）。 */
    private static final int DEFAULT_TTL_MINUTES = 15;

    private final AiToolActionMapper actionMapper;

    private final AiToolPolicyGate policyGate;

    private final AiToolExecutor toolExecutor;

    /** 工具注册服务（D08）：读工具标识与当前已发布版本的政策。 */
    private final AiToolService toolService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiToolActionDO createFromDecision(
            AiToolDecision decision, Long runId, Long applicationId, String subjectType, String externalUserId) {
        if (decision == null
                || decision.version() == null
                || runId == null
                || applicationId == null
                || !StringUtils.hasText(subjectType)) {
            throw exception(AI_REQUEST_INVALID);
        }
        Map<String, Object> arguments = new LinkedHashMap<>(decision.arguments());
        AiToolActionDO action = new AiToolActionDO()
                .setRunId(runId)
                .setToolId(decision.version().getToolId())
                .setToolVersionId(decision.version().getId())
                .setApplicationId(applicationId)
                .setSubjectType(subjectType)
                .setExternalUserId(externalUserId == null ? "" : externalUserId)
                .setPolicy(decision.version().getPolicy())
                .setArgumentsHash(AiToolActionStateMachine.argumentsHash(arguments))
                .setArgumentsJson(JsonUtils.toJsonString(arguments))
                .setChallenge(AiToolActionStateMachine.newChallenge())
                .setStatus(AiToolActionDO.STATUS_PENDING)
                .setExpiresAt(LocalDateTime.now().plusMinutes(DEFAULT_TTL_MINUTES))
                .setVersion(0);
        actionMapper.insert(action);
        return action;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiToolActionDO confirm(
            Long actionId,
            Long applicationId,
            String subjectType,
            String externalUserId,
            String challenge,
            Map<String, Object> arguments) {
        AiToolActionDO action = requireOwned(actionId, applicationId, subjectType, externalUserId);
        String argumentsHash = AiToolActionStateMachine.argumentsHash(arguments);
        AiToolActionStateMachine.requireConfirmable(
                action, applicationId, subjectType, externalUserId, challenge, argumentsHash, LocalDateTime.now());
        // 当前政策必须仍是 CONFIRM：政策被改成 DENY 后旧确认不能继续
        requirePolicyStillConfirm(action);
        if (actionMapper.updateWithVersion(
                        new AiToolActionDO()
                                .setId(action.getId())
                                .setStatus(AiToolActionDO.STATUS_CONFIRMED)
                                .setDecidedAt(LocalDateTime.now())
                                .setVersion(action.getVersion() + 1),
                        action.getVersion())
                == 0) {
            // 并发确认只有一个赢家
            throw exception(AI_STATE_CONFLICT);
        }
        return actionMapper.selectById(action.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiToolActionDO reject(
            Long actionId, Long applicationId, String subjectType, String externalUserId, String challenge) {
        AiToolActionDO action = requireOwned(actionId, applicationId, subjectType, externalUserId);
        AiToolActionStateMachine.requireSameSubject(action, applicationId, subjectType, externalUserId);
        if (!challenge.equals(action.getChallenge())) {
            throw exception(AI_TOOL_ACTION_CHALLENGE_INVALID);
        }
        if (!AiToolActionDO.STATUS_PENDING.equals(action.getStatus())) {
            throw exception(AI_TOOL_ACTION_NOT_PENDING);
        }
        if (actionMapper.updateWithVersion(
                        new AiToolActionDO()
                                .setId(action.getId())
                                .setStatus(AiToolActionDO.STATUS_CANCELLED)
                                .setDecidedAt(LocalDateTime.now())
                                .setVersion(action.getVersion() + 1),
                        action.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        return actionMapper.selectById(action.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiToolActionDO execute(Long actionId, Long applicationId, String subjectType, String externalUserId) {
        AiToolActionDO action = requireOwned(actionId, applicationId, subjectType, externalUserId);
        AiToolActionStateMachine.requireSameSubject(action, applicationId, subjectType, externalUserId);
        String currentPolicy = currentPolicy(action);
        AiToolActionStateMachine.requireExecutable(action, currentPolicy, LocalDateTime.now());
        // 先 CAS 置 EXECUTED（占位），确保"重放/并发执行"只有一个赢家拿到执行权
        if (actionMapper.updateWithVersion(
                        new AiToolActionDO()
                                .setId(action.getId())
                                .setStatus(AiToolActionDO.STATUS_EXECUTED)
                                .setExecutedAt(LocalDateTime.now())
                                .setVersion(action.getVersion() + 1),
                        action.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        AiToolDecision decision = policyGate.decideAfterConfirmation(toolCodeOf(action), argumentsOf(action));
        // 上面已把动作置为 EXECUTED（版本 +1）；这里只补写结论，保持版本推进一致
        int executedVersion = action.getVersion() + 1;
        try {
            AiConnectorExecutionResultDTO result = toolExecutor.execute(decision);
            boolean upstreamFailed = result != null && "FAILED".equals(result.getStatus());
            String resultCode = upstreamFailed
                    ? (result.getDetailCode() == null ? "upstream-failed" : result.getDetailCode())
                    : (result == null ? null : result.getStatus());
            actionMapper.updateWithVersion(
                    new AiToolActionDO()
                            .setId(action.getId())
                            .setStatus(upstreamFailed ? AiToolActionDO.STATUS_FAILED : AiToolActionDO.STATUS_EXECUTED)
                            .setResultCode(resultCode)
                            .setVersion(executedVersion + 1),
                    executedVersion);
            return actionMapper.selectById(action.getId());
        } catch (com.basicframework.framework.common.exception.ServiceException failure) {
            // 执行失败也是终态：记录稳定原因码，不回滚"已执行过"的事实（避免重放产生第二次副作用）
            actionMapper.updateWithVersion(
                    new AiToolActionDO()
                            .setId(action.getId())
                            .setStatus(AiToolActionDO.STATUS_FAILED)
                            .setResultCode(String.valueOf(failure.getCode()))
                            .setVersion(executedVersion + 1),
                    executedVersion);
            throw failure;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiToolActionDO expire(Long actionId) {
        AiToolActionDO action = requireAction(actionId);
        if (!AiToolActionDO.STATUS_PENDING.equals(action.getStatus())) {
            throw exception(AI_TOOL_ACTION_NOT_PENDING);
        }
        if (actionMapper.updateWithVersion(
                        new AiToolActionDO()
                                .setId(action.getId())
                                .setStatus(AiToolActionDO.STATUS_EXPIRED)
                                .setVersion(action.getVersion() + 1),
                        action.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        return actionMapper.selectById(action.getId());
    }

    @Override
    public AiToolActionDO getAction(Long actionId, Long applicationId, String subjectType, String externalUserId) {
        return requireOwned(actionId, applicationId, subjectType, externalUserId);
    }

    @Override
    public PageResult<AiToolActionDO> getActionPage(
            Long applicationId, String subjectType, String externalUserId, Long runId, PageParam pageParam) {
        if (pageParam == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        return actionMapper.selectPage(pageParam, applicationId, subjectType, externalUserId, runId);
    }

    /** 越权与不存在同语义：不属于当前主体的动作按不存在处理。 */
    private AiToolActionDO requireOwned(Long actionId, Long applicationId, String subjectType, String externalUserId) {
        AiToolActionDO action = requireAction(actionId);
        AiToolActionStateMachine.requireSameSubject(action, applicationId, subjectType, externalUserId);
        return action;
    }

    private AiToolActionDO requireAction(Long actionId) {
        AiToolActionDO action = actionId == null ? null : actionMapper.selectById(actionId);
        if (action == null) {
            throw exception(AI_TOOL_ACTION_NOT_FOUND);
        }
        return action;
    }

    private void requirePolicyStillConfirm(AiToolActionDO action) {
        if (!"CONFIRM".equals(currentPolicy(action))) {
            throw exception(AI_TOOL_ACTION_NOT_PENDING);
        }
    }

    /** 当前政策：从工具当前已发布版本读取（政策变化必须反映到确认判定）。 */
    private String currentPolicy(AiToolActionDO action) {
        return toolService.requirePublishedVersion(toolCodeOf(action)).getPolicy();
    }

    private String toolCodeOf(AiToolActionDO action) {
        return toolService.getTool(action.getToolId()).getCode();
    }

    private Map<String, Object> argumentsOf(AiToolActionDO action) {
        Map<?, ?> parsed;
        try {
            parsed = JsonUtils.parseObject(action.getArgumentsJson(), Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_TOOL_ACTION_ARGUMENTS_CHANGED);
        }
        if (parsed == null) {
            throw exception(AI_TOOL_ACTION_ARGUMENTS_CHANGED);
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        parsed.forEach((key, value) -> arguments.put(String.valueOf(key), value));
        return arguments;
    }
}
