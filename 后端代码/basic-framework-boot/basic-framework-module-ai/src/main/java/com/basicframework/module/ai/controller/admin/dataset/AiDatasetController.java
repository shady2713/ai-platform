package com.basicframework.module.ai.controller.admin.dataset;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.dataset.vo.AiDatasetPageReqVO;
import com.basicframework.module.ai.controller.admin.dataset.vo.AiDatasetRespVO;
import com.basicframework.module.ai.controller.admin.dataset.vo.AiDatasetSaveReqVO;
import com.basicframework.module.ai.controller.admin.dataset.vo.AiDatasetVersionRespVO;
import com.basicframework.module.ai.controller.admin.dataset.vo.AiDatasetVersionSaveReqVO;
import com.basicframework.module.ai.controller.admin.dataset.vo.AiDatasetVersionVerifyReqVO;
import com.basicframework.module.ai.controller.admin.dataset.vo.AiDatasetVersionVerifyRespVO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetVersionDO;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetSaveDTO;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetVersionSaveDTO;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetVersionVerifyResultDTO;
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
 * AI 语义数据集管理接口（D04）。
 *
 * <p>权限码与 V68 迁移的菜单种子一一对应：查询 {@code ai:dataset:query}、新增 {@code ai:dataset:create}、
 * 修改/启停 {@code ai:dataset:update}、删除 {@code ai:dataset:delete}、
 * 创建版本 {@code ai:dataset:version:create}、验证版本 {@code ai:dataset:version:verify}、
 * 发布版本 {@code ai:dataset:version:publish}。
 *
 * <p>响应只含来源对象、定义快照与漂移**列名**，不含任何上游数据值。
 */
@Tag(name = "管理后台 - AI 数据集")
@RestController
@RequestMapping("/ai/dataset")
@Validated
@RequiredArgsConstructor
public class AiDatasetController {

    private final AiDatasetService datasetService;

    @PostMapping("/create")
    @Operation(summary = "新增数据集（来源对象必须在该连接器授权白名单内）")
    @PreAuthorize("@ss.hasPermission('ai:dataset:create')")
    public CommonResult<Long> create(@Valid @RequestBody AiDatasetSaveReqVO reqVO) {
        return success(datasetService.create(toSaveDTO(reqVO)));
    }

    @PutMapping("/update")
    @Operation(summary = "修改数据集（标识与来源不可修改）")
    @PreAuthorize("@ss.hasPermission('ai:dataset:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody AiDatasetSaveReqVO reqVO) {
        datasetService.update(toSaveDTO(reqVO));
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "启用/停用数据集（停用后不能新建/验证/发布版本）")
    @PreAuthorize("@ss.hasPermission('ai:dataset:update')")
    public CommonResult<Boolean> updateStatus(
            @Parameter(description = "数据集编号", required = true) @RequestParam("id") @NotNull @Positive Long id,
            @Parameter(description = "乐观锁版本", required = true) @RequestParam("version") @NotNull @PositiveOrZero
                    Integer version,
            @Parameter(description = "是否启用", required = true) @RequestParam("enabled") @NotNull Boolean enabled) {
        datasetService.updateStatus(id, version, enabled);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除数据集（版本被报表引用时拒绝；版本行保留以维持可追溯）")
    @PreAuthorize("@ss.hasPermission('ai:dataset:delete')")
    public CommonResult<Boolean> delete(
            @Parameter(description = "数据集编号", required = true) @RequestParam("id") @NotNull @Positive Long id,
            @Parameter(description = "乐观锁版本", required = true) @RequestParam("version") @NotNull @PositiveOrZero
                    Integer version) {
        datasetService.delete(id, version);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "查询数据集")
    @PreAuthorize("@ss.hasPermission('ai:dataset:query')")
    public CommonResult<AiDatasetRespVO> get(
            @Parameter(description = "数据集编号", required = true) @RequestParam("id") @NotNull @Positive Long id) {
        return success(toRespVO(datasetService.getDataset(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询数据集")
    @PreAuthorize("@ss.hasPermission('ai:dataset:query')")
    public CommonResult<PageResult<AiDatasetRespVO>> page(@Valid AiDatasetPageReqVO pageReqVO) {
        PageResult<AiDatasetDO> page =
                datasetService.getDatasetPage(pageReqVO, pageReqVO.getConnectorId(), pageReqVO.getStatus());
        return success(new PageResult<>(
                page.getList().stream().map(AiDatasetController::toRespVO).toList(), page.getTotal()));
    }

    @PostMapping("/version/create")
    @Operation(summary = "创建语义版本草稿（定义校验 + schemaHash）")
    @PreAuthorize("@ss.hasPermission('ai:dataset:version:create')")
    public CommonResult<Long> createVersion(@Valid @RequestBody AiDatasetVersionSaveReqVO reqVO) {
        return success(datasetService.createVersion(new AiDatasetVersionSaveDTO()
                .setDatasetId(reqVO.getDatasetId())
                .setDefinitionJson(reqVO.getDefinitionJson())));
    }

    @PostMapping("/version/verify")
    @Operation(summary = "验证语义版本（与上游结构比对；漂移则置待验证）")
    @PreAuthorize("@ss.hasPermission('ai:dataset:version:verify')")
    public CommonResult<AiDatasetVersionVerifyRespVO> verifyVersion(
            @Valid @RequestBody AiDatasetVersionVerifyReqVO reqVO) {
        return success(toVerifyRespVO(datasetService.verifyVersion(reqVO.getVersionId(), reqVO.getVersion())));
    }

    @PostMapping("/version/publish")
    @Operation(summary = "发布语义版本（要求已验证且上游结构自验证以来未变化）")
    @PreAuthorize("@ss.hasPermission('ai:dataset:version:publish')")
    public CommonResult<AiDatasetVersionVerifyRespVO> publishVersion(
            @Valid @RequestBody AiDatasetVersionVerifyReqVO reqVO) {
        return success(toVerifyRespVO(datasetService.publishVersion(reqVO.getVersionId(), reqVO.getVersion())));
    }

    @GetMapping("/version/get")
    @Operation(summary = "查询语义版本（含定义快照，旧报表可追溯）")
    @PreAuthorize("@ss.hasPermission('ai:dataset:query')")
    public CommonResult<AiDatasetVersionRespVO> getVersion(
            @Parameter(description = "版本编号", required = true) @RequestParam("versionId") @NotNull @Positive
                    Long versionId) {
        return success(toVersionRespVO(datasetService.getVersion(versionId)));
    }

    @GetMapping("/version/page")
    @Operation(summary = "分页查询语义版本")
    @PreAuthorize("@ss.hasPermission('ai:dataset:query')")
    public CommonResult<PageResult<AiDatasetVersionRespVO>> versionPage(
            @Parameter(description = "数据集编号", required = true) @RequestParam("datasetId") @NotNull @Positive
                    Long datasetId,
            @Valid AiDatasetPageReqVO pageReqVO) {
        PageResult<AiDatasetVersionDO> page = datasetService.getVersionPage(datasetId, pageReqVO);
        return success(new PageResult<>(
                page.getList().stream()
                        .map(AiDatasetController::toVersionRespVO)
                        .toList(),
                page.getTotal()));
    }

    private static AiDatasetSaveDTO toSaveDTO(AiDatasetSaveReqVO reqVO) {
        return new AiDatasetSaveDTO()
                .setId(reqVO.getId())
                .setCode(reqVO.getCode())
                .setName(reqVO.getName())
                .setDescription(reqVO.getDescription())
                .setConnectorId(reqVO.getConnectorId())
                .setSourceObject(reqVO.getSourceObject())
                .setVersion(reqVO.getVersion());
    }

    private static AiDatasetRespVO toRespVO(AiDatasetDO dataset) {
        return new AiDatasetRespVO()
                .setId(dataset.getId())
                .setCode(dataset.getCode())
                .setName(dataset.getName())
                .setDescription(dataset.getDescription())
                .setConnectorId(dataset.getConnectorId())
                .setSourceObject(dataset.getSourceObject())
                .setStatus(dataset.getStatus())
                .setLatestVersionNo(dataset.getLatestVersionNo())
                .setPublishedVersionNo(dataset.getPublishedVersionNo())
                .setVersion(dataset.getVersion())
                .setCreateTime(dataset.getCreateTime());
    }

    private static AiDatasetVersionRespVO toVersionRespVO(AiDatasetVersionDO version) {
        return new AiDatasetVersionRespVO()
                .setId(version.getId())
                .setDatasetId(version.getDatasetId())
                .setVersionNo(version.getVersionNo())
                .setStatus(version.getStatus())
                .setDefinitionJson(version.getDefinitionJson())
                .setSchemaHash(version.getSchemaHash())
                .setSourceSchemaHash(version.getSourceSchemaHash())
                .setVerificationStatus(version.getVerificationStatus())
                .setDriftJson(version.getDriftJson())
                .setVerifiedAt(version.getVerifiedAt())
                .setPublishedAt(version.getPublishedAt())
                .setVersion(version.getVersion());
    }

    private static AiDatasetVersionVerifyRespVO toVerifyRespVO(AiDatasetVersionVerifyResultDTO result) {
        return new AiDatasetVersionVerifyRespVO()
                .setVersionId(result.getVersionId())
                .setVersionNo(result.getVersionNo())
                .setStatus(result.getStatus())
                .setVerificationStatus(result.getVerificationStatus())
                .setMissingColumns(result.getMissingColumns())
                .setTypeChangedColumns(result.getTypeChangedColumns())
                .setAddedColumns(result.getAddedColumns())
                .setSchemaHash(result.getSchemaHash())
                .setSourceSchemaHash(result.getSourceSchemaHash())
                .setPublishable(result.isPublishable());
    }
}
