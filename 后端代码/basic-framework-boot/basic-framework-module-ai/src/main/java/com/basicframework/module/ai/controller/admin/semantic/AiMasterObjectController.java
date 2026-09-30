package com.basicframework.module.ai.controller.admin.semantic;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterCatalogReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterMappingEntryCreateReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterMappingResolutionRespVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterMappingResolveReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterMappingReverseReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterMappingReverseRespVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterObjectCatalogRespVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterObjectPageReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterObjectRespVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterObjectSaveReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterObjectStatusReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterRevisionDetailRespVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterRevisionDraftReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterRevisionPageReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterRevisionPublishReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterRevisionRespVO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectMappingDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectRevisionDO;
import com.basicframework.module.ai.service.semantic.AiMasterMappingResolver;
import com.basicframework.module.ai.service.semantic.AiMasterObjectCatalogService;
import com.basicframework.module.ai.service.semantic.AiMasterObjectService;
import com.basicframework.module.ai.service.semantic.dto.AiMasterCatalogEntryDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingEntrySaveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingResolutionDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingResolveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingReverseDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingReverseResultDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectCatalogDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectCatalogQueryDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectSaveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterRevisionDetailDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterRevisionDraftDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
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
 * 跨系统主数据映射接口（Y02）。
 *
 * <p>权限边界（与 V93 迁移的 system_menu 种子一一对应）：
 * <ul>
 *   <li>{@code ai:semantic:query}：对象/版本/条目的只读查询，以及**判定**（源键 → 对象）与
 *       **目录发现**（主体能看到哪些系统的映射）——都是只读事实，不改变任何映射内容；</li>
 *   <li>{@code ai:semantic:manage}：登记对象、编辑草稿版本、登记/删除条目与**发布**。
 *       发布是审核动作：发布人必须不同于草稿创建人（服务层校验，请求体不能自报）；
 *       查看权不等于改映射权，因为映射决定了"哪些标识算同一个实体"；</li>
 *   <li>判定与反查都必须显式给出判定时刻与版本号：没有"取最新版本/取服务器当前时间"的省略写法。</li>
 * </ul>
 */
@Tag(name = "管理后台 - AI 主数据映射")
@RestController
@RequestMapping("/ai/semantic")
@Validated
@RequiredArgsConstructor
public class AiMasterObjectController {

    private final AiMasterObjectService masterObjectService;

    private final AiMasterMappingResolver mappingResolver;

    private final AiMasterObjectCatalogService catalogService;

    @PostMapping("/object/create")
    @Operation(summary = "新建企业统一对象")
    @PreAuthorize("@ss.hasPermission('ai:semantic:manage')")
    public CommonResult<Long> createObject(@Valid @RequestBody AiMasterObjectSaveReqVO reqVO) {
        return success(masterObjectService.createObject(new AiMasterObjectSaveDTO()
                .setObjectCode(reqVO.getObjectCode())
                .setObjectName(reqVO.getObjectName())
                .setObjectType(reqVO.getObjectType())
                .setDescription(reqVO.getDescription())));
    }

    @PutMapping("/object/update")
    @Operation(summary = "修改企业统一对象（标识不可改）")
    @PreAuthorize("@ss.hasPermission('ai:semantic:manage')")
    public CommonResult<Boolean> updateObject(@Valid @RequestBody AiMasterObjectSaveReqVO reqVO) {
        masterObjectService.updateObject(new AiMasterObjectSaveDTO()
                .setId(reqVO.getId())
                .setObjectCode(reqVO.getObjectCode())
                .setObjectName(reqVO.getObjectName())
                .setObjectType(reqVO.getObjectType())
                .setDescription(reqVO.getDescription())
                .setVersion(reqVO.getVersion()));
        return success(true);
    }

    @PutMapping("/object/update-status")
    @Operation(summary = "启用/停用企业统一对象（停用后判定阻断）")
    @PreAuthorize("@ss.hasPermission('ai:semantic:manage')")
    public CommonResult<Boolean> updateObjectStatus(@Valid @RequestBody AiMasterObjectStatusReqVO reqVO) {
        masterObjectService.updateObjectStatus(reqVO.getId(), reqVO.getVersion(), reqVO.getEnabled());
        return success(true);
    }

    @GetMapping("/object/get")
    @Operation(summary = "查询企业统一对象详情")
    @Parameter(name = "id", description = "统一对象编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:semantic:query')")
    public CommonResult<AiMasterObjectRespVO> getObject(@RequestParam("id") @NotNull @Positive Long id) {
        return success(toObjectRespVO(masterObjectService.getObject(id)));
    }

    @GetMapping("/object/get-by-code")
    @Operation(summary = "按标识查询企业统一对象")
    @Parameter(name = "objectCode", description = "统一对象标识", required = true)
    @PreAuthorize("@ss.hasPermission('ai:semantic:query')")
    public CommonResult<AiMasterObjectRespVO> getObjectByCode(
            @RequestParam("objectCode") @NotBlank @Size(max = 64) String objectCode) {
        return success(toObjectRespVO(masterObjectService.getObjectByCode(objectCode)));
    }

    @GetMapping("/object/page")
    @Operation(summary = "企业统一对象分页")
    @PreAuthorize("@ss.hasPermission('ai:semantic:query')")
    public CommonResult<PageResult<AiMasterObjectRespVO>> getObjectPage(@Valid AiMasterObjectPageReqVO reqVO) {
        PageResult<AiMasterObjectDO> page =
                masterObjectService.getObjectPage(reqVO, reqVO.getObjectType(), reqVO.getStatus(), reqVO.getKeyword());
        List<AiMasterObjectRespVO> list = page.getList().stream()
                .map(AiMasterObjectController::toObjectRespVO)
                .toList();
        return success(new PageResult<>(list, page.getTotal()));
    }

    @PostMapping("/revision/create")
    @Operation(summary = "新建映射版本草稿（一个对象同时只允许一个草稿）")
    @PreAuthorize("@ss.hasPermission('ai:semantic:manage')")
    public CommonResult<Long> createRevision(@Valid @RequestBody AiMasterRevisionDraftReqVO reqVO) {
        return success(masterObjectService.createRevision(new AiMasterRevisionDraftDTO()
                .setMasterObjectId(reqVO.getMasterObjectId())
                .setValidFrom(reqVO.getValidFrom())
                .setValidTo(reqVO.getValidTo())));
    }

    @PostMapping("/mapping/create")
    @Operation(summary = "在草稿版本里登记源键映射（只认源键，不按同名推断）")
    @PreAuthorize("@ss.hasPermission('ai:semantic:manage')")
    public CommonResult<Long> createMappingEntry(@Valid @RequestBody AiMasterMappingEntryCreateReqVO reqVO) {
        return success(masterObjectService.addMappingEntry(new AiMasterMappingEntrySaveDTO()
                .setMasterObjectId(reqVO.getMasterObjectId())
                .setRevisionNo(reqVO.getRevisionNo())
                .setApplicationId(reqVO.getApplicationId())
                .setEntityType(reqVO.getEntityType())
                .setSourceKey(reqVO.getSourceKey())
                .setSourceName(reqVO.getSourceName())
                .setMatchMethod(reqVO.getMatchMethod())
                .setValidFrom(reqVO.getValidFrom())
                .setValidTo(reqVO.getValidTo())));
    }

    @DeleteMapping("/mapping/delete")
    @Operation(summary = "删除草稿版本里的映射条目（已发布版本不可改）")
    @Parameter(name = "id", description = "条目编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:semantic:manage')")
    public CommonResult<Boolean> deleteMappingEntry(
            @RequestParam("id") @NotNull @Positive Long id, @RequestParam("version") @NotNull Integer version) {
        masterObjectService.removeMappingEntry(id, version);
        return success(true);
    }

    @PutMapping("/revision/publish")
    @Operation(summary = "发布映射版本（发布人必须不同于草稿创建人；冲突阻断）")
    @PreAuthorize("@ss.hasPermission('ai:semantic:manage')")
    public CommonResult<AiMasterRevisionRespVO> publishRevision(
            @Valid @RequestBody AiMasterRevisionPublishReqVO reqVO) {
        return success(toRevisionRespVO(masterObjectService.publishRevision(
                reqVO.getMasterObjectId(), reqVO.getRevisionNo(), reqVO.getVersion())));
    }

    @GetMapping("/revision/get")
    @Operation(summary = "查询映射版本")
    @PreAuthorize("@ss.hasPermission('ai:semantic:query')")
    public CommonResult<AiMasterRevisionRespVO> getRevision(
            @RequestParam("masterObjectId") @NotNull @Positive Long masterObjectId,
            @RequestParam("revisionNo") @NotNull @Positive Long revisionNo) {
        return success(toRevisionRespVO(masterObjectService.getRevision(masterObjectId, revisionNo)));
    }

    @GetMapping("/revision/detail")
    @Operation(summary = "查询映射版本详情（条目 + 冲突预览 + 是否可发布）")
    @PreAuthorize("@ss.hasPermission('ai:semantic:query')")
    public CommonResult<AiMasterRevisionDetailRespVO> getRevisionDetail(
            @RequestParam("masterObjectId") @NotNull @Positive Long masterObjectId,
            @RequestParam("revisionNo") @NotNull @Positive Long revisionNo) {
        return success(toRevisionDetailRespVO(masterObjectService.getRevisionDetail(masterObjectId, revisionNo)));
    }

    @GetMapping("/revision/page")
    @Operation(summary = "映射版本分页")
    @PreAuthorize("@ss.hasPermission('ai:semantic:query')")
    public CommonResult<PageResult<AiMasterRevisionRespVO>> getRevisionPage(@Valid AiMasterRevisionPageReqVO reqVO) {
        PageResult<AiMasterObjectRevisionDO> page =
                masterObjectService.getRevisionPage(reqVO, reqVO.getMasterObjectId(), reqVO.getStatus());
        List<AiMasterRevisionRespVO> list = page.getList().stream()
                .map(AiMasterObjectController::toRevisionRespVO)
                .toList();
        return success(new PageResult<>(list, page.getTotal()));
    }

    @PostMapping("/resolve/object-key")
    @Operation(summary = "按对象 + 显式版本判定源键（版本未发布/过期/冲突一律阻断）")
    @PreAuthorize("@ss.hasPermission('ai:semantic:query')")
    public CommonResult<AiMasterMappingResolutionRespVO> resolveObjectKey(
            @Valid @RequestBody AiMasterMappingResolveReqVO reqVO) {
        return success(toResolutionRespVO(mappingResolver.resolveObjectKey(new AiMasterMappingResolveDTO()
                .setObjectCode(reqVO.getObjectCode())
                .setRevisionNo(reqVO.getRevisionNo())
                .setApplicationId(reqVO.getApplicationId())
                .setEntityType(reqVO.getEntityType())
                .setAsOf(reqVO.getAsOf()))));
    }

    @PostMapping("/resolve/source-key")
    @Operation(summary = "按源键反查统一对象（未登记返回 mapped=false；冲突/过期阻断）")
    @PreAuthorize("@ss.hasPermission('ai:semantic:query')")
    public CommonResult<AiMasterMappingReverseRespVO> resolveSourceKey(
            @Valid @RequestBody AiMasterMappingReverseReqVO reqVO) {
        return success(toReverseRespVO(mappingResolver.resolveSourceKey(new AiMasterMappingReverseDTO()
                .setApplicationId(reqVO.getApplicationId())
                .setEntityType(reqVO.getEntityType())
                .setSourceKey(reqVO.getSourceKey())
                .setAsOf(reqVO.getAsOf()))));
    }

    @GetMapping("/catalog/get")
    @Operation(summary = "发现某主体可见的主数据映射目录（无权系统不出现）")
    @PreAuthorize("@ss.hasPermission('ai:semantic:query')")
    public CommonResult<AiMasterObjectCatalogRespVO> getCatalog(@Valid AiMasterCatalogReqVO reqVO) {
        return success(toCatalogRespVO(catalogService.discover(new AiMasterObjectCatalogQueryDTO()
                .setObjectCode(reqVO.getObjectCode())
                .setRevisionNo(reqVO.getRevisionNo())
                .setApplicationId(reqVO.getApplicationId())
                .setSubjectType(reqVO.getSubjectType())
                .setExternalUserId(reqVO.getExternalUserId())
                .setAsOf(reqVO.getAsOf()))));
    }

    private static AiMasterObjectRespVO toObjectRespVO(AiMasterObjectDO object) {
        return new AiMasterObjectRespVO()
                .setId(object.getId())
                .setObjectCode(object.getObjectCode())
                .setObjectName(object.getObjectName())
                .setObjectType(object.getObjectType())
                .setDescription(object.getDescription())
                .setStatus(object.getStatus())
                .setCurrentRevision(object.getCurrentRevision())
                .setVersion(object.getVersion())
                .setCreateTime(object.getCreateTime());
    }

    private static AiMasterRevisionRespVO toRevisionRespVO(AiMasterObjectRevisionDO revision) {
        return new AiMasterRevisionRespVO()
                .setMasterObjectId(revision.getMasterObjectId())
                .setRevisionNo(revision.getRevisionNo())
                .setStatus(revision.getStatus())
                .setValidFrom(revision.getValidFrom())
                .setValidTo(revision.getValidTo())
                .setEntryCount(revision.getEntryCount())
                .setMappingFingerprint(revision.getMappingFingerprint())
                .setCreatedBy(revision.getCreatedBy())
                .setPublishedBy(revision.getPublishedBy())
                .setPublishedTime(revision.getPublishedTime())
                .setVersion(revision.getVersion());
    }

    private static AiMasterRevisionDetailRespVO toRevisionDetailRespVO(AiMasterRevisionDetailDTO detail) {
        return new AiMasterRevisionDetailRespVO()
                .setRevision(toRevisionRespVO(detail.getRevision()))
                .setEntries(detail.getEntries().stream()
                        .map(entry ->
                                toMappingEntry(entry, detail.getEntryProblems().get(entry.getId())))
                        .toList())
                .setConflictKeys(detail.getConflictKeys())
                .setPublishable(detail.isPublishable());
    }

    private static AiMasterRevisionDetailRespVO.MappingEntry toMappingEntry(
            AiMasterObjectMappingDO entry, String problem) {
        return new AiMasterRevisionDetailRespVO.MappingEntry()
                .setId(entry.getId())
                .setApplicationId(entry.getApplicationId())
                .setEntityType(entry.getEntityType())
                .setSourceKey(entry.getSourceKey())
                .setSourceName(entry.getSourceName())
                .setMatchMethod(entry.getMatchMethod())
                .setValidFrom(entry.getValidFrom())
                .setValidTo(entry.getValidTo())
                .setVersion(entry.getVersion())
                .setProblem(problem);
    }

    private static AiMasterMappingResolutionRespVO toResolutionRespVO(AiMasterMappingResolutionDTO resolution) {
        return new AiMasterMappingResolutionRespVO()
                .setMasterObjectId(resolution.getMasterObjectId())
                .setObjectCode(resolution.getObjectCode())
                .setObjectName(resolution.getObjectName())
                .setObjectType(resolution.getObjectType())
                .setRevisionNo(resolution.getRevisionNo())
                .setRevisionFingerprint(resolution.getRevisionFingerprint())
                .setAsOf(resolution.getAsOf())
                .setApplicationId(resolution.getApplicationId())
                .setEntityType(resolution.getEntityType())
                .setSourceKey(resolution.getSourceKey())
                .setSourceName(resolution.getSourceName())
                .setMatchMethod(resolution.getMatchMethod())
                .setValidFrom(resolution.getValidFrom())
                .setValidTo(resolution.getValidTo());
    }

    private static AiMasterMappingReverseRespVO toReverseRespVO(AiMasterMappingReverseResultDTO result) {
        return new AiMasterMappingReverseRespVO()
                .setMapped(result.isMapped())
                .setReason(result.getReason())
                .setApplicationId(result.getApplicationId())
                .setEntityType(result.getEntityType())
                .setSourceKey(result.getSourceKey())
                .setAsOf(result.getAsOf())
                .setMasterObjectId(result.getMasterObjectId())
                .setObjectCode(result.getObjectCode())
                .setObjectName(result.getObjectName())
                .setObjectType(result.getObjectType())
                .setRevisionNo(result.getRevisionNo())
                .setRevisionFingerprint(result.getRevisionFingerprint())
                .setMatchMethod(result.getMatchMethod())
                .setSourceName(result.getSourceName())
                .setValidFrom(result.getValidFrom())
                .setValidTo(result.getValidTo());
    }

    private static AiMasterObjectCatalogRespVO toCatalogRespVO(AiMasterObjectCatalogDTO catalog) {
        return new AiMasterObjectCatalogRespVO()
                .setMasterObjectId(catalog.getMasterObjectId())
                .setObjectCode(catalog.getObjectCode())
                .setObjectName(catalog.getObjectName())
                .setObjectType(catalog.getObjectType())
                .setRevisionNo(catalog.getRevisionNo())
                .setRevisionFingerprint(catalog.getRevisionFingerprint())
                .setAsOf(catalog.getAsOf())
                .setDenied(catalog.isDenied())
                .setEntries(catalog.getEntries().stream()
                        .map(AiMasterObjectController::toCatalogEntry)
                        .toList())
                .setCatalogFingerprint(catalog.getCatalogFingerprint())
                .setModelCatalog(catalog.getModelCatalog());
    }

    private static AiMasterObjectCatalogRespVO.Entry toCatalogEntry(AiMasterCatalogEntryDTO entry) {
        return new AiMasterObjectCatalogRespVO.Entry()
                .setApplicationId(entry.getApplicationId())
                .setAppCode(entry.getAppCode())
                .setSystemName(entry.getSystemName())
                .setEntityType(entry.getEntityType())
                .setSourceKey(entry.getSourceKey())
                .setSourceName(entry.getSourceName())
                .setMatchMethod(entry.getMatchMethod())
                .setValidFrom(entry.getValidFrom())
                .setValidTo(entry.getValidTo())
                .setInForce(entry.isInForce())
                .setUsable(entry.isUsable())
                .setProblem(entry.getProblem());
    }
}
