package com.basicframework.module.ai.service.tool;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_POLICY_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_WRITE_REQUIRES_CONFIRMATION;

import com.basicframework.module.ai.adapter.connector.http.AiHttpConnectorExecutor;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionRequestDTO;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import com.basicframework.module.ai.domain.tool.AiToolPolicy;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 工具执行器（D08 + X06）：只接受 {@link AiToolDecision}，不接受"工具名 + 参数"。
 *
 * <p>为什么执行器只吃判定对象：判定里带着**已发布版本**（政策、来源、schema 都来自版本快照），
 * 执行器因此无法被"传一个工具名"绕过政策；非 EXECUTE 判定直接拒绝（CONFIRM 必须先经确认流程）。
 * 执行本身复用 D02 的 HTTP 执行链路：固定 Origin、请求头只来自连接器、分页有界。
 *
 * <p>X06 增补两个受控入口：
 * <ul>
 *   <li><b>写调用</b>只能走 {@link #executeWrite}：必须带业务幂等键，且判定必须来自写工具的已发布版本；
 *       既有的 {@link #execute} 明确拒绝写工具判定（{@link #execute} 是读/自动路径，
 *       写工具只能经"确认 + 消费一次"的动作入口进来）；</li>
 *   <li><b>核对查询</b>走 {@link #executeReconcile}：只允许在动作所属连接器上执行**登记的**核对操作，
 *       参数只有业务键——核对路径不能变成"任意查询"通道。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AiToolExecutor {

    private final AiHttpConnectorExecutor httpConnectorExecutor;

    /** 执行一次已判定的**读**工具调用（写工具判定一律拒绝，必须经受控写入口）。 */
    public AiConnectorExecutionResultDTO execute(AiToolDecision decision) {
        requireExecutable(decision);
        if (isWrite(decision)) {
            // 写工具不能走通用执行路径：它必须带业务幂等键并经确认动作进入
            throw exception(AI_TOOL_WRITE_REQUIRES_CONFIRMATION);
        }
        return httpConnectorExecutor.execute(new AiConnectorExecutionRequestDTO()
                .setConnectorId(decision.connectorId())
                .setOperationKey(decision.operationKey())
                .setArguments(decision.arguments()));
    }

    /**
     * 执行一次已判定的**写**工具调用：业务幂等键必须存在（上游据此去重，平台据此保证不第二次副作用）。
     */
    public AiConnectorExecutionResultDTO executeWrite(AiToolDecision decision, String idempotencyKey) {
        requireExecutable(decision);
        if (!isWrite(decision) || !StringUtils.hasText(idempotencyKey)) {
            // 没有写判定或没有业务键：写入口不可用（宁可不发，也不发一个无法去重的写）
            throw exception(AI_TOOL_WRITE_BINDING_INVALID);
        }
        return httpConnectorExecutor.execute(new AiConnectorExecutionRequestDTO()
                .setConnectorId(decision.connectorId())
                .setOperationKey(decision.operationKey())
                .setArguments(decision.arguments()));
    }

    /** 执行一次**登记的**核对查询（只读）：连接器必须是写工具自己的连接器，参数只带业务键。 */
    public AiConnectorExecutionResultDTO executeReconcile(
            Long connectorId, String reconcileOperationKey, Map<String, Object> arguments) {
        if (connectorId == null
                || !StringUtils.hasText(reconcileOperationKey)
                || arguments == null
                || arguments.isEmpty()) {
            throw exception(AI_TOOL_WRITE_BINDING_INVALID);
        }
        return httpConnectorExecutor.execute(new AiConnectorExecutionRequestDTO()
                .setConnectorId(connectorId)
                .setOperationKey(reconcileOperationKey)
                .setArguments(arguments));
    }

    private static void requireExecutable(AiToolDecision decision) {
        if (decision == null || !decision.executable() || decision.connectorId() == null) {
            // 没有 EXECUTE 判定就没有执行许可（CONFIRM 必须走确认流程后重新判定）
            throw exception(AI_TOOL_POLICY_DENIED);
        }
    }

    private static boolean isWrite(AiToolDecision decision) {
        return AiToolPolicy.ToolType.parse(decision.version().getToolType()) == AiToolPolicy.ToolType.WRITE;
    }
}
