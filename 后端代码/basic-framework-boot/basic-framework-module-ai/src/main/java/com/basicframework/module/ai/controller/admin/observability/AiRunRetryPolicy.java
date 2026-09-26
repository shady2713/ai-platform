package com.basicframework.module.ai.controller.admin.observability;

import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;

/**
 * 人工重试判据（Q03）：与 O06 的按主体重试**同一判据**。
 *
 * <p>为什么两处都有：Q03 的授权范围只含 `controller/admin/observability`，不能改
 * `service/task`；而管理端跨主体重试也不能走按主体过滤的 O06 服务（管理端没有应用主体会话）。
 * 因此判据在这里以纯函数实现，并与 O06 保持一致；两条规则的等价性由
 * {@code AiRunRetryPolicyTest} 的规则矩阵固定。若后续卡放开 `service/task`，
 * 应合并为单一权威策略（见本卡证据的未验证项）。
 */
final class AiRunRetryPolicy {

    private AiRunRetryPolicy() {}

    /** 不可重试的原因；返回 null 表示可重试。 */
    static String blockedReason(AiRunDO run, AiRunTaskDO task) {
        if (task == null) {
            return "缺少可重试的任务";
        }
        String taskStatus = task.getStatus();
        if (AiRunTaskDO.STATUS_UNKNOWN.equals(taskStatus)) {
            // 结果未知：重复执行可能产生第二份副作用，必须人工核对后新建运行
            return "结果未知（UNKNOWN），请核对后新建运行";
        }
        if (AiRunTaskDO.STATUS_RUNNING.equals(taskStatus)) {
            return "任务仍在执行（或租约未过期）";
        }
        if (AiRunTaskDO.STATUS_SUCCEEDED.equals(taskStatus)) {
            return "任务已成功";
        }
        if (!AiRunTaskDO.STATUS_FAILED.equals(taskStatus)) {
            return "任务状态为 " + taskStatus;
        }
        String runStatus = run == null ? null : run.getStatus();
        if (AiRunDO.STATUS_SUCCEEDED.equals(runStatus) || AiRunDO.STATUS_CANCELLED.equals(runStatus)) {
            return "运行已结束（" + runStatus + "）";
        }
        return null;
    }
}
