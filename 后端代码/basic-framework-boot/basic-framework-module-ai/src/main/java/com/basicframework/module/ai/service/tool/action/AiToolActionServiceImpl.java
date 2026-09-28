package com.basicframework.module.ai.service.tool.action;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_ACTION_ARGUMENTS_CHANGED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_ACTION_CHALLENGE_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_ACTION_IDEMPOTENCY_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_ACTION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_ACTION_NOT_PENDING;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_ACTION_RECONCILE_FAILED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_WRITE_POLICY_UNSUPPORTED;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import com.basicframework.module.ai.dal.dataobject.action.AiToolActionDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.dal.mysql.action.AiToolActionMapper;
import com.basicframework.module.ai.domain.tool.AiToolPolicy;
import com.basicframework.module.ai.service.tool.AiToolDecision;
import com.basicframework.module.ai.service.tool.AiToolExecutor;
import com.basicframework.module.ai.service.tool.AiToolPolicyGate;
import com.basicframework.module.ai.service.tool.AiToolService;
import com.basicframework.module.ai.service.tool.AiToolWriteBinding;
import com.basicframework.module.ai.service.tool.AiToolWriteGate;
import com.basicframework.module.ai.service.tool.action.dto.AiToolActionCreateResult;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 工具动作服务（D09 + X06）：确认状态机的落库与执行。
 *
 * <p>读工具的动作语义（D09 保持不变）：创建时冻结参数（哈希 + JSON）、生成一次性 challenge 与到期时间；
 * 确认要求主体 + 挑战 + 参数哈希 + 未过期 + 当前政策仍为 CONFIRM；执行只发生一次（CAS）。
 *
 * <p>写工具的增补（X06）：
 * <ul>
 *   <li><b>业务幂等键</b>：写动作登记业务幂等键（值取自冻结参数），同工具 + 同键在数据层唯一——
 *       重复提交只会复用同一条动作，<b>绝不产生第二次副作用</b>；已确定无副作用的终态不占键，
 *       失败后可以重新发起；</li>
 *   <li><b>结果未定</b>：执行先 CAS 消费确认（CONFIRMED → EXECUTING + attempt_epoch），
 *       上游超时/连接中断/5xx/响应不可用一律落 UNKNOWN，不做自动重放；</li>
 *   <li><b>显式核对</b>：只有结果未定（EXECUTING/UNKNOWN）的动作可以核对；程序核对调用动作登记的
 *       核对查询（按业务键查），人工核对记录操作员结论与说明；核对同样 CAS，concurrent 只有一个赢家。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AiToolActionServiceImpl implements AiToolActionService {

    /** 动作默认有效期（分钟）。 */
    private static final int DEFAULT_TTL_MINUTES = 15;

    /** 人工核对说明长度上限（与列宽一致；不含上游正文与凭据）。 */
    private static final int MAX_VERIFY_NOTE_LENGTH = 200;

    /** 核对结论：已生效的稳定原因码。 */
    private static final String REASON_RECONCILED_APPLIED = "reconciled-applied";

    /** 核对结论：未生效的稳定原因码。 */
    private static final String REASON_RECONCILED_NOT_APPLIED = "reconciled-not-applied";

    private final AiToolActionMapper actionMapper;

    private final AiToolPolicyGate policyGate;

    private final AiToolExecutor toolExecutor;

    /** 工具注册服务（D08）：读工具标识与当前已发布版本的政策。 */
    private final AiToolService toolService;

    /** 写工具准入闸门（X06）：发布期/执行期/核对期的绑定判定。 */
    private final AiToolWriteGate writeGate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiToolActionCreateResult createFromDecision(
            AiToolDecision decision, Long runId, Long applicationId, String subjectType, String externalUserId) {
        if (decision == null
                || decision.version() == null
                || runId == null
                || applicationId == null
                || !StringUtils.hasText(subjectType)) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiToolVersionDO version = decision.version();
        Map<String, Object> arguments = new LinkedHashMap<>(decision.arguments());
        boolean write = AiToolPolicy.ToolType.parse(version.getToolType()) == AiToolPolicy.ToolType.WRITE;
        AiToolWriteBinding binding = null;
        if (write) {
            if (AiToolPolicy.parse(version.getPolicy()) != AiToolPolicy.CONFIRM) {
                // 只有 CONFIRM 政策才有"待确认写动作"；AUTO 写版本在发布期就被拒绝
                throw exception(AI_TOOL_WRITE_POLICY_UNSUPPORTED);
            }
            binding = AiToolWriteBinding.parse(
                    version.getOutputSchemaJson(),
                    version.getToolType(),
                    version.getSourceRef(),
                    version.getInputSchemaJson());
        }
        String argumentsHash = AiToolActionStateMachine.argumentsHash(arguments);
        AiToolActionDO action = new AiToolActionDO()
                .setRunId(runId)
                .setToolId(version.getToolId())
                .setToolVersionId(version.getId())
                .setApplicationId(applicationId)
                .setSubjectType(subjectType)
                .setExternalUserId(externalUserId == null ? "" : externalUserId)
                .setPolicy(version.getPolicy())
                .setToolType(write ? AiToolActionDO.TOOL_TYPE_WRITE : AiToolActionDO.TOOL_TYPE_READ)
                .setArgumentsHash(argumentsHash)
                .setArgumentsJson(JsonUtils.toJsonString(arguments))
                .setChallenge(AiToolActionStateMachine.newChallenge())
                .setStatus(AiToolActionDO.STATUS_PENDING)
                .setAttemptEpoch(0)
                .setExpiresAt(LocalDateTime.now().plusMinutes(DEFAULT_TTL_MINUTES))
                .setVersion(0);
        if (!write) {
            // 读工具：每次确认都是独立动作（没有副作用，也就没有业务幂等键）
            actionMapper.insert(action);
            return AiToolActionCreateResult.created(action);
        }
        action.setIdempotencyParam(binding.idempotencyParam());
        action.setIdempotencyKey(binding.idempotencyKeyOf(arguments));
        action.setVerifySourceRef(binding.reconcileOperation());
        action.setVerifyParam(binding.reconcileParam());
        AiToolActionDO existing = actionMapper.selectByBusinessKey(version.getToolId(), action.getIdempotencyKey());
        if (existing != null && releaseIfExpiredBeforeExecution(existing, LocalDateTime.now())) {
            // 待确认/已确认但已过期：过期即无副作用，落成 EXPIRED 释放业务幂等键（可重新发起）
            existing = null;
        }
        if (existing != null) {
            // 同一业务意图已经在流程中或已执行：复用原动作，不产生第二个动作（也不会有第二次副作用）
            return AiToolActionCreateResult.reused(
                    requireSameIntentAndSubject(existing, argumentsHash, applicationId, subjectType, externalUserId));
        }
        try {
            actionMapper.insert(action);
        } catch (DuplicateKeyException concurrentSameKey) {
            // 并发同键：唯一键保证只有一个赢家；输家复用赢家的动作行
            AiToolActionDO winner = actionMapper.selectByBusinessKey(version.getToolId(), action.getIdempotencyKey());
            if (winner == null) {
                throw exception(AI_STATE_CONFLICT);
            }
            return AiToolActionCreateResult.reused(
                    requireSameIntentAndSubject(winner, argumentsHash, applicationId, subjectType, externalUserId));
        }
        return AiToolActionCreateResult.created(action);
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
        AiToolDO tool = toolService.getTool(action.getToolId());
        // 执行前再鉴权：注册表 + 当前政策（版本未发布/工具停用/政策变化都在这里被拒绝）
        AiToolVersionDO published = toolService.requirePublishedVersion(tool.getCode());
        AiToolActionStateMachine.requireExecutable(action, published.getPolicy(), LocalDateTime.now());
        boolean write = AiToolActionDO.TOOL_TYPE_WRITE.equals(action.getToolType());
        if (write) {
            // 写工具：冻结的绑定必须与当前已发布版本一致（绑定变化与改参数同等，须重新确认）
            writeGate.requireExecutableBinding(
                    tool, published, action.getIdempotencyParam(), action.getVerifySourceRef());
        }
        AiToolDecision decision = policyGate.decideAfterConfirmation(tool.getCode(), argumentsOf(action));
        // 消费确认：CAS CONFIRMED → EXECUTING（尝试代数 +1）。并发与重放只有一个赢家能真正执行
        int epoch = action.getAttemptEpoch() == null ? 0 : action.getAttemptEpoch();
        if (actionMapper.updateWithVersion(
                        new AiToolActionDO()
                                .setId(action.getId())
                                .setStatus(AiToolActionDO.STATUS_EXECUTING)
                                .setExecutedAt(LocalDateTime.now())
                                .setAttemptEpoch(epoch + 1)
                                .setVersion(action.getVersion() + 1),
                        action.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        int executingVersion = action.getVersion() + 1;
        AiToolWriteOutcome.Classification outcome;
        try {
            AiConnectorExecutionResultDTO result = write
                    ? toolExecutor.executeWrite(decision, action.getIdempotencyKey())
                    : toolExecutor.execute(decision);
            outcome = AiToolWriteOutcome.classifyResult(result);
        } catch (RuntimeException failure) {
            // 失败也保留"确认已被消费"的事实：写动作绝不因为异常而回到可执行状态
            outcome = AiToolWriteOutcome.classifyFailure(failure);
        }
        String status = finalStatus(write, outcome);
        if (actionMapper.updateWithVersion(
                        new AiToolActionDO()
                                .setId(action.getId())
                                .setStatus(status)
                                .setResultCode(bound(outcome.reasonCode(), 64))
                                .setVersion(executingVersion + 1),
                        executingVersion)
                == 0) {
            // 并发核对先一步收敛：结论以先写入的为准，不覆盖（本方法只返回当前事实）
            return actionMapper.selectById(action.getId());
        }
        return actionMapper.selectById(action.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiToolActionDO reconcile(
            Long actionId,
            Long applicationId,
            String subjectType,
            String externalUserId,
            String mode,
            String outcome,
            String note) {
        AiToolActionDO action = requireOwned(actionId, applicationId, subjectType, externalUserId);
        AiToolActionStateMachine.requireSameSubject(action, applicationId, subjectType, externalUserId);
        // 只有"结果未定"的动作需要核对（EXECUTING=执行中/进程崩溃，UNKNOWN=上游未确认）
        AiToolActionStateMachine.requireReconcilable(action);
        String verifiedBy = normaliseMode(mode);
        String verifyResult;
        String evidence;
        if (AiToolActionDO.VERIFIED_BY_PROGRAM.equals(verifiedBy)) {
            if (!AiToolActionDO.TOOL_TYPE_WRITE.equals(action.getToolType())
                    || !StringUtils.hasText(action.getVerifySourceRef())
                    || !StringUtils.hasText(action.getVerifyParam())
                    || !StringUtils.hasText(action.getIdempotencyKey())) {
                // 没有登记核对查询的动作（读动作/旧动作）只能人工核对
                throw exception(AI_TOOL_ACTION_NOT_PENDING);
            }
            AiToolDO tool = toolService.getTool(action.getToolId());
            writeGate.requireReconcileKeyed(
                    tool.getConnectorId(), action.getVerifySourceRef(), action.getVerifyParam());
            Map<String, Object> queryArguments = Map.of(action.getVerifyParam(), action.getIdempotencyKey());
            AiConnectorExecutionResultDTO queried =
                    toolExecutor.executeReconcile(tool.getConnectorId(), action.getVerifySourceRef(), queryArguments);
            if (queried == null || !java.util.Set.of("COMPLETE", "PARTIAL").contains(queried.getStatus())) {
                // 核对查询本身不可用：动作保持"结果未定"，绝不猜结论（可稍后重试核对或转人工）
                throw exception(AI_TOOL_ACTION_RECONCILE_FAILED);
            }
            int items = queried.getItemCount();
            verifyResult = items > 0 ? AiToolActionDO.VERIFY_APPLIED : AiToolActionDO.VERIFY_NOT_APPLIED;
            evidence = bound(action.getVerifySourceRef() + ":items=" + items, MAX_VERIFY_NOTE_LENGTH);
        } else {
            verifyResult = normaliseOutcome(outcome);
            evidence = bound(requireManualNote(note), MAX_VERIFY_NOTE_LENGTH);
        }
        String status = AiToolActionDO.VERIFY_APPLIED.equals(verifyResult)
                ? AiToolActionDO.STATUS_EXECUTED
                : AiToolActionDO.STATUS_FAILED;
        if (actionMapper.updateWithVersion(
                        new AiToolActionDO()
                                .setId(action.getId())
                                .setStatus(status)
                                .setResultCode(
                                        AiToolActionDO.VERIFY_APPLIED.equals(verifyResult)
                                                ? REASON_RECONCILED_APPLIED
                                                : REASON_RECONCILED_NOT_APPLIED)
                                .setVerifiedAt(LocalDateTime.now())
                                .setVerifiedBy(verifiedBy)
                                .setVerifyResult(verifyResult)
                                .setVerifyEvidence(evidence)
                                .setVersion(action.getVersion() + 1),
                        action.getVersion())
                == 0) {
            // 并发核对只有一个赢家
            throw exception(AI_STATE_CONFLICT);
        }
        return actionMapper.selectById(action.getId());
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

    /**
     * 过期且**尚未执行**（PENDING/CONFIRMED）的动作不占业务幂等键：落成 EXPIRED 终态后允许重新发起。
     *
     * <p>为什么只有这两个状态可以释放：它们表示"确认尚未被消费"，过期后既不能确认也不能执行，
     * 因此确定没有副作用；而 EXECUTING/UNKNOWN/EXECUTED 表示确认已被消费（写可能已生效），
     * 它们必须永远占住业务键——否则"重新发起"就成了第二次副作用的通道。
     *
     * @return 是否已把该动作落成 EXPIRED（CAS 失败说明状态被并发推进，此时保守地当作仍占键）
     */
    private boolean releaseIfExpiredBeforeExecution(AiToolActionDO action, LocalDateTime now) {
        boolean beforeExecution = AiToolActionDO.STATUS_PENDING.equals(action.getStatus())
                || AiToolActionDO.STATUS_CONFIRMED.equals(action.getStatus());
        if (!beforeExecution || !AiToolActionStateMachine.expired(action, now)) {
            return false;
        }
        return actionMapper.updateWithVersion(
                        new AiToolActionDO()
                                .setId(action.getId())
                                .setStatus(AiToolActionDO.STATUS_EXPIRED)
                                .setVersion(action.getVersion() + 1),
                        action.getVersion())
                == 1;
    }

    /** 写动作的结果未定只能靠核对收敛；读动作没有副作用，未定即失败（可以重新发起）。 */
    private static String finalStatus(boolean write, AiToolWriteOutcome.Classification outcome) {
        if (outcome.verdict() == AiToolWriteOutcome.Verdict.APPLIED) {
            return AiToolActionDO.STATUS_EXECUTED;
        }
        if (write && !outcome.settled()) {
            return AiToolActionDO.STATUS_UNKNOWN;
        }
        return AiToolActionDO.STATUS_FAILED;
    }

    /** 复用同键动作：必须是"同一主体 + 同一参数摘要"，否则是同键不同意图（409，不泄漏他人动作内容）。 */
    private AiToolActionDO requireSameIntentAndSubject(
            AiToolActionDO existing,
            String argumentsHash,
            Long applicationId,
            String subjectType,
            String externalUserId) {
        String normalizedUser = externalUserId == null ? "" : externalUserId;
        if (!applicationId.equals(existing.getApplicationId())
                || !subjectType.equals(existing.getSubjectType())
                || !normalizedUser.equals(existing.getExternalUserId())
                || !argumentsHash.equals(existing.getArgumentsHash())) {
            throw exception(AI_TOOL_ACTION_IDEMPOTENCY_CONFLICT);
        }
        return existing;
    }

    private static String normaliseMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            throw exception(AI_REQUEST_INVALID);
        }
        String normalized = mode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!AiToolActionDO.VERIFIED_BY_PROGRAM.equals(normalized)
                && !AiToolActionDO.VERIFIED_BY_MANUAL.equals(normalized)) {
            throw exception(AI_REQUEST_INVALID);
        }
        return normalized;
    }

    private static String normaliseOutcome(String outcome) {
        if (!StringUtils.hasText(outcome)) {
            throw exception(AI_REQUEST_INVALID);
        }
        String normalized = outcome.trim().toUpperCase(java.util.Locale.ROOT);
        if (!AiToolActionDO.VERIFY_APPLIED.equals(normalized)
                && !AiToolActionDO.VERIFY_NOT_APPLIED.equals(normalized)) {
            throw exception(AI_REQUEST_INVALID);
        }
        return normalized;
    }

    /** 人工核对说明：可空，但不接受超长与控制字符（核对证据列宽 200，且不允许夹带正文/凭据形态）。 */
    private static String requireManualNote(String note) {
        if (note == null || note.isBlank()) {
            return "manual";
        }
        if (note.length() > MAX_VERIFY_NOTE_LENGTH || note.chars().anyMatch(Character::isISOControl)) {
            throw exception(AI_REQUEST_INVALID);
        }
        return note.trim();
    }

    private static String bound(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
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
