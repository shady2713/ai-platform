package com.basicframework.module.ai.controller.app.v1.task;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.task.vo.AiTaskPageReqVO;
import com.basicframework.module.ai.controller.app.v1.task.vo.AiTaskProgressRespVO;
import com.basicframework.module.ai.controller.app.v1.task.vo.AiTaskRetryReqVO;
import com.basicframework.module.ai.service.task.AiTaskService;
import com.basicframework.module.ai.service.task.dto.AiRunProgressDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 运行任务查询与人工重试接口（O06）。
 *
 * <p>查询按当前主体过滤（越权与不存在同语义），结果只给标识与摘要；
 * 人工重试是显式动作：先按当前权限重建身份，再校验任务是否可重试——
 * {@code UNKNOWN}（结果未知）任务拒绝普通重试，重复执行可能产生第二份副作用。
 * 事件重放与取消见 O05，清理由保留期 Job 承担（不在应用端开放）。
 */
@Tag(name = "应用端 - AI 运行任务")
@RestController
@RequestMapping("/ai/task")
@Validated
@RequiredArgsConstructor
public class AiTaskController {

    private final AiTaskService taskService;

    @GetMapping("/progress")
    @Operation(summary = "运行进度与结果引用（按当前主体过滤；越权与不存在同语义）")
    @Parameter(name = "runId", description = "运行编号", required = true)
    @AuthenticatedOnly
    public CommonResult<AiTaskProgressRespVO> progress(@RequestParam("runId") @NotNull @Positive Long runId) {
        return success(toRespVO(taskService.progress(runId)));
    }

    @GetMapping("/page")
    @Operation(summary = "当前主体的运行进度分页（按编号倒序）")
    @AuthenticatedOnly
    public CommonResult<PageResult<AiTaskProgressRespVO>> page(@Valid AiTaskPageReqVO reqVO) {
        PageResult<AiRunProgressDTO> page = taskService.pageProgress(reqVO);
        return success(new PageResult<>(
                page.getList().stream().map(AiTaskController::toRespVO).collect(Collectors.toList()), page.getTotal()));
    }

    @PostMapping("/retry")
    @Operation(summary = "人工重试（校验当前权限与可重试性；UNKNOWN 任务拒绝普通重试）")
    @AuthenticatedOnly
    public CommonResult<Boolean> retry(@Valid @RequestBody AiTaskRetryReqVO reqVO) {
        taskService.retry(reqVO.getRunId(), reqVO.getVersion());
        return success(true);
    }

    private static AiTaskProgressRespVO toRespVO(AiRunProgressDTO progress) {
        return new AiTaskProgressRespVO()
                .setRunId(progress.getRunId())
                .setRunKey(progress.getRunKey())
                .setStatus(progress.getStatus())
                .setStepCount(progress.getStepCount())
                .setLatestSeq(progress.getLatestSeq())
                .setConversationId(progress.getConversationId())
                .setResultMessageId(progress.getResultMessageId())
                .setResultDigest(progress.getResultDigest())
                .setTaskStatus(progress.getTaskStatus())
                .setAttemptCount(progress.getAttemptCount())
                .setNextAttemptTime(progress.getNextAttemptTime())
                .setLastErrorCode(progress.getLastErrorCode())
                .setRetryable(progress.isRetryable())
                .setRetryBlockedReason(progress.getRetryBlockedReason())
                .setCreateTime(progress.getCreateTime());
    }
}
