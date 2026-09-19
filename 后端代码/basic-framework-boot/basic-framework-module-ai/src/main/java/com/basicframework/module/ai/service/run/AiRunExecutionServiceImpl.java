package com.basicframework.module.ai.service.run;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RUN_ALREADY_TERMINAL;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RUN_BUDGET_EXCEEDED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RUN_NOT_EXECUTABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RUN_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_UNSUPPORTED;

import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.ModelToolCall;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationMessageDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.domain.identity.AiExecutionContext;
import com.basicframework.module.ai.domain.policy.AiOutboundLevel;
import com.basicframework.module.ai.domain.runtime.AiModelFailureCodes;
import com.basicframework.module.ai.domain.runtime.AiRunBudget;
import com.basicframework.module.ai.domain.runtime.AiRunSnapshot;
import com.basicframework.module.ai.service.context.AiContextBuilder;
import com.basicframework.module.ai.service.context.dto.AiContextBuildDTO;
import com.basicframework.module.ai.service.context.dto.AiContextHistoryDTO;
import com.basicframework.module.ai.service.context.dto.AiContextResultDTO;
import com.basicframework.module.ai.service.conversation.AiConversationService;
import com.basicframework.module.ai.service.conversation.dto.AiConversationRunContextDTO;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.AiModelInvocationResult;
import com.basicframework.module.ai.service.model.AiModelInvocationService;
import com.basicframework.module.ai.service.run.dto.AiRunExecutionResultDTO;
import com.basicframework.module.ai.service.serviceconfig.AiServiceReleaseService;
import com.basicframework.module.ai.service.task.AiTaskService;
import com.basicframework.module.ai.service.task.dto.AiTaskLeaseDTO;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 文本运行执行器（O04）：请求校验 → 身份重建 → 上下文 → 模型 → 结果落库，全程受预算约束。
 *
 * <p>关键语义：
 * <ul>
 *   <li><b>有界执行</b>：步数、总耗时与工具次数都有上限，用尽即以稳定原因结束；</li>
 *   <li><b>无假成功</b>：上游异常统一收敛为平台错误码，运行写 FAILED，绝不把异常当成功；</li>
 *   <li><b>工具明确不支持</b>：模型请求工具调用时，只有注册在受控执行接口里的工具才会执行；
 *       没有实现时直接以 {@code AI_TOOL_UNSUPPORTED} 结束，不"跳过工具继续生成"；</li>
 *   <li><b>终态原子且不可覆盖</b>：终态写入用任务租约栅栏 + 运行行乐观锁，
 *       晚到的回调（旧 worker、重复执行）无法覆盖已写入的终态；</li>
 *   <li><b>网络调用在短事务之外</b>：身份重建、上下文与模型调用都不持有事务，落库时才开短事务。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AiRunExecutionServiceImpl implements AiRunExecutionService {

    private final AiRunMapper runMapper;

    private final AiServiceReleaseService releaseService;

    private final AiModelEndpointService endpointService;

    private final AiModelInvocationService invocationService;

    private final AiContextBuilder contextBuilder;

    private final AiConversationService conversationService;

    private final AiTaskService taskService;

    private final AiToolExecutor toolExecutor;

    private final AiRunTerminalWriter terminalWriter;

    @Override
    public AiRunExecutionResultDTO execute(AiTaskLeaseDTO lease, AiRunBudget budget) {
        AiRunBudget effective = budget == null ? AiRunBudget.defaults() : budget;
        AiRunDO run = requireRunnableRun(lease);
        long startedAt = System.nanoTime();
        List<String> toolCallTrace = new ArrayList<>();

        // 1) 身份重建：撤销、范围收窄、应用停用都会在这里失败（不继承任何旧身份）
        AiExecutionContext context = taskService.rebuildIdentity(run.getId());
        if (context.isDeny()) {
            throw exception(AI_RUN_NOT_EXECUTABLE, "执行身份不可用");
        }
        // 2) 发布版本固定值重新判定：资源授权与停用状态按当前值
        AiServiceReleaseDO release = requireRelease(run);
        AiRunSnapshot pin = AiRunSnapshot.of(release, releaseService.listReleaseBindings(release.getId()));
        releaseService.resolvePinnedRun(pin);
        // 3) 输入：用户消息属于受控业务数据，执行阶段从会话回放（不再单独保存正文）
        String userMessage = latestUserMessage(run);
        AiContextResultDTO prompt = contextBuilder.build(new AiContextBuildDTO()
                .setSystemPrompt(release.getPromptTemplate())
                .setHistory(history(run))
                .setUserMessage(userMessage));
        if (!effective.hasStepLeft(0)) {
            throw exception(AI_RUN_BUDGET_EXCEEDED, "步数");
        }
        if (effective.durationExceeded(elapsed(startedAt))) {
            throw exception(AI_RUN_BUDGET_EXCEEDED, "耗时");
        }

        // 4) 模型调用（外发策略先于网络调用；超时按预算上限收敛）
        String modelId = currentModelId(pin.getModelEndpointId());
        Duration timeout = Duration.ofMillis(
                Math.min(effective.getMaxDurationMillis(), effective.getMaxDurationMillis() - elapsed(startedAt)));
        AiModelInvocationResult<ModelResponse> invoked;
        try {
            invoked = invocationService.generate(
                    pin.getModelEndpointId(),
                    new ModelRequest(modelId, prompt.getPrompt(), timeout),
                    AiOutboundLevel.parse(run.getDataLevel()).orElse(AiOutboundLevel.L2_INTERNAL));
        } catch (ModelException failure) {
            // 上游失败：写 FAILED 并抛稳定错误码，绝不返回假成功
            String errorCode =
                    String.valueOf(AiModelFailureCodes.of(failure.getReason()).getCode());
            finishRun(lease, run, AiRunDO.STATUS_FAILED, errorCode, 1, null);
            throw AiModelFailureCodes.toServiceException(failure);
        }
        ModelResponse output = invoked.output();

        // 5) 工具调用只交受控执行接口；没有实现时明确不支持
        int toolCalls = 0;
        for (ModelToolCall toolCall : output.toolCalls()) {
            if (!effective.hasToolCallLeft(toolCalls)) {
                finishRun(lease, run, AiRunDO.STATUS_FAILED, "TOOL_BUDGET_EXCEEDED", 1, null);
                throw exception(AI_RUN_BUDGET_EXCEEDED, "工具次数");
            }
            var binding = toolExecutor.find(toolCall.name());
            if (binding.isEmpty()) {
                // 没有受控工具实现：明确不支持，不"跳过工具继续生成"
                finishRun(lease, run, AiRunDO.STATUS_FAILED, "TOOL_UNSUPPORTED", 1, null);
                throw exception(AI_TOOL_UNSUPPORTED);
            }
            toolCalls++;
            toolCallTrace.add(toolCall.name());
        }
        if (!effective.hasStepLeft(1)) {
            finishRun(lease, run, AiRunDO.STATUS_FAILED, "STEP_BUDGET_EXCEEDED", 1, null);
            throw exception(AI_RUN_BUDGET_EXCEEDED, "步数");
        }
        if (effective.durationExceeded(elapsed(startedAt))) {
            finishRun(lease, run, AiRunDO.STATUS_FAILED, "DURATION_BUDGET_EXCEEDED", 1, null);
            throw exception(AI_RUN_BUDGET_EXCEEDED, "耗时");
        }

        // 6) 结果落库：助手消息与运行终态在同一事务内写入
        int steps = 1;
        boolean finished = finishRun(lease, run, AiRunDO.STATUS_SUCCEEDED, null, steps, output.text());
        if (!finished) {
            throw exception(AI_RUN_ALREADY_TERMINAL);
        }
        return new AiRunExecutionResultDTO()
                .setRunId(run.getId())
                .setStatus(AiRunDO.STATUS_SUCCEEDED)
                .setSteps(steps)
                .setToolCalls(toolCalls)
                .setDurationMillis(elapsed(startedAt))
                .setOutputText(output.text());
    }

    /** 落库：助手消息 + 运行终态（短事务，见 {@link AiRunTerminalWriter}）。 */
    private boolean finishRun(
            AiTaskLeaseDTO lease, AiRunDO run, String status, String errorCode, int steps, String assistantMessage) {
        return terminalWriter.finish(lease, run, status, errorCode, steps, assistantMessage);
    }

    private AiRunDO requireRunnableRun(AiTaskLeaseDTO lease) {
        Long runId = lease == null ? null : lease.getRunId();
        AiRunDO run = runId == null ? null : runMapper.selectById(runId);
        if (run == null) {
            throw exception(AI_RUN_NOT_FOUND);
        }
        if (!AiRunDO.STATUS_ACCEPTED.equals(run.getStatus()) && !AiRunDO.STATUS_RUNNING.equals(run.getStatus())) {
            throw exception(AI_RUN_ALREADY_TERMINAL);
        }
        return run;
    }

    private AiServiceReleaseDO requireRelease(AiRunDO run) {
        return releaseService.listReleases(run.getServiceId()).stream()
                .filter(release -> release.getId().equals(run.getReleaseId()))
                .findFirst()
                .orElseThrow(() -> exception(AI_RUN_NOT_EXECUTABLE, "发布版本不存在"));
    }

    /** 执行输入 = 会话里最近一条用户消息（受理时已与幂等记录、运行、首任务同事务落库）。 */
    private String latestUserMessage(AiRunDO run) {
        if (run.getConversationId() == null) {
            throw exception(AI_RUN_NOT_EXECUTABLE, "无会话的运行没有可回放的输入消息");
        }
        return conversationService.listMessages(run.getConversationId(), null, 100).stream()
                .filter(message -> AiConversationMessageDO.ROLE_USER.equals(message.getRole()))
                .reduce((first, second) -> second)
                .map(AiConversationMessageDO::getContent)
                .filter(StringUtils::hasText)
                .orElseThrow(() -> exception(AI_RUN_NOT_EXECUTABLE, "会话中没有用户消息"));
    }

    /** 上下文历史 = 会话已有消息（不含本次输入的最后一条由 userMessage 传入）。 */
    private List<AiContextHistoryDTO> history(AiRunDO run) {
        if (run.getConversationId() == null) {
            return List.of();
        }
        AiConversationRunContextDTO context = conversationService.loadRunContext(run.getConversationId(), 20);
        return context.getHistory().stream()
                .map(message ->
                        new AiContextHistoryDTO().setRole(message.getRole()).setContent(message.getContent()))
                .toList();
    }

    private String currentModelId(Long endpointId) {
        return endpointService.getRevisions(endpointId).stream()
                .findFirst()
                .map(revision -> revision.getModelId())
                .orElseThrow(() -> exception(AI_RUN_NOT_EXECUTABLE, "端点缺少配置版本"));
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
