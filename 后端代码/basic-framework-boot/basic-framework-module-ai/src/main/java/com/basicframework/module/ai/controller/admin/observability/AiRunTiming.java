package com.basicframework.module.ai.controller.admin.observability;

import com.basicframework.module.ai.controller.admin.observability.vo.AiRunTimingRespVO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.usage.AiUsageLedgerDO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 运行耗时分解（Q03）：**只把实测值当实测值**。
 *
 * <p>模型耗时来自用量账本的真实计量（上游报告或平台估算，来源未知的单独计数）；
 * 检索与业务 API 在运行链路里尚未单独计量，因此固定列入 {@code unmeasuredStages}，
 * 字段留空而不是写 0（AT-060：不把"不知道"显示成"零"）。
 */
final class AiRunTiming {

    /** 已终态的运行：总耗时按最后更新时间计算。 */
    private static final Set<String> TERMINAL_STATUSES =
            Set.of(AiRunDO.STATUS_SUCCEEDED, AiRunDO.STATUS_FAILED, AiRunDO.STATUS_CANCELLED);

    /** 运行链路里尚未单独计量的阶段（稳定名称，与运维口径一致）。 */
    static final List<String> UNMEASURED_STAGES = List.of("RETRIEVAL", "BUSINESS_API");

    private AiRunTiming() {}

    static AiRunTimingRespVO of(AiRunDO run, List<AiUsageLedgerDO> usages, LocalDateTime now) {
        List<AiUsageLedgerDO> list = usages == null ? List.of() : usages;
        long modelDuration = 0L;
        int unknownUsage = 0;
        for (AiUsageLedgerDO usage : list) {
            if (usage.getDurationMs() != null) {
                modelDuration += usage.getDurationMs();
            }
            if (AiUsageLedgerDO.SOURCE_UNKNOWN.equals(usage.getUsageSource())) {
                unknownUsage++;
            }
        }
        return new AiRunTimingRespVO()
                .setTotalDurationMs(totalDurationMs(run, now))
                .setModelDurationMs(modelDuration)
                .setModelInvocationCount(list.size())
                .setUnknownUsageCount(unknownUsage)
                .setRetrievalDurationMs(null)
                .setBusinessApiDurationMs(null)
                .setUnmeasuredStages(UNMEASURED_STAGES);
    }

    /** 受理到终态的毫秒数；仍在执行时按当前时刻计算，时间缺失或为负则留空。 */
    private static Long totalDurationMs(AiRunDO run, LocalDateTime now) {
        if (run == null || run.getCreateTime() == null) {
            return null;
        }
        LocalDateTime end =
                TERMINAL_STATUSES.contains(run.getStatus()) && run.getUpdateTime() != null ? run.getUpdateTime() : now;
        if (end == null || end.isBefore(run.getCreateTime())) {
            return null;
        }
        return Duration.between(run.getCreateTime(), end).toMillis();
    }
}
