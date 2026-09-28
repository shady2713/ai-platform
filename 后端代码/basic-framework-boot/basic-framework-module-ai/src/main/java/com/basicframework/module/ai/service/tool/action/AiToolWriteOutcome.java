package com.basicframework.module.ai.service.tool.action;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_ARGUMENT_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_CONFIG_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_DISABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_PUBLISHED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_ORIGIN_MISMATCH;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_ARGUMENT_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_POLICY_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_VERSION_NOT_PUBLISHED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_WRITE_REQUIRES_CONFIRMATION;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import java.util.Set;

/**
 * 写调用结果判定（X06，AT-019）：把一次写调用的结果**保守地**分成"已生效/明确拒绝/结果未定"。
 *
 * <p>判定的方向是安全而不是乐观：
 * <ul>
 *   <li>{@link Verdict#APPLIED}：上游明确成功（COMPLETE/PARTIAL）；</li>
 *   <li>{@link Verdict#REJECTED}：**能证明请求没有发出或上游明确拒绝**（出站策略拒绝、连接器停用、
 *       参数非法、HTTP 4xx）——此时确定没有副作用，可以如实记 FAILED；</li>
 *   <li>{@link Verdict#UNKNOWN}：其余全部（超时、连接失败或中断、响应超限、上游 3xx/5xx、
 *       响应不可解析、未知异常）——请求可能已经到达并生效，必须记 UNKNOWN 并走核对，
 *       <b>绝不</b>当成 FAILED 去"重试"。</li>
 * </ul>
 *
 * <p>为什么连接失败也算 UNKNOWN：出站客户端把"连接建立失败"与"请求已发出但读取响应失败"
 * 收敛为同一个稳定原因（{@code CONNECT_FAILED}），平台无法区分二者；宁可多核对一次，
 * 也不能谎报"没有发生"。
 */
public final class AiToolWriteOutcome {

    /** 结果未定的稳定结论码。 */
    public static final String REASON_UNKNOWN = "result-unknown";

    /** 无法确认的异常结论码（稳定、不含异常消息）。 */
    public static final String REASON_UNCONFIRMED = "request-unconfirmed";

    /** 出站原因 → 判定：这些原因在**发出请求之前**就拒绝了，确定没有副作用。 */
    private static final Set<String> PRE_SEND_REASONS =
            Set.of("TARGET_NOT_ALLOWED", "PRIVATE_TARGET_DENIED", "INVALID_REQUEST");

    /** 错误码 → 判定：连接器/工具在发送前抛出的稳定拒绝码。 */
    private static final Set<Integer> PRE_SEND_CODES = Set.of(
            AI_TOOL_NOT_FOUND.getCode(),
            AI_TOOL_VERSION_NOT_PUBLISHED.getCode(),
            AI_TOOL_POLICY_DENIED.getCode(),
            AI_TOOL_ARGUMENT_INVALID.getCode(),
            AI_TOOL_WRITE_BINDING_INVALID.getCode(),
            AI_TOOL_WRITE_REQUIRES_CONFIRMATION.getCode(),
            AI_CONNECTOR_NOT_FOUND.getCode(),
            AI_CONNECTOR_DISABLED.getCode(),
            AI_CONNECTOR_ARGUMENT_INVALID.getCode(),
            AI_CONNECTOR_CONFIG_INVALID.getCode(),
            AI_CONNECTOR_ORIGIN_MISMATCH.getCode(),
            AI_CONNECTOR_OPERATION_NOT_FOUND.getCode(),
            AI_CONNECTOR_OPERATION_NOT_PUBLISHED.getCode());

    private AiToolWriteOutcome() {}

    /** 判定词表（落库口径）。 */
    public enum Verdict {
        /** 上游明确成功。 */
        APPLIED,
        /** 明确拒绝（请求未发出或上游明确拒绝，确定无副作用）。 */
        REJECTED,
        /** 结果未定（可能已生效，必须核对）。 */
        UNKNOWN
    }

    /** 判定与稳定结论码（结论码长度受 {@code ai_tool_action.result_code} 列宽约束）。 */
    public record Classification(Verdict verdict, String reasonCode) {

        public Classification {
            reasonCode = reasonCode == null ? REASON_UNKNOWN : reasonCode;
        }

        /** 是否确定无副作用（可如实记 FAILED）。 */
        public boolean rejected() {
            return verdict == Verdict.REJECTED;
        }

        /** 结果是否确定（APPLIED/REJECTED 都确定；UNKNOWN 未定）。 */
        public boolean settled() {
            return verdict != Verdict.UNKNOWN;
        }
    }

    /** 上游结果对象 → 判定（结果对象为 null 也按"未确认"处理）。 */
    public static Classification classifyResult(AiConnectorExecutionResultDTO result) {
        if (result == null) {
            return new Classification(Verdict.UNKNOWN, REASON_UNKNOWN);
        }
        String status = result.getStatus() == null ? "" : result.getStatus();
        if ("COMPLETE".equals(status) || "PARTIAL".equals(status)) {
            // 上游 2xx 且结果可用：写已生效（PARTIAL 是读侧分页结论，不影响"写已生效"）
            return new Classification(Verdict.APPLIED, status);
        }
        String detail = result.getDetailCode();
        if (detail == null || detail.isBlank()) {
            return new Classification(Verdict.UNKNOWN, REASON_UNKNOWN);
        }
        if (detail.startsWith("HTTP_")) {
            Integer httpStatus = httpStatusOf(detail);
            if (httpStatus != null && httpStatus >= 400 && httpStatus < 500) {
                // 上游明确拒绝（参数/权限/冲突）：副作用未发生
                return new Classification(Verdict.REJECTED, detail);
            }
            // 3xx（不跟随重定向）与 5xx：上游可能已处理，结果未定
            return new Classification(Verdict.UNKNOWN, detail);
        }
        if ("AI_DISABLED".equals(detail)) {
            // 没有受控出站客户端：请求从未发出
            return new Classification(Verdict.REJECTED, detail);
        }
        if (PRE_SEND_REASONS.contains(detail)) {
            return new Classification(Verdict.REJECTED, detail);
        }
        // TIMEOUT / CONNECT_FAILED / RESPONSE_TOO_LARGE / 未知原因：结果未定
        return new Classification(Verdict.UNKNOWN, detail);
    }

    /** 抛出的异常 → 判定：只有"发送前"的稳定拒绝码才能记 FAILED。 */
    public static Classification classifyFailure(Throwable failure) {
        if (failure instanceof ServiceException serviceFailure && PRE_SEND_CODES.contains(serviceFailure.getCode())) {
            return new Classification(Verdict.REJECTED, String.valueOf(serviceFailure.getCode()));
        }
        // 其余异常（含读取中断、解析失败等未知异常）：不猜结论
        return new Classification(Verdict.UNKNOWN, REASON_UNCONFIRMED);
    }

    private static Integer httpStatusOf(String detail) {
        try {
            return Integer.valueOf(detail.substring("HTTP_".length()));
        } catch (NumberFormatException notAStatus) {
            return null;
        }
    }
}
