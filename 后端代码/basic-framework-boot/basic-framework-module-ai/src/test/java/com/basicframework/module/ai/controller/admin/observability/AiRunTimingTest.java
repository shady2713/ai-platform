package com.basicframework.module.ai.controller.admin.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.controller.admin.observability.vo.AiRunTimingRespVO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.usage.AiUsageLedgerDO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 耗时分解口径（Q03）：模型耗时取实测之和；未知计量计数；检索/业务 API 明确未计量。 */
class AiRunTimingTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 26, 10, 0, 0);

    private static AiUsageLedgerDO usage(Integer durationMs, String source) {
        return new AiUsageLedgerDO().setDurationMs(durationMs).setUsageSource(source);
    }

    @Test
    void measuredModelDurationSumsOnlyRealValues() {
        AiRunDO succeeded = new AiRunDO().setStatus(AiRunDO.STATUS_SUCCEEDED);
        succeeded.setCreateTime(START);
        succeeded.setUpdateTime(START.plusSeconds(5));
        AiRunTimingRespVO timing = AiRunTiming.of(
                succeeded,
                List.of(usage(800, AiUsageLedgerDO.SOURCE_REPORTED), usage(null, AiUsageLedgerDO.SOURCE_UNKNOWN)),
                START.plusSeconds(30));

        assertThat(timing.getModelDurationMs()).isEqualTo(800L);
        assertThat(timing.getModelInvocationCount()).isEqualTo(2);
        assertThat(timing.getUnknownUsageCount()).isEqualTo(1);
        assertThat(timing.getTotalDurationMs()).as("终态运行按更新时间算总耗时").isEqualTo(5_000L);
        assertThat(timing.getRetrievalDurationMs()).isNull();
        assertThat(timing.getBusinessApiDurationMs()).isNull();
        assertThat(timing.getUnmeasuredStages()).containsExactly("RETRIEVAL", "BUSINESS_API");
    }

    @Test
    void runningRunUsesCurrentTimeAndEmptyLedgerIsZeroNotUnknown() {
        AiRunDO running = new AiRunDO().setStatus(AiRunDO.STATUS_RUNNING);
        running.setCreateTime(START);
        AiRunTimingRespVO timing = AiRunTiming.of(running, List.of(), START.plusSeconds(7));

        assertThat(timing.getTotalDurationMs()).isEqualTo(7_000L);
        assertThat(timing.getModelDurationMs()).as("没有计量记录是 0 次调用（与「来源未知」不同）").isZero();
        assertThat(timing.getModelInvocationCount()).isZero();
        assertThat(timing.getUnknownUsageCount()).isZero();
    }

    @Test
    void missingOrInconsistentTimestampsLeaveDurationEmpty() {
        assertThat(AiRunTiming.of(new AiRunDO().setStatus(AiRunDO.STATUS_ACCEPTED), List.of(), START)
                        .getTotalDurationMs())
                .isNull();
        AiRunDO inconsistent = new AiRunDO().setStatus(AiRunDO.STATUS_FAILED);
        inconsistent.setCreateTime(START);
        inconsistent.setUpdateTime(START.minusSeconds(1));
        assertThat(AiRunTiming.of(inconsistent, null, START).getTotalDurationMs())
                .as("时间倒挂不编造负数耗时")
                .isNull();
        assertThat(AiRunTiming.of(null, null, START).getUnmeasuredStages())
                .containsExactly("RETRIEVAL", "BUSINESS_API");
    }
}
