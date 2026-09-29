package com.basicframework.module.ai.controller.admin.application;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.application.vo.AiAnalysisScopeSelectReqVO;
import com.basicframework.module.ai.controller.admin.application.vo.AiAnalysisScopeSelectionRespVO;
import com.basicframework.module.ai.controller.admin.application.vo.AiAnalysisScopeVerifyReqVO;
import com.basicframework.module.ai.controller.admin.application.vo.AiSubjectFederationApproveReqVO;
import com.basicframework.module.ai.controller.admin.application.vo.AiSubjectFederationPageReqVO;
import com.basicframework.module.ai.controller.admin.application.vo.AiSubjectFederationRespVO;
import com.basicframework.module.ai.controller.admin.application.vo.AiSubjectFederationRevokeReqVO;
import com.basicframework.module.ai.controller.admin.application.vo.AiSubjectFederationSubmitReqVO;
import com.basicframework.module.ai.controller.admin.application.vo.AiSystemCatalogReqVO;
import com.basicframework.module.ai.controller.admin.application.vo.AiSystemCatalogRespVO;
import com.basicframework.module.ai.dal.dataobject.federation.AiSubjectFederationDO;
import com.basicframework.module.ai.service.application.AiSystemCatalogService;
import com.basicframework.module.ai.service.application.dto.AiSystemCatalogDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemCatalogQueryDTO;
import com.basicframework.module.ai.service.authorization.AiSubjectFederationService;
import com.basicframework.module.ai.service.authorization.dto.AiSubjectFederationSubmitDTO;
import com.basicframework.module.ai.service.context.AiAnalysisScopeService;
import com.basicframework.module.ai.service.context.dto.AiAnalysisScopeSelectDTO;
import com.basicframework.module.ai.service.context.dto.AiAnalysisScopeSelectionDTO;
import com.basicframework.module.ai.service.context.dto.AiSelectedSystemDTO;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多系统授权发现与范围选择接口（Y01）。
 *
 * <p>权限边界（与 V91 迁移的 system_menu 种子一一对应）：
 * <ul>
 *   <li>发现与范围选择是**只读**能力，复用 {@code ai:application:query}（它只回答"这个主体在
 *       哪些系统有什么范围"，不改变任何授权事实）；</li>
 *   <li>联邦映射的提交/批准/撤销使用独立权限 {@code ai:application:federation}：拥有应用修改权
 *       不等于拥有跨系统身份映射权（映射会让另一系统的主体可见，必须独立审批）；</li>
 *   <li>批准人由登录态决定，请求体不能自报（服务层校验批准人 ≠ 提交人）。</li>
 * </ul>
 */
@Tag(name = "管理后台 - AI 多系统授权发现")
@RestController
@RequestMapping("/ai/application")
@Validated
@RequiredArgsConstructor
public class AiApplicationDiscoveryController {

    private final AiSystemCatalogService catalogService;

    private final AiAnalysisScopeService analysisScopeService;

    private final AiSubjectFederationService federationService;

    @GetMapping("/discovery/get")
    @Operation(summary = "发现当前主体可访问的系统与范围（无权系统不出现）")
    @PreAuthorize("@ss.hasPermission('ai:application:query')")
    public CommonResult<AiSystemCatalogRespVO> discover(@Valid AiSystemCatalogReqVO reqVO) {
        AiSystemCatalogDTO catalog = catalogService.discover(new AiSystemCatalogQueryDTO()
                .setApplicationId(reqVO.getApplicationId())
                .setSubjectType(reqVO.getSubjectType())
                .setExternalUserId(reqVO.getExternalUserId()));
        return success(toCatalogRespVO(catalog));
    }

    @PostMapping("/discovery/select")
    @Operation(summary = "显式选择分析范围（当前系统或跨系统，校验目录指纹）")
    @PreAuthorize("@ss.hasPermission('ai:application:query')")
    public CommonResult<AiAnalysisScopeSelectionRespVO> select(@Valid @RequestBody AiAnalysisScopeSelectReqVO reqVO) {
        return success(toSelectionRespVO(analysisScopeService.select(new AiAnalysisScopeSelectDTO()
                .setApplicationId(reqVO.getApplicationId())
                .setSubjectType(reqVO.getSubjectType())
                .setExternalUserId(reqVO.getExternalUserId())
                .setMode(reqVO.getMode())
                .setTargetSystemCodes(reqVO.getTargetSystemCodes())
                .setCatalogFingerprint(reqVO.getCatalogFingerprint()))));
    }

    @PostMapping("/discovery/verify")
    @Operation(summary = "再核验历史范围选择（事实变化返回 409）")
    @PreAuthorize("@ss.hasPermission('ai:application:query')")
    public CommonResult<AiAnalysisScopeSelectionRespVO> verify(@Valid @RequestBody AiAnalysisScopeVerifyReqVO reqVO) {
        return success(toSelectionRespVO(analysisScopeService.verify(new AiAnalysisScopeSelectionDTO()
                .setApplicationId(reqVO.getApplicationId())
                .setSubjectType(reqVO.getSubjectType())
                .setExternalUserId(reqVO.getExternalUserId())
                .setMode(reqVO.getMode())
                .setTargetSystemCodes(reqVO.getTargetSystemCodes())
                .setCatalogFingerprint(reqVO.getCatalogFingerprint())
                .setSelectionFingerprint(reqVO.getSelectionFingerprint()))));
    }

    @PostMapping("/federation/submit")
    @Operation(summary = "登记跨系统主体联邦映射（等待独立审批，不按同名推断）")
    @PreAuthorize("@ss.hasPermission('ai:application:federation')")
    public CommonResult<Long> submitFederation(@Valid @RequestBody AiSubjectFederationSubmitReqVO reqVO) {
        return success(federationService.submit(new AiSubjectFederationSubmitDTO()
                .setSourceApplicationId(reqVO.getSourceApplicationId())
                .setSourceSubjectType(reqVO.getSourceSubjectType())
                .setSourceExternalUserId(reqVO.getSourceExternalUserId())
                .setTargetApplicationId(reqVO.getTargetApplicationId())
                .setTargetSubjectType(reqVO.getTargetSubjectType())
                .setTargetExternalUserId(reqVO.getTargetExternalUserId())));
    }

    @PutMapping("/federation/approve")
    @Operation(summary = "独立审批通过联邦映射（批准人必须不同于提交人）")
    @PreAuthorize("@ss.hasPermission('ai:application:federation')")
    public CommonResult<Boolean> approveFederation(@Valid @RequestBody AiSubjectFederationApproveReqVO reqVO) {
        federationService.approve(reqVO.getId(), reqVO.getVersion(), reqVO.getApprovalNote());
        return success(true);
    }

    @PutMapping("/federation/revoke")
    @Operation(summary = "撤销联邦映射（立即不再参与发现）")
    @PreAuthorize("@ss.hasPermission('ai:application:federation')")
    public CommonResult<Boolean> revokeFederation(@Valid @RequestBody AiSubjectFederationRevokeReqVO reqVO) {
        federationService.revoke(reqVO.getId(), reqVO.getVersion());
        return success(true);
    }

    @GetMapping("/federation/get")
    @Operation(summary = "查询联邦映射详情")
    @Parameter(name = "id", description = "映射编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:application:query')")
    public CommonResult<AiSubjectFederationRespVO> getFederation(@RequestParam("id") @NotNull @Positive Long id) {
        return success(toFederationRespVO(federationService.getFederation(id)));
    }

    @GetMapping("/federation/page")
    @Operation(summary = "查询联邦映射分页")
    @PreAuthorize("@ss.hasPermission('ai:application:query')")
    public CommonResult<PageResult<AiSubjectFederationRespVO>> getFederationPage(
            @Valid AiSubjectFederationPageReqVO pageReqVO) {
        PageResult<AiSubjectFederationDO> page = federationService.getFederationPage(
                pageReqVO, pageReqVO.getSourceApplicationId(), pageReqVO.getStatus());
        List<AiSubjectFederationRespVO> list = page.getList().stream()
                .map(AiApplicationDiscoveryController::toFederationRespVO)
                .toList();
        return success(new PageResult<>(list, page.getTotal()));
    }

    private static AiSystemCatalogRespVO toCatalogRespVO(AiSystemCatalogDTO catalog) {
        return new AiSystemCatalogRespVO()
                .setApplicationId(catalog.getApplicationId())
                .setSubjectType(catalog.getSubjectType())
                .setExternalUserId(catalog.getExternalUserId())
                .setDenied(catalog.isDenied())
                .setEntries(catalog.getEntries().stream()
                        .map(entry -> new AiSystemCatalogRespVO.SystemEntry()
                                .setApplicationId(entry.getApplicationId())
                                .setAppCode(entry.getAppCode())
                                .setSystemName(entry.getSystemName())
                                .setCurrentSystem(entry.isCurrentSystem())
                                .setFederationId(entry.getFederationId())
                                .setFederationRevision(entry.getFederationRevision())
                                .setSubjectType(entry.getSubjectType())
                                .setExternalUserId(entry.getExternalUserId())
                                .setScopeSource(entry.getScopeSource())
                                .setScopeVersion(entry.getScopeVersion())
                                .setScopes(entry.getScopes().stream()
                                        .map(scope -> new AiSystemCatalogRespVO.ScopeRow()
                                                .setResourceType(scope.getResourceType())
                                                .setResourceKey(scope.getResourceKey())
                                                .setActions(scope.getActions()))
                                        .toList())
                                .setSystemFingerprint(entry.getSystemFingerprint()))
                        .toList())
                .setCatalogFingerprint(catalog.getCatalogFingerprint())
                .setModelCatalog(catalog.getModelCatalog());
    }

    private static AiAnalysisScopeSelectionRespVO toSelectionRespVO(AiAnalysisScopeSelectionDTO selection) {
        return new AiAnalysisScopeSelectionRespVO()
                .setApplicationId(selection.getApplicationId())
                .setSubjectType(selection.getSubjectType())
                .setExternalUserId(selection.getExternalUserId())
                .setMode(selection.getMode())
                .setTargetSystemCodes(selection.getTargetSystemCodes())
                .setCatalogFingerprint(selection.getCatalogFingerprint())
                .setSystems(selection.getSystems().stream()
                        .map(AiApplicationDiscoveryController::toSelectedSystemRespVO)
                        .toList())
                .setModelCatalog(selection.getModelCatalog())
                .setSelectionFingerprint(selection.getSelectionFingerprint());
    }

    private static AiAnalysisScopeSelectionRespVO.SelectedSystem toSelectedSystemRespVO(AiSelectedSystemDTO system) {
        return new AiAnalysisScopeSelectionRespVO.SelectedSystem()
                .setApplicationId(system.getApplicationId())
                .setAppCode(system.getAppCode())
                .setSystemName(system.getSystemName())
                .setCurrentSystem(system.isCurrentSystem())
                .setFederationId(system.getFederationId())
                .setSystemFingerprint(system.getSystemFingerprint());
    }

    private static AiSubjectFederationRespVO toFederationRespVO(AiSubjectFederationDO federation) {
        return new AiSubjectFederationRespVO()
                .setId(federation.getId())
                .setSourceApplicationId(federation.getSourceApplicationId())
                .setSourceSubjectType(federation.getSourceSubjectType())
                .setSourceExternalUserId(federation.getSourceExternalUserId())
                .setTargetApplicationId(federation.getTargetApplicationId())
                .setTargetSubjectType(federation.getTargetSubjectType())
                .setTargetExternalUserId(federation.getTargetExternalUserId())
                .setStatus(federation.getStatus())
                .setRequestedBy(federation.getRequestedBy())
                .setRequestedTime(federation.getRequestedTime())
                .setApprovedBy(federation.getApprovedBy())
                .setApprovedTime(federation.getApprovedTime())
                .setApprovalNote(federation.getApprovalNote())
                .setRevision(federation.getRevision())
                .setVersion(federation.getVersion());
    }
}
