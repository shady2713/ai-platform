package com.basicframework.module.ai.service.realtime;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_TOOL_CALL_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_TOOL_IN_PROGRESS_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_TOOL_POLICY_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_CONFIRMATION_REQUIRED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_POLICY_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_WRITE_REQUIRES_CONFIRMATION;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeEventDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeSessionDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeToolCallDO;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeToolCallMapper;
import com.basicframework.module.ai.domain.tool.AiToolPolicy;
import com.basicframework.module.ai.service.tool.AiToolDecision;
import com.basicframework.module.ai.service.tool.AiToolExecutor;
import com.basicframework.module.ai.service.tool.AiToolPolicyGate;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 会话内工具执行桥（X05 + X06）：只执行**免确认的读工具**，且每个调用最多执行一次。
 *
 * <p>为什么必须走 D08/X06 的闸门而不是"按工具名执行"：模型能影响的只有工具标识与参数值——
 * 政策（DENY/CONFIRM/AUTO）、来源操作、参数 schema 都来自**已发布版本快照**；
 * 写工具与需要人工确认的工具在会话内一律拒绝（它们必须走运行/动作流程：动作行要求绑定运行，
 * 并带一次性确认挑战，会话内没有这套上下文）。判定与执行顺序与运行链路一致，不另开一条捷径。
 *
 * <p>幂等（AT-069 的另一半）：执行权只从 {@code PROPOSED} 消费一次；重连后客户端重发执行请求时，
 * 已终态的调用直接返回既有结论（{@code EXECUTED}/{@code FAILED}/{@code REJECTED}），
 * 正在执行的调用返回 409，不会出现第二次副作用。
 */
@Component
@RequiredArgsConstructor
public class AiRealtimeToolBridge {

    private static final int MAX_RESULT_CODE_LENGTH = 64;

    private final AiRealtimeToolCallMapper toolCallMapper;

    private final AiRealtimeEventApplier eventApplier;

    private final AiToolPolicyGate policyGate;

    private final AiToolExecutor toolExecutor;

    /** 执行一次会话内工具调用（幂等：终态直接返回既有结论）。 */
    public AiRealtimeToolCallDO execute(AiRealtimeSessionDO session, AiRealtimeToolCallDO call) {
        if (settled(call.getStatus())) {
            // 重连重放：返回既有结论，不产生第二次调用
            return call;
        }
        if (!AiRealtimeToolCallDO.STATUS_PROPOSED.equals(call.getStatus())
                || toolCallMapper.claimExecution(call.getId()) == 0) {
            AiRealtimeToolCallDO current = toolCallMapper.selectById(call.getId());
            if (current == null) {
                throw exception(AI_REALTIME_TOOL_CALL_NOT_EXISTS);
            }
            if (settled(current.getStatus())) {
                return current;
            }
            // PROPOSED 之外的中间态（或并发抢输）：执行权已被消费，不能重复执行
            throw exception(AI_REALTIME_TOOL_IN_PROGRESS_CONFLICT);
        }
        return decideAndExecute(session, call);
    }

    /** 判定（政策 + 参数 schema + 写工具拒绝）→ 执行 → 落终态。 */
    private AiRealtimeToolCallDO decideAndExecute(AiRealtimeSessionDO session, AiRealtimeToolCallDO call) {
        Map<String, Object> arguments = parseArguments(session, call);
        AiToolDecision decision;
        try {
            decision = policyGate.decide(call.getToolCode(), arguments);
        } catch (ServiceException denied) {
            rejectAndThrow(session, call, denialCode(denied));
            return null;
        }
        if (AiToolPolicy.ToolType.parse(decision.version().getToolType()) == AiToolPolicy.ToolType.WRITE) {
            // 写工具必须走确认/动作流程：会话内不执行（判定为 EXECUTE 也不放行）
            rejectAndThrow(session, call, "AI_TOOL_WRITE_REQUIRES_CONFIRMATION");
            return null;
        }
        toolCallMapper.bindDecision(call.getId(), decision.version().getId(), AiToolPolicy.ToolType.READ.name());
        AiConnectorExecutionResultDTO result;
        try {
            result = toolExecutor.execute(decision);
        } catch (ServiceException failure) {
            failAndThrow(session, call, failure);
            return null;
        } catch (RuntimeException failure) {
            failAndThrow(session, call, failure);
            return null;
        }
        finish(session, call, AiRealtimeToolCallDO.STATUS_EXECUTED, resultCodeOf(result));
        return toolCallMapper.selectById(call.getId());
    }

    /** 参数正文只从冻结事实读取（客户端不能改参数）；解析失败按拒绝处理。 */
    private Map<String, Object> parseArguments(AiRealtimeSessionDO session, AiRealtimeToolCallDO call) {
        Map<String, Object> parsed = null;
        try {
            parsed = JsonUtils.parseObject(call.getArgumentsJson(), Map.class);
        } catch (RuntimeException invalid) {
            parsed = null;
        }
        if (parsed == null) {
            rejectAndThrow(session, call, "ARGUMENTS_INVALID");
        }
        return parsed;
    }

    /** 拒绝（终态）并抛平台稳定错误码；返回值只为让调用点保持单返回形状（方法总是抛出）。 */
    private AiRealtimeToolCallDO rejectAndThrow(
            AiRealtimeSessionDO session, AiRealtimeToolCallDO call, String resultCode) {
        toolCallMapper.reject(call.getId(), bound(resultCode), LocalDateTime.now());
        recordToolEvent(session, call, "REJECTED", resultCode);
        throw exception(AI_REALTIME_TOOL_POLICY_DENIED);
    }

    /** 执行异常：落 FAILED（稳定结论码），并把平台错误码原样抛给调用方。 */
    private AiRealtimeToolCallDO failAndThrow(
            AiRealtimeSessionDO session, AiRealtimeToolCallDO call, RuntimeException failure) {
        String code = failure instanceof ServiceException serviceFailure
                ? String.valueOf(serviceFailure.getCode())
                : failure.getClass().getSimpleName();
        finish(session, call, AiRealtimeToolCallDO.STATUS_FAILED, code);
        throw failure;
    }

    /** 落终态并留痕（同一调用同一状态的重复写入被事件去重键吞掉）。 */
    private void finish(AiRealtimeSessionDO session, AiRealtimeToolCallDO call, String status, String resultCode) {
        toolCallMapper.finishExecution(call.getId(), status, bound(resultCode), LocalDateTime.now());
        recordToolEvent(session, call, status, resultCode);
    }

    private void recordToolEvent(
            AiRealtimeSessionDO session, AiRealtimeToolCallDO call, String status, String resultCode) {
        if (session == null) {
            return;
        }
        eventApplier.record(
                session.getId(),
                AiRealtimeEventDO.TYPE_TOOL_CALL,
                call.getTurnNo(),
                0,
                call.getToolCode(),
                0,
                call.getCallId() + ":" + status,
                "tool:" + call.getCallId() + ":" + status);
    }

    /** 结论码：优先上游稳定明细码，其次结论状态，最后固定成功码。 */
    private static String resultCodeOf(AiConnectorExecutionResultDTO result) {
        if (result == null) {
            return "EXECUTED";
        }
        if (StringUtils.hasText(result.getDetailCode())) {
            return result.getDetailCode();
        }
        return StringUtils.hasText(result.getStatus()) ? result.getStatus() : "EXECUTED";
    }

    /** 政策拒绝的稳定码（只映射平台已知的拒绝语义，未知码保留平台数字码）。 */
    private static String denialCode(ServiceException denied) {
        if (AI_TOOL_CONFIRMATION_REQUIRED.getCode().equals(denied.getCode())) {
            return "AI_TOOL_CONFIRMATION_REQUIRED";
        }
        if (AI_TOOL_WRITE_REQUIRES_CONFIRMATION.getCode().equals(denied.getCode())) {
            return "AI_TOOL_WRITE_REQUIRES_CONFIRMATION";
        }
        if (AI_TOOL_POLICY_DENIED.getCode().equals(denied.getCode())) {
            return "AI_TOOL_POLICY_DENIED";
        }
        return String.valueOf(denied.getCode());
    }

    private static boolean settled(String status) {
        return AiRealtimeToolCallDO.STATUS_EXECUTED.equals(status)
                || AiRealtimeToolCallDO.STATUS_FAILED.equals(status)
                || AiRealtimeToolCallDO.STATUS_REJECTED.equals(status);
    }

    /** 结论码长度对齐列宽：平台稳定码远短于此，这里只防御未知上游结论。 */
    private static String bound(String resultCode) {
        String value = resultCode == null || resultCode.isBlank() ? "EXECUTED" : resultCode;
        return value.length() <= MAX_RESULT_CODE_LENGTH ? value : value.substring(0, MAX_RESULT_CODE_LENGTH);
    }
}
