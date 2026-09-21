package com.basicframework.module.ai.controller.admin.tool;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.tool.vo.AiToolPageReqVO;
import com.basicframework.module.ai.controller.admin.tool.vo.AiToolRespVO;
import com.basicframework.module.ai.controller.admin.tool.vo.AiToolSaveReqVO;
import com.basicframework.module.ai.controller.admin.tool.vo.AiToolVersionPublishReqVO;
import com.basicframework.module.ai.controller.admin.tool.vo.AiToolVersionRespVO;
import com.basicframework.module.ai.controller.admin.tool.vo.AiToolVersionSaveReqVO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.service.tool.AiToolService;
import com.basicframework.module.ai.service.tool.dto.AiToolSaveDTO;
import com.basicframework.module.ai.service.tool.dto.AiToolVersionSaveDTO;
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
 * AI 工具管理接口（D08）。
 *
 * <p>权限码与 V70 迁移的菜单种子一一对应：查询 {@code ai:tool:query}、新增 {@code ai:tool:create}、
 * 修改/启停 {@code ai:tool:update}、删除 {@code ai:tool:delete}、版本创建与发布 {@code ai:tool:version}。
 *
 * <p>版本是政策与输入输出 schema 的不可变快照：发布后改政策必须新建版本；
 * 首期只允许发布读工具（WRITE 版本发布被拒绝）。
 */
@Tag(name = "管理后台 - AI 工具")
@RestController
@RequestMapping("/ai/tool")
@Validated
@RequiredArgsConstructor
public class AiToolController {

    private final AiToolService toolService;

    @PostMapping("/create")
    @Operation(summary = "新增工具（绑定连接器；标识创建后不可修改）")
    @PreAuthorize("@ss.hasPermission('ai:tool:create')")
    public CommonResult<Long> create(@Valid @RequestBody AiToolSaveReqVO reqVO) {
        return success(toolService.create(toSaveDTO(reqVO)));
    }

    @PutMapping("/update")
    @Operation(summary = "修改工具（只允许名称与说明）")
    @PreAuthorize("@ss.hasPermission('ai:tool:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody AiToolSaveReqVO reqVO) {
        toolService.update(toSaveDTO(reqVO));
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "启用/停用工具（停用后不可执行）")
    @PreAuthorize("@ss.hasPermission('ai:tool:update')")
    public CommonResult<Boolean> updateStatus(
            @Parameter(description = "工具编号", required = true) @RequestParam("id") @NotNull @Positive Long id,
            @Parameter(description = "乐观锁版本", required = true) @RequestParam("version") @NotNull @PositiveOrZero
                    Integer version,
            @Parameter(description = "是否启用", required = true) @RequestParam("enabled") @NotNull Boolean enabled) {
        toolService.updateStatus(id, version, enabled);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除工具（被服务/步骤引用时拒绝）")
    @PreAuthorize("@ss.hasPermission('ai:tool:delete')")
    public CommonResult<Boolean> delete(
            @Parameter(description = "工具编号", required = true) @RequestParam("id") @NotNull @Positive Long id,
            @Parameter(description = "乐观锁版本", required = true) @RequestParam("version") @NotNull @PositiveOrZero
                    Integer version) {
        toolService.delete(id, version);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "查询工具")
    @PreAuthorize("@ss.hasPermission('ai:tool:query')")
    public CommonResult<AiToolRespVO> get(
            @Parameter(description = "工具编号", required = true) @RequestParam("id") @NotNull @Positive Long id) {
        return success(toRespVO(toolService.getTool(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询工具")
    @PreAuthorize("@ss.hasPermission('ai:tool:query')")
    public CommonResult<PageResult<AiToolRespVO>> page(@Valid AiToolPageReqVO pageReqVO) {
        PageResult<AiToolDO> page =
                toolService.getToolPage(pageReqVO, pageReqVO.getConnectorId(), pageReqVO.getStatus());
        return success(new PageResult<>(
                page.getList().stream().map(AiToolController::toRespVO).toList(), page.getTotal()));
    }

    @PostMapping("/version/create")
    @Operation(summary = "创建工具版本草稿（政策缺省 DENY）")
    @PreAuthorize("@ss.hasPermission('ai:tool:version')")
    public CommonResult<Long> createVersion(@Valid @RequestBody AiToolVersionSaveReqVO reqVO) {
        return success(toolService.createVersion(new AiToolVersionSaveDTO()
                .setToolId(reqVO.getToolId())
                .setToolType(reqVO.getToolType())
                .setPolicy(reqVO.getPolicy())
                .setSourceKind(reqVO.getSourceKind())
                .setSourceRef(reqVO.getSourceRef())
                .setInputSchemaJson(reqVO.getInputSchemaJson())
                .setOutputSchemaJson(reqVO.getOutputSchemaJson())));
    }

    @PostMapping("/version/publish")
    @Operation(summary = "发布工具版本（首期只允许读工具；来源 operation 必须已发布）")
    @PreAuthorize("@ss.hasPermission('ai:tool:version')")
    public CommonResult<Boolean> publishVersion(@Valid @RequestBody AiToolVersionPublishReqVO reqVO) {
        toolService.publishVersion(reqVO.getVersionId(), reqVO.getVersion());
        return success(true);
    }

    @GetMapping("/version/get")
    @Operation(summary = "查询工具版本")
    @PreAuthorize("@ss.hasPermission('ai:tool:query')")
    public CommonResult<AiToolVersionRespVO> getVersion(
            @Parameter(description = "版本编号", required = true) @RequestParam("versionId") @NotNull @Positive
                    Long versionId) {
        return success(toVersionRespVO(toolService.getVersion(versionId)));
    }

    @GetMapping("/version/page")
    @Operation(summary = "分页查询工具版本")
    @PreAuthorize("@ss.hasPermission('ai:tool:query')")
    public CommonResult<PageResult<AiToolVersionRespVO>> versionPage(
            @Parameter(description = "工具编号", required = true) @RequestParam("toolId") @NotNull @Positive Long toolId,
            @Valid AiToolPageReqVO pageReqVO) {
        PageResult<AiToolVersionDO> page = toolService.getVersionPage(toolId, pageReqVO);
        return success(new PageResult<>(
                page.getList().stream().map(AiToolController::toVersionRespVO).toList(), page.getTotal()));
    }

    private static AiToolSaveDTO toSaveDTO(AiToolSaveReqVO reqVO) {
        return new AiToolSaveDTO()
                .setId(reqVO.getId())
                .setCode(reqVO.getCode())
                .setName(reqVO.getName())
                .setDescription(reqVO.getDescription())
                .setConnectorId(reqVO.getConnectorId())
                .setVersion(reqVO.getVersion());
    }

    private static AiToolRespVO toRespVO(AiToolDO tool) {
        return new AiToolRespVO()
                .setId(tool.getId())
                .setCode(tool.getCode())
                .setName(tool.getName())
                .setDescription(tool.getDescription())
                .setConnectorId(tool.getConnectorId())
                .setStatus(tool.getStatus())
                .setLatestVersionNo(tool.getLatestVersionNo())
                .setVersion(tool.getVersion())
                .setCreateTime(tool.getCreateTime());
    }

    private static AiToolVersionRespVO toVersionRespVO(AiToolVersionDO version) {
        return new AiToolVersionRespVO()
                .setId(version.getId())
                .setToolId(version.getToolId())
                .setVersionNo(version.getVersionNo())
                .setStatus(version.getStatus())
                .setToolType(version.getToolType())
                .setPolicy(version.getPolicy())
                .setSourceKind(version.getSourceKind())
                .setSourceRef(version.getSourceRef())
                .setInputSchemaJson(version.getInputSchemaJson())
                .setOutputSchemaJson(version.getOutputSchemaJson())
                .setSchemaHash(version.getSchemaHash())
                .setPublishedAt(version.getPublishedAt())
                .setVersion(version.getVersion());
    }
}
