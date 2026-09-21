package com.basicframework.module.ai.service.tool.action;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_ANALYSIS_STEP_LIMIT_EXCEEDED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RUN_NOT_ACTIVE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_CONFIRMATION_REQUIRED;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import com.basicframework.module.ai.dal.dataobject.action.AiToolActionDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.dal.mysql.action.AiRunStepCounterMapper;
import com.basicframework.module.ai.dal.mysql.tool.AiToolMapper;
import com.basicframework.module.ai.domain.runtime.AiRunBudget;
import com.basicframework.module.ai.domain.tool.AiToolPolicy;
import com.basicframework.module.ai.service.tool.AiToolDecision;
import com.basicframework.module.ai.service.tool.AiToolExecutor;
import com.basicframework.module.ai.service.tool.AiToolPolicyGate;
import com.basicframework.module.ai.service.tool.AiToolService;
import com.basicframework.module.ai.service.tool.AiToolServiceImpl;
import com.basicframework.module.ai.service.tool.action.dto.AiAnalysisStepRequestDTO;
import com.basicframework.module.ai.service.tool.action.dto.AiAnalysisStepResultDTO;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 分析步骤调度（D09）：把"查询/工具"步骤接进运行，并限制次数与预算。
 *
 * <p>每一步都做三件事（顺序即安全语义）：
 * <ol>
 *   <li><b>运行必须活跃</b>：取消或终态后不再执行后续步骤——占用步数与状态检查在同一条 SQL 里完成，
 *       不存在"检查通过后刚被取消"的窗口（AT-016）；</li>
 *   <li><b>预算有界</b>：步数与耗时都受运行预算约束，超限即 429（不静默继续）；</li>
 *   <li><b>政策判定</b>：AUTO 直接执行；CONFIRM 生成动作（冻结参数 + 到期挑战）等待人工确认；
 *       DENY 直接拒绝（判定与执行都走 D08 的闸门，调度器不自己判断政策）。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AiAnalysisStepScheduler {

    private final AiRunStepCounterMapper stepCounterMapper;

    private final AiToolPolicyGate policyGate;

    private final AiToolExecutor toolExecutor;

    private final AiToolActionService actionService;

    private final AiToolService toolService;

    private final AiToolMapper toolMapper;

    /** 执行一个分析步骤（工具调用）。 */
    @Transactional(rollbackFor = Exception.class)
    public AiAnalysisStepResultDTO executeStep(AiAnalysisStepRequestDTO request) {
        if (request == null
                || request.getRunId() == null
                || request.getApplicationId() == null
                || !StringUtils.hasText(request.getSubjectType())
                || !StringUtils.hasText(request.getToolCode())) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiRunBudget budget = request.getBudget() == null ? AiRunBudget.defaults() : request.getBudget();
        AiRunStepCounterMapper.AiRunStepRow run = stepCounterMapper.selectRun(request.getRunId());
        if (run == null || !active(run.status())) {
            throw exception(AI_RUN_NOT_ACTIVE);
        }
        int usedSteps = run.stepCount() == null ? 0 : run.stepCount();
        if (!budget.hasStepLeft(usedSteps)) {
            throw exception(AI_ANALYSIS_STEP_LIMIT_EXCEEDED);
        }
        if (run.elapsedMillis() != null && budget.durationExceeded(run.elapsedMillis())) {
            throw exception(AI_ANALYSIS_STEP_LIMIT_EXCEEDED);
        }
        // 原子占用一步：运行已取消/终态时影响 0 行
        if (stepCounterMapper.incrementIfActive(request.getRunId()) == 0) {
            throw exception(AI_RUN_NOT_ACTIVE);
        }
        int stepsUsedNow = usedSteps + 1;

        Map<String, Object> arguments =
                new LinkedHashMap<>(request.getArguments() == null ? Map.of() : request.getArguments());
        try {
            AiToolDecision decision = policyGate.decide(request.getToolCode(), arguments);
            AiConnectorExecutionResultDTO result = toolExecutor.execute(decision);
            return new AiAnalysisStepResultDTO()
                    .setOutcome(AiAnalysisStepResultDTO.OUTCOME_EXECUTED)
                    .setStepsUsed(stepsUsedNow)
                    .setSourceStatus(result == null ? null : result.getStatus())
                    .setReason(result == null ? null : result.getStoppedReason());
        } catch (ServiceException failure) {
            if (!AI_TOOL_CONFIRMATION_REQUIRED.getCode().equals(failure.getCode())) {
                throw failure;
            }
            // 政策 CONFIRM：冻结参数、生成一次性挑战，等人工确认（不执行任何网络动作）
            AiToolVersionDO version = toolService.requirePublishedVersion(request.getToolCode());
            Map<String, Object> validated = AiToolServiceImpl.validateArguments(version, arguments);
            AiToolDO tool = toolMapper.selectById(version.getToolId());
            AiToolDecision pending = new AiToolDecision(
                    AiToolDecision.Outcome.CONFIRM,
                    version,
                    tool == null ? null : tool.getConnectorId(),
                    version.getSourceRef(),
                    validated);
            AiToolActionDO action = actionService.createFromDecision(
                    pending,
                    request.getRunId(),
                    request.getApplicationId(),
                    request.getSubjectType(),
                    request.getExternalUserId());
            return new AiAnalysisStepResultDTO()
                    .setOutcome(AiAnalysisStepResultDTO.OUTCOME_AWAITING_CONFIRMATION)
                    .setStepsUsed(stepsUsedNow)
                    .setActionId(action.getId())
                    .setChallenge(action.getChallenge())
                    .setExpiresAt(action.getExpiresAt())
                    .setReason(AiToolPolicy.CONFIRM.name());
        }
    }

    /** 运行是否可继续（与 ai_run 的状态词表一致）。 */
    private static boolean active(String status) {
        return "ACCEPTED".equals(status) || "RUNNING".equals(status);
    }
}
