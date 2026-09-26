package com.basicframework.module.ai.controller.admin.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;
import org.junit.jupiter.api.Test;

/**
 * 重试判据规则矩阵（Q03）：与 O06 的按主体重试同判据——
 * 只有"任务失败且运行未结束"才允许重试，结果未知与仍在执行都必须拒绝。
 */
class AiRunRetryPolicyTest {

    private static AiRunDO run(String status) {
        return new AiRunDO().setId(1L).setStatus(status);
    }

    private static AiRunTaskDO task(String status) {
        return new AiRunTaskDO()
                .setId(2L)
                .setRunId(1L)
                .setTaskKind(AiRunTaskDO.KIND_RUN_STEP)
                .setStatus(status);
    }

    @Test
    void onlyFailedTaskOfUnfinishedRunIsRetryable() {
        assertThat(AiRunRetryPolicy.blockedReason(run(AiRunDO.STATUS_FAILED), task(AiRunTaskDO.STATUS_FAILED)))
                .as("失败任务 + 未结束运行：可重试")
                .isNull();
        assertThat(AiRunRetryPolicy.blockedReason(run(AiRunDO.STATUS_ACCEPTED), task(AiRunTaskDO.STATUS_FAILED)))
                .as("受理中但任务已失败：仍可重试")
                .isNull();
    }

    @Test
    void unknownAndRunningAndSucceededAreBlockedWithStableReasons() {
        assertThat(AiRunRetryPolicy.blockedReason(run(AiRunDO.STATUS_FAILED), task(AiRunTaskDO.STATUS_UNKNOWN)))
                .contains("结果未知");
        assertThat(AiRunRetryPolicy.blockedReason(run(AiRunDO.STATUS_FAILED), task(AiRunTaskDO.STATUS_RUNNING)))
                .contains("仍在执行");
        assertThat(AiRunRetryPolicy.blockedReason(run(AiRunDO.STATUS_FAILED), task(AiRunTaskDO.STATUS_SUCCEEDED)))
                .contains("已成功");
        assertThat(AiRunRetryPolicy.blockedReason(run(AiRunDO.STATUS_FAILED), task(AiRunTaskDO.STATUS_QUEUED)))
                .contains("QUEUED");
    }

    @Test
    void finishedRunAndMissingTaskAreBlocked() {
        assertThat(AiRunRetryPolicy.blockedReason(run(AiRunDO.STATUS_SUCCEEDED), task(AiRunTaskDO.STATUS_FAILED)))
                .contains("运行已结束");
        assertThat(AiRunRetryPolicy.blockedReason(run(AiRunDO.STATUS_CANCELLED), task(AiRunTaskDO.STATUS_FAILED)))
                .contains("运行已结束");
        assertThat(AiRunRetryPolicy.blockedReason(run(AiRunDO.STATUS_FAILED), null))
                .contains("缺少可重试的任务");
        assertThat(AiRunRetryPolicy.blockedReason(null, task(AiRunTaskDO.STATUS_FAILED)))
                .as("运行行为空时按未结束处理（查询侧不会给出这种组合）")
                .isNull();
    }
}
