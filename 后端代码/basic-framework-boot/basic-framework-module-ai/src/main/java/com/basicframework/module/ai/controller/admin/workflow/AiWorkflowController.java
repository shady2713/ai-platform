package com.basicframework.module.ai.controller.admin.workflow;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowPageReqVO;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowRespVO;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowSaveReqVO;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowStatusReqVO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowDO;
import com.basicframework.module.ai.service.workflow.AiWorkflowService;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowSaveDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 流程定义管理接口（X08）。
 *
 * <p>流程是按应用的配置面：应用内标识唯一、创建后不可修改；停用后不受理新运行。
 * 权限码与 V89 迁移的 system_menu 种子一一对应（{@code ai:workflow:query|manage|delete}）。
 */
@Tag(name = "管理后台 - AI 流程编排")
@RestController
@RequestMapping("/ai/workflow")
@Validated
@RequiredArgsConstructor
public class AiWorkflowController {

    private final AiWorkflowService workflowService;

    @PostMapping("/create")
    @Operation(summary = "创建流程定义（应用内标识唯一）")
    @PreAuthorize("@ss.hasPermission('ai:workflow:manage')")
    public CommonResult<Long> createWorkflow(@Valid @RequestBody AiWorkflowSaveReqVO createReqVO) {
        return success(workflowService.createWorkflow(toSaveDTO(createReqVO)));
    }

    @PutMapping("/update")
    @Operation(summary = "修改流程定义（标识不可修改）")
    @PreAuthorize("@ss.hasPermission('ai:workflow:manage')")
    public CommonResult<Boolean> updateWorkflow(@Valid @RequestBody AiWorkflowSaveReqVO updateReqVO) {
        workflowService.updateWorkflow(toSaveDTO(updateReqVO));
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "启用/停用流程定义（停用后不受理新运行）")
    @PreAuthorize("@ss.hasPermission('ai:workflow:manage')")
    public CommonResult<Boolean> updateWorkflowStatus(@Valid @RequestBody AiWorkflowStatusReqVO reqVO) {
        workflowService.updateStatus(reqVO.getId(), reqVO.getVersion(), reqVO.getEnabled());
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除流程定义（软删除）")
    @PreAuthorize("@ss.hasPermission('ai:workflow:delete')")
    public CommonResult<Boolean> deleteWorkflow(
            @RequestParam("id") @NotNull @Positive Long id,
            @RequestParam("version") @NotNull @PositiveOrZero Integer version) {
        workflowService.deleteWorkflow(id, version);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获取流程定义")
    @Parameter(name = "id", description = "流程编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:workflow:query')")
    public CommonResult<AiWorkflowRespVO> getWorkflow(@RequestParam("id") @NotNull @Positive Long id) {
        return success(toRespVO(workflowService.getWorkflow(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询流程定义")
    @PreAuthorize("@ss.hasPermission('ai:workflow:query')")
    public CommonResult<PageResult<AiWorkflowRespVO>> getWorkflowPage(@Valid AiWorkflowPageReqVO pageReqVO) {
        PageResult<AiWorkflowDO> page = workflowService.getWorkflowPage(
                pageReqVO, pageReqVO.getApplicationId(), pageReqVO.getCode(), pageReqVO.getStatus());
        return success(new PageResult<>(
                page.getList().stream().map(AiWorkflowController::toRespVO).toList(), page.getTotal()));
    }

    private static AiWorkflowSaveDTO toSaveDTO(AiWorkflowSaveReqVO reqVO) {
        return new AiWorkflowSaveDTO()
                .setId(reqVO.getId())
                .setApplicationId(reqVO.getApplicationId())
                .setCode(reqVO.getCode())
                .setName(reqVO.getName())
                .setDescription(reqVO.getDescription())
                .setVersion(reqVO.getVersion());
    }

    private static AiWorkflowRespVO toRespVO(AiWorkflowDO workflow) {
        return new AiWorkflowRespVO()
                .setId(workflow.getId())
                .setApplicationId(workflow.getApplicationId())
                .setCode(workflow.getCode())
                .setName(workflow.getName())
                .setDescription(workflow.getDescription())
                .setStatus(workflow.getStatus())
                .setLatestVersionNo(workflow.getLatestVersionNo())
                .setVersion(workflow.getVersion())
                .setCreateTime(workflow.getCreateTime());
    }
}
