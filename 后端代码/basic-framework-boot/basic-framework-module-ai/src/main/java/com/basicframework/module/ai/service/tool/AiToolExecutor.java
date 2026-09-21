package com.basicframework.module.ai.service.tool;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_POLICY_DENIED;

import com.basicframework.module.ai.adapter.connector.http.AiHttpConnectorExecutor;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionRequestDTO;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 工具执行器（D08）：只接受 {@link AiToolDecision}，不接受"工具名 + 参数"。
 *
 * <p>为什么执行器只吃判定对象：判定里带着**已发布版本**（政策、来源、schema 都来自版本快照），
 * 执行器因此无法被"传一个工具名"绕过政策；非 EXECUTE 判定直接拒绝（CONFIRM 必须先经确认流程）。
 * 执行本身复用 D02 的 HTTP 执行链路：固定 Origin、请求头只来自连接器、分页有界。
 */
@Service
@RequiredArgsConstructor
public class AiToolExecutor {

    private final AiHttpConnectorExecutor httpConnectorExecutor;

    /** 执行一次已判定的工具调用。 */
    public AiConnectorExecutionResultDTO execute(AiToolDecision decision) {
        if (decision == null || !decision.executable() || decision.connectorId() == null) {
            // 没有 EXECUTE 判定就没有执行许可（CONFIRM 必须走确认流程后重新判定）
            throw exception(AI_TOOL_POLICY_DENIED);
        }
        return httpConnectorExecutor.execute(new AiConnectorExecutionRequestDTO()
                .setConnectorId(decision.connectorId())
                .setOperationKey(decision.operationKey())
                .setArguments(decision.arguments()));
    }
}
