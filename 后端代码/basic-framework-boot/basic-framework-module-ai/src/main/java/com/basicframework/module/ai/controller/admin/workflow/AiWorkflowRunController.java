package com.basicframework.module.ai.controller.admin.workflow;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowRunAcceptReqVO;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowRunNodeRespVO;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowRunPageReqVO;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowRunRespVO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowRunDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowRunNodeDO;
import com.basicframework.module.ai.service.workflow.AiWorkflowRunService;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowRunAcceptDTO;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowRunResultDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 流程受控运行接口（X08）。
 *
 * <p>受理即固定最新已发布版本并**同步**执行（预算有界），响应带终态与逐节点事实
 * （步骤可视化与失败定位）。需要人工确认的工具节点在流程里不会执行——运行以稳定错误码
 * 受控结束（复用 X06 的 {@code AI_TOOL_CONFIRMATION_REQUIRED}），写副作用只能经既有确认链路。
 */
@Tag(name = "管理后台 - AI 流程运行")
@RestController
@RequestMapping("/ai/workflow-run")
@Validated
@RequiredArgsConstructor
public class AiWorkflowRunController {

    private final AiWorkflowRunService runService;

    @PostMapping("/accept")
    @Operation(summary = "受理并执行流程运行（幂等；固定已发布版本；预算受控）")
    @PreAuthorize("@ss.hasPermission('ai:workflow:run')")
    public CommonResult<AiWorkflowRunRespVO> acceptRun(@Valid @RequestBody AiWorkflowRunAcceptReqVO reqVO) {
        AiWorkflowRunResultDTO result = runService.accept(new AiWorkflowRunAcceptDTO()
                .setWorkflowId(reqVO.getWorkflowId())
                .setIdempotencyKey(reqVO.getIdempotencyKey())
                .setDataLevel(reqVO.getDataLevel())
                .setInputText(reqVO.getInputText())
                .setMaxSteps(reqVO.getMaxSteps())
                .setMaxDurationMillis(reqVO.getMaxDurationMillis()));
        return success(toRespVO(result));
    }

    @GetMapping("/get")
    @Operation(summary = "获取流程运行")
    @Parameter(name = "id", description = "运行编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:workflow:query')")
    public CommonResult<AiWorkflowRunRespVO> getRun(@RequestParam("id") @NotNull @Positive Long id) {
        return success(toRespVO(runService.getRun(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询流程运行")
    @PreAuthorize("@ss.hasPermission('ai:workflow:query')")
    public CommonResult<PageResult<AiWorkflowRunRespVO>> getRunPage(@Valid AiWorkflowRunPageReqVO pageReqVO) {
        PageResult<AiWorkflowRunDO> page =
                runService.getRunPage(pageReqVO, pageReqVO.getWorkflowId(), pageReqVO.getStatus());
        return success(new PageResult<>(
                page.getList().stream().map(AiWorkflowRunController::toRespVO).toList(), page.getTotal()));
    }

    @GetMapping("/node-list")
    @Operation(summary = "获取运行的节点留痕（按执行顺序）")
    @Parameter(name = "runId", description = "运行编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:workflow:query')")
    public CommonResult<List<AiWorkflowRunNodeRespVO>> getNodeList(
            @RequestParam("runId") @NotNull @Positive Long runId) {
        return success(runService.getRunNodes(runId).stream()
                .map(AiWorkflowRunController::toNodeRespVO)
                .toList());
    }

    /** 受理响应：由服务层 DTO（含节点事实）映射；幂等键/时间戳以运行查询接口为准。 */
    private static AiWorkflowRunRespVO toRespVO(AiWorkflowRunResultDTO result) {
        return new AiWorkflowRunRespVO()
                .setId(result.getRunId())
                .setWorkflowId(result.getWorkflowId())
                .setWorkflowVersionId(result.getWorkflowVersionId())
                .setStatus(result.getStatus())
                .setOutputText(result.getOutputText())
                .setErrorCode(result.getErrorCode())
                .setNodeExecuted(result.getNodeExecuted())
                .setNodeTotal(result.getNodeTotal())
                .setDurationMs(result.getDurationMs())
                .setNodes(result.getNodes().stream()
                        .map(node -> new AiWorkflowRunNodeRespVO()
                                .setNodeKey(node.getNodeKey())
                                .setNodeType(node.getNodeType())
                                .setStatus(node.getStatus())
                                .setOutputText(node.getOutputText())
                                .setErrorCode(node.getErrorCode())
                                .setDurationMs(node.getDurationMs()))
                        .toList());
    }

    private static AiWorkflowRunRespVO toRespVO(AiWorkflowRunDO run) {
        return new AiWorkflowRunRespVO()
                .setId(run.getId())
                .setWorkflowId(run.getWorkflowId())
                .setWorkflowVersionId(run.getWorkflowVersionId())
                .setIdempotencyKey(run.getIdempotencyKey())
                .setStatus(run.getStatus())
                .setDataLevel(run.getDataLevel())
                .setOutputText(run.getOutputText())
                .setErrorCode(run.getErrorCode())
                .setCurrentNodeKey(run.getCurrentNodeKey())
                .setNodeExecuted(run.getNodeExecuted())
                .setNodeTotal(run.getNodeTotal())
                .setDurationMs(run.getDurationMs())
                .setStartedTime(run.getStartedTime())
                .setFinishedTime(run.getFinishedTime());
    }

    private static AiWorkflowRunNodeRespVO toNodeRespVO(AiWorkflowRunNodeDO node) {
        return new AiWorkflowRunNodeRespVO()
                .setNodeKey(node.getNodeKey())
                .setNodeType(node.getNodeType())
                .setStatus(node.getStatus())
                .setOutputText(node.getOutputText())
                .setErrorCode(node.getErrorCode())
                .setDurationMs(node.getDurationMs());
    }
}
