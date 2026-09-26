package com.basicframework.module.ai.controller.admin.observability;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.observability.vo.AiRunMonitorDetailRespVO;
import com.basicframework.module.ai.controller.admin.observability.vo.AiRunMonitorPageReqVO;
import com.basicframework.module.ai.controller.admin.observability.vo.AiRunMonitorRespVO;
import com.basicframework.module.ai.controller.admin.observability.vo.AiRunRetryReqVO;
import com.basicframework.module.ai.controller.admin.observability.vo.AiRunTimelineRespVO;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationMessageDO;
import com.basicframework.module.ai.dal.dataobject.event.AiRunEventDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;
import com.basicframework.module.ai.dal.dataobject.usage.AiUsageLedgerDO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维运行监控接口（Q03）。
 *
 * <p>与用量控制面（Q02 的 {@code /ai/usage/**}）配合使用：本接口回答"跑到哪一步、失败在哪、
 * 慢在哪里、能不能重试"，用量接口回答"花了多少"。两者都只回**标识与计量元数据**：
 * 主体只有类型（不回外部用户标识明文）、模型端点只有编号与配置修订、事件块正文不进列表
 * （AT-011：后台普通管理员看不到秘密与正文）。
 *
 * <p>查询需要 {@code ai:observability:query}，重试是另一个权限点 {@code ai:observability:retry}：
 * 看与做分别鉴权，界面按钮的可见性由同一批权限码决定（无权限时后端仍会拒绝）。
 */
@Tag(name = "管理后台 - AI 运行监控")
@RestController
@RequestMapping("/ai/observability")
@Validated
@RequiredArgsConstructor
public class AiObservabilityController {

    private final AiRunMonitorQuery monitorQuery;

    private final AiRunRetryCommand retryCommand;

    @GetMapping("/run/page")
    @Operation(summary = "运行监控分页（按应用/服务/状态/主体/时间窗筛选；服务端过滤后分页）")
    @PreAuthorize("@ss.hasPermission('ai:observability:query')")
    public CommonResult<PageResult<AiRunMonitorRespVO>> page(@Valid AiRunMonitorPageReqVO reqVO) {
        PageResult<AiRunDO> page = monitorQuery.pageRuns(reqVO);
        Map<Long, AiRunTaskDO> tasks = monitorQuery.findStepTasks(
                page.getList().stream().map(AiRunDO::getId).toList());
        return success(new PageResult<>(
                page.getList().stream()
                        .map(run -> toRow(run, tasks.get(run.getId())))
                        .toList(),
                page.getTotal()));
    }

    @GetMapping("/run/get")
    @Operation(summary = "运行详情（步骤、失败原因码、耗时分解与可重试性；同一判据驱动重试入口）")
    @Parameter(name = "runId", description = "运行编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:observability:query')")
    public CommonResult<AiRunMonitorDetailRespVO> get(@RequestParam("runId") @NotNull @Positive Long runId) {
        AiRunDO run = monitorQuery.requireRun(runId);
        AiRunTaskDO task = monitorQuery.findStepTask(run.getId());
        AiConversationMessageDO result = monitorQuery.resultMessage(run);
        List<AiUsageLedgerDO> usages = monitorQuery.usages(run.getId());
        String blocked = AiRunRetryPolicy.blockedReason(run, task);
        return success(new AiRunMonitorDetailRespVO()
                .setRunId(run.getId())
                .setRunKey(run.getRunKey())
                .setApplicationId(run.getApplicationId())
                .setServiceId(run.getServiceId())
                .setReleaseId(run.getReleaseId())
                .setSubjectType(run.getSubjectType())
                .setStatus(run.getStatus())
                .setStepCount(run.getStepCount())
                .setLatestSeq(run.getEventSeq() == null ? 0 : run.getEventSeq())
                .setDataLevel(run.getDataLevel())
                .setModelEndpointId(run.getModelEndpointId())
                .setEndpointConfigRevision(run.getEndpointConfigRevision())
                .setContentHash(run.getContentHash())
                .setConversationId(run.getConversationId())
                .setResultMessageId(result == null ? null : result.getId())
                .setResultDigest(result == null ? null : result.getContentHash())
                .setTaskId(task == null ? null : task.getId())
                .setTaskKind(task == null ? null : task.getTaskKind())
                .setTaskStatus(task == null ? null : task.getStatus())
                .setAttemptCount(task == null ? null : task.getAttemptCount())
                .setNextAttemptTime(task == null ? null : task.getNextAttemptTime())
                .setLastErrorCode(task == null ? null : task.getLastErrorCode())
                .setRunVersion(run.getVersion())
                .setTiming(AiRunTiming.of(run, usages, LocalDateTime.now()))
                .setRetryable(blocked == null)
                .setRetryBlockedReason(blocked)
                .setCreateTime(run.getCreateTime())
                .setUpdateTime(run.getUpdateTime()));
    }

    @GetMapping("/run/timeline")
    @Operation(summary = "运行事件时间线（有界；默认不展开事件块正文，只给块类型与有无）")
    @Parameter(name = "runId", description = "运行编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:observability:query')")
    public CommonResult<List<AiRunTimelineRespVO>> timeline(
            @RequestParam("runId") @NotNull @Positive Long runId,
            @Parameter(description = "起始序号（不含；从该序号之后取）") @RequestParam(value = "afterSeq", required = false)
                    Integer afterSeq,
            @Parameter(description = "单次上限（默认 100，最大 200）")
                    @RequestParam(value = "limit", required = false, defaultValue = "100")
                    Integer limit) {
        monitorQuery.requireRun(runId);
        return success(monitorQuery.timeline(runId, afterSeq, limit).stream()
                .map(AiObservabilityController::toTimeline)
                .toList());
    }

    @PostMapping("/run/retry")
    @Operation(summary = "人工重试（只允许原任务可重试类型：结果未知或仍在执行一律拒绝）")
    @PreAuthorize("@ss.hasPermission('ai:observability:retry')")
    public CommonResult<Boolean> retry(@Valid @RequestBody AiRunRetryReqVO reqVO) {
        retryCommand.retry(reqVO.getRunId(), reqVO.getVersion());
        return success(true);
    }

    private static AiRunMonitorRespVO toRow(AiRunDO run, AiRunTaskDO task) {
        String blocked = AiRunRetryPolicy.blockedReason(run, task);
        return new AiRunMonitorRespVO()
                .setRunId(run.getId())
                .setRunKey(run.getRunKey())
                .setApplicationId(run.getApplicationId())
                .setServiceId(run.getServiceId())
                .setReleaseId(run.getReleaseId())
                .setSubjectType(run.getSubjectType())
                .setStatus(run.getStatus())
                .setStepCount(run.getStepCount())
                .setLatestSeq(run.getEventSeq() == null ? 0 : run.getEventSeq())
                .setTaskStatus(task == null ? null : task.getStatus())
                .setAttemptCount(task == null ? null : task.getAttemptCount())
                .setLastErrorCode(task == null ? null : task.getLastErrorCode())
                .setRetryable(blocked == null)
                .setRetryBlockedReason(blocked)
                .setCreateTime(run.getCreateTime())
                .setUpdateTime(run.getUpdateTime());
    }

    private static AiRunTimelineRespVO toTimeline(AiRunEventDO event) {
        return new AiRunTimelineRespVO()
                .setSeq(event.getSeq())
                .setStatus(event.getStatus())
                .setBlockType(StringUtils.hasText(event.getBlockType()) ? event.getBlockType() : null)
                .setSchemaVersion(event.getSchemaVersion())
                .setBlockPresent(StringUtils.hasText(event.getBlockJson()))
                .setCreateTime(event.getCreateTime());
    }
}
