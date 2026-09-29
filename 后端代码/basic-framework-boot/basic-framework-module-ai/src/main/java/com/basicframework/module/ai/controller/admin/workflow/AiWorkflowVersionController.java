package com.basicframework.module.ai.controller.admin.workflow;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowDraftCreateReqVO;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowDraftUpdateReqVO;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowVersionActionReqVO;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowVersionPageReqVO;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowVersionRespVO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowVersionDO;
import com.basicframework.module.ai.service.workflow.AiWorkflowService;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowDraftSaveDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 流程版本接口（X08）：草稿/发布隔离的协议层。
 *
 * <p>版本是不可变快照：只有 DRAFT 可编辑；发布先过结构校验（环/无出口/类型不匹配）与引用核对，
 * 之后任何修改都必须新建草稿。graphJson 在响应里原样返回（受控契约，编辑器据此还原表单）。
 */
@Tag(name = "管理后台 - AI 流程版本")
@RestController
@RequestMapping("/ai/workflow-version")
@Validated
@RequiredArgsConstructor
public class AiWorkflowVersionController {

    private final AiWorkflowService workflowService;

    @PostMapping("/create-draft")
    @Operation(summary = "新建草稿版本（同一流程同时最多一个打开的草稿）")
    @PreAuthorize("@ss.hasPermission('ai:workflow:manage')")
    public CommonResult<Long> createDraft(@Valid @RequestBody AiWorkflowDraftCreateReqVO reqVO) {
        return success(workflowService.createDraft(reqVO.getWorkflowId(), reqVO.getGraphJson()));
    }

    @PutMapping("/update-draft")
    @Operation(summary = "编辑草稿图（只有 DRAFT 可编辑）")
    @PreAuthorize("@ss.hasPermission('ai:workflow:manage')")
    public CommonResult<Boolean> updateDraft(@Valid @RequestBody AiWorkflowDraftUpdateReqVO reqVO) {
        workflowService.updateDraft(new AiWorkflowDraftSaveDTO()
                .setWorkflowId(reqVO.getWorkflowId())
                .setVersionId(reqVO.getVersionId())
                .setGraphJson(reqVO.getGraphJson())
                .setVersion(reqVO.getVersion()));
        return success(true);
    }

    @PutMapping("/publish")
    @Operation(summary = "发布版本（环/无出口/类型不匹配/引用不存在一律拒绝）")
    @PreAuthorize("@ss.hasPermission('ai:workflow:manage')")
    public CommonResult<Long> publishVersion(@Valid @RequestBody AiWorkflowVersionActionReqVO reqVO) {
        return success(workflowService.publishVersion(reqVO.getId(), reqVO.getVersion()));
    }

    @PutMapping("/discard")
    @Operation(summary = "废弃草稿（终态，释放单开草稿）")
    @PreAuthorize("@ss.hasPermission('ai:workflow:manage')")
    public CommonResult<Boolean> discardDraft(@Valid @RequestBody AiWorkflowVersionActionReqVO reqVO) {
        workflowService.discardDraft(reqVO.getId(), reqVO.getVersion());
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获取流程版本（含图 JSON）")
    @Parameter(name = "id", description = "版本编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:workflow:query')")
    public CommonResult<AiWorkflowVersionRespVO> getVersion(@RequestParam("id") @NotNull @Positive Long id) {
        return success(toRespVO(workflowService.getVersion(id)));
    }

    @GetMapping("/open-draft")
    @Operation(summary = "获取当前打开的草稿（无则返回空）")
    @Parameter(name = "workflowId", description = "流程编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:workflow:query')")
    public CommonResult<AiWorkflowVersionRespVO> getOpenDraft(
            @RequestParam("workflowId") @NotNull @Positive Long workflowId) {
        AiWorkflowVersionDO draft = workflowService.getOpenDraft(workflowId);
        return success(draft == null ? null : toRespVO(draft));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询流程版本")
    @PreAuthorize("@ss.hasPermission('ai:workflow:query')")
    public CommonResult<PageResult<AiWorkflowVersionRespVO>> getVersionPage(
            @Valid AiWorkflowVersionPageReqVO pageReqVO) {
        PageResult<AiWorkflowVersionDO> page =
                workflowService.getVersionPage(pageReqVO, pageReqVO.getWorkflowId(), pageReqVO.getStatus());
        return success(new PageResult<>(
                page.getList().stream()
                        .map(AiWorkflowVersionController::toRespVO)
                        .toList(),
                page.getTotal()));
    }

    private static AiWorkflowVersionRespVO toRespVO(AiWorkflowVersionDO version) {
        return new AiWorkflowVersionRespVO()
                .setId(version.getId())
                .setWorkflowId(version.getWorkflowId())
                .setVersionNo(version.getVersionNo())
                .setStatus(version.getStatus())
                .setGraphJson(version.getGraphJson())
                .setGraphHash(version.getGraphHash())
                .setNodeCount(version.getNodeCount())
                .setEdgeCount(version.getEdgeCount())
                .setPublishedAt(version.getPublishedAt())
                .setVersion(version.getVersion())
                .setCreateTime(version.getCreateTime());
    }
}
