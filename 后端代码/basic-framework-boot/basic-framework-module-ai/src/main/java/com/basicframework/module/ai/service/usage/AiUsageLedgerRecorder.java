package com.basicframework.module.ai.service.usage;

import com.basicframework.module.ai.service.usage.dto.AiUsageRecordDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把 M05 的调用计量落到账本（Q02 的持久化实现）。
 *
 * <p>两条边界：
 * <ol>
 *   <li><b>不写假行</b>：账本要求应用归属与模型标识；低层模型调用若在没有应用上下文的场景发生，
 *       本实现**跳过写入并留下计数告警**，而不是编一个应用编号（编造会让"按应用聚合"失真）；</li>
 *   <li><b>不抛断业务</b>：计量失败不改变调用结果（接口约定），异常在实现内收敛并告警。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiUsageLedgerRecorder implements AiModelUsageRecorder {

    private final AiUsageLedgerService usageLedgerService;

    @Override
    public void record(AiModelInvocationRecord record) {
        if (record == null || record.getInvocationId() == null) {
            return;
        }
        if (record.getApplicationId() == null) {
            // 无应用归属的调用（例如端点能力探测）：不落账本，留可观测信号
            log.warn("用量未归属应用，未落账本 invocationId={}", record.getInvocationId());
            return;
        }
        AiUsageRecordDTO dto = new AiUsageRecordDTO()
                .setInvocationId(record.getInvocationId())
                .setApplicationId(record.getApplicationId())
                .setServiceId(record.getServiceId())
                .setRunId(record.getRunId())
                .setTaskId(record.getTaskId())
                .setSubjectRef(record.getSubjectRef())
                .setModelRef(record.getModelRef() == null ? "endpoint:" + record.getEndpointId() : record.getModelRef())
                .setModelRevision(record.getConfigRevision())
                .setEndpointRef(record.getEndpointId() == null ? null : "endpoint:" + record.getEndpointId())
                .setInputTokens(toLong(record.getPromptTokens()))
                .setOutputTokens(toLong(record.getCompletionTokens()))
                .setUsageSource(sourceOf(record))
                .setDurationMs((int) Math.min(Integer.MAX_VALUE, record.getLatencyMs()))
                .setStatus(record.succeeded() ? "SUCCEEDED" : "FAILED");
        try {
            usageLedgerService.record(dto);
        } catch (RuntimeException failure) {
            // 计量失败不抛断调用链（接口约定）；仅记录 invocationId 与错误类型
            log.warn(
                    "用量落账本失败 invocationId={} error={}",
                    record.getInvocationId(),
                    failure.getClass().getSimpleName());
        }
    }

    /** 来源：估算优先（估算不得参与结算）；否则"有计数即上游报告"，两者都没有即未知。 */
    private static String sourceOf(AiModelInvocationRecord record) {
        if (record.isEstimated()) {
            return "ESTIMATED";
        }
        return record.usageKnown() ? "REPORTED" : "UNKNOWN";
    }

    private static Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }
}
