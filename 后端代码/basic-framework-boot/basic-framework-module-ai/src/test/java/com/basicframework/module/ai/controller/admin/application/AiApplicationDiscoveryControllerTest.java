package com.basicframework.module.ai.controller.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageParam;
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
import com.basicframework.module.ai.service.application.dto.AiSystemEntryDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemScopeDTO;
import com.basicframework.module.ai.service.authorization.AiSubjectFederationService;
import com.basicframework.module.ai.service.authorization.dto.AiSubjectFederationSubmitDTO;
import com.basicframework.module.ai.service.context.AiAnalysisScopeService;
import com.basicframework.module.ai.service.context.dto.AiAnalysisScopeSelectDTO;
import com.basicframework.module.ai.service.context.dto.AiAnalysisScopeSelectionDTO;
import com.basicframework.module.ai.service.context.dto.AiSelectedSystemDTO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 多系统授权发现协议层契约（Y01）：VO 只做映射，身份与目录指纹逐字段传递，
 * 审批人不由请求体提供（服务层从登录态取），响应里的模型目录原样透出。
 */
class AiApplicationDiscoveryControllerTest {

    private final AiSystemCatalogService catalogService = mock(AiSystemCatalogService.class);

    private final AiAnalysisScopeService analysisScopeService = mock(AiAnalysisScopeService.class);

    private final AiSubjectFederationService federationService = mock(AiSubjectFederationService.class);

    private final AiApplicationDiscoveryController controller =
            new AiApplicationDiscoveryController(catalogService, analysisScopeService, federationService);

    @Test
    void discoverMapsIdentityAndExposesEntriesWithModelCatalog() {
        when(catalogService.discover(any(AiSystemCatalogQueryDTO.class))).thenReturn(catalog());

        CommonResult<AiSystemCatalogRespVO> result = controller.discover(new AiSystemCatalogReqVO()
                .setApplicationId(7L)
                .setSubjectType("USER")
                .setExternalUserId("alice"));

        ArgumentCaptor<AiSystemCatalogQueryDTO> captor = ArgumentCaptor.forClass(AiSystemCatalogQueryDTO.class);
        verify(catalogService).discover(captor.capture());
        assertThat(captor.getValue().getApplicationId()).isEqualTo(7L);
        assertThat(captor.getValue().getSubjectType()).isEqualTo("USER");
        assertThat(captor.getValue().getExternalUserId()).isEqualTo("alice");

        AiSystemCatalogRespVO data = result.getData();
        assertThat(data.isDenied()).isFalse();
        assertThat(data.getCatalogFingerprint()).isEqualTo("catalog-fingerprint");
        assertThat(data.getModelCatalog()).isEqualTo("[{\"system\":\"crm\"}]");
        assertThat(data.getEntries()).hasSize(1);
        AiSystemCatalogRespVO.SystemEntry entry = data.getEntries().get(0);
        assertThat(entry.getAppCode()).isEqualTo("crm");
        assertThat(entry.isCurrentSystem()).isTrue();
        assertThat(entry.getScopes()).hasSize(1);
        assertThat(entry.getScopes().get(0).getResourceKey()).isEqualTo("q3");
        assertThat(entry.getScopes().get(0).getActions()).containsExactly("READ");
    }

    @Test
    void selectAndVerifyPassSelectionFieldsThroughWithoutInventingAnything() {
        when(analysisScopeService.select(any(AiAnalysisScopeSelectDTO.class))).thenReturn(selection());
        when(analysisScopeService.verify(any(AiAnalysisScopeSelectionDTO.class)))
                .thenReturn(selection());

        AiAnalysisScopeSelectReqVO selectReqVO = new AiAnalysisScopeSelectReqVO()
                .setApplicationId(7L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setMode("CROSS_SYSTEM")
                .setTargetSystemCodes(List.of("crm", "erp"))
                .setCatalogFingerprint("catalog-fingerprint");
        AiAnalysisScopeSelectionRespVO selected = controller.select(selectReqVO).getData();

        ArgumentCaptor<AiAnalysisScopeSelectDTO> captor = ArgumentCaptor.forClass(AiAnalysisScopeSelectDTO.class);
        verify(analysisScopeService).select(captor.capture());
        assertThat(captor.getValue().getTargetSystemCodes()).containsExactly("crm", "erp");
        assertThat(captor.getValue().getCatalogFingerprint()).isEqualTo("catalog-fingerprint");
        assertThat(selected.getSelectionFingerprint()).isEqualTo("selection-fingerprint");
        assertThat(selected.getSystems()).hasSize(1);
        assertThat(selected.getSystems().get(0).getFederationId()).isEqualTo(11L);

        AiAnalysisScopeVerifyReqVO verifyReqVO = new AiAnalysisScopeVerifyReqVO()
                .setApplicationId(7L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setMode("CROSS_SYSTEM")
                .setTargetSystemCodes(List.of("crm", "erp"))
                .setCatalogFingerprint("catalog-fingerprint")
                .setSelectionFingerprint("selection-fingerprint");
        assertThat(controller.verify(verifyReqVO).getData().getSelectionFingerprint())
                .isEqualTo("selection-fingerprint");
        ArgumentCaptor<AiAnalysisScopeSelectionDTO> verifyCaptor =
                ArgumentCaptor.forClass(AiAnalysisScopeSelectionDTO.class);
        verify(analysisScopeService).verify(verifyCaptor.capture());
        assertThat(verifyCaptor.getValue().getSelectionFingerprint()).isEqualTo("selection-fingerprint");
    }

    @Test
    void federationMutationsDelegateToServiceAndNeverCarryTheApproverInTheBody() {
        when(federationService.submit(any(AiSubjectFederationSubmitDTO.class))).thenReturn(42L);

        CommonResult<Long> submitted = controller.submitFederation(new AiSubjectFederationSubmitReqVO()
                .setSourceApplicationId(7L)
                .setSourceSubjectType("USER")
                .setSourceExternalUserId("alice")
                .setTargetApplicationId(9L)
                .setTargetSubjectType("USER")
                .setTargetExternalUserId("alice"));

        ArgumentCaptor<AiSubjectFederationSubmitDTO> captor =
                ArgumentCaptor.forClass(AiSubjectFederationSubmitDTO.class);
        verify(federationService).submit(captor.capture());
        assertThat(captor.getValue().getTargetExternalUserId()).isEqualTo("alice");
        assertThat(submitted.getData()).isEqualTo(42L);

        assertThat(controller
                        .approveFederation(new AiSubjectFederationApproveReqVO()
                                .setId(42L)
                                .setVersion(0)
                                .setApprovalNote("复核通过"))
                        .getData())
                .isTrue();
        verify(federationService).approve(42L, 0, "复核通过");

        assertThat(controller
                        .revokeFederation(
                                new AiSubjectFederationRevokeReqVO().setId(42L).setVersion(1))
                        .getData())
                .isTrue();
        verify(federationService).revoke(42L, 1);
    }

    @Test
    void federationQueriesMapDoToVoAndPageKeepsTotal() {
        AiSubjectFederationDO row = new AiSubjectFederationDO()
                .setId(42L)
                .setSourceApplicationId(7L)
                .setSourceSubjectType("USER")
                .setSourceExternalUserId("alice")
                .setTargetApplicationId(9L)
                .setTargetSubjectType("USER")
                .setTargetExternalUserId("alice")
                .setStatus(AiSubjectFederationDO.STATUS_APPROVED)
                .setRequestedBy(1001L)
                .setRequestedTime(LocalDateTime.of(2026, 9, 29, 10, 0))
                .setApprovedBy(2002L)
                .setApprovedTime(LocalDateTime.of(2026, 9, 29, 11, 0))
                .setApprovalNote("复核通过")
                .setRevision(2L)
                .setVersion(1);
        when(federationService.getFederation(42L)).thenReturn(row);
        when(federationService.getFederationPage(any(PageParam.class), any(), any()))
                .thenReturn(new PageResult<>(List.of(row), 1L));

        AiSubjectFederationRespVO detail = controller.getFederation(42L).getData();
        assertThat(detail.getStatus()).isEqualTo(AiSubjectFederationDO.STATUS_APPROVED);
        assertThat(detail.getApprovedBy()).isEqualTo(2002L);
        assertThat(detail.getApprovalNote()).isEqualTo("复核通过");

        AiSubjectFederationPageReqVO pageReqVO = new AiSubjectFederationPageReqVO();
        pageReqVO.setSourceApplicationId(7L);
        pageReqVO.setStatus(AiSubjectFederationDO.STATUS_APPROVED);
        PageResult<AiSubjectFederationRespVO> page =
                controller.getFederationPage(pageReqVO).getData();
        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getList()).hasSize(1);
        assertThat(page.getList().get(0).getId()).isEqualTo(42L);
        assertThat(page.getList().get(0).getSourceExternalUserId()).isEqualTo("alice");
    }

    private static AiSystemCatalogDTO catalog() {
        return new AiSystemCatalogDTO()
                .setApplicationId(7L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setDenied(false)
                .setEntries(List.of(new AiSystemEntryDTO()
                        .setApplicationId(7L)
                        .setAppCode("crm")
                        .setSystemName("CRM")
                        .setCurrentSystem(true)
                        .setSubjectType("USER")
                        .setExternalUserId("alice")
                        .setScopeSource("crm-auth")
                        .setScopeVersion(2L)
                        .setScopes(List.of(new AiSystemScopeDTO()
                                .setResourceType("REPORT")
                                .setResourceKey("q3")
                                .setActions(List.of("READ"))))
                        .setSystemFingerprint("system-fingerprint")))
                .setCatalogFingerprint("catalog-fingerprint")
                .setModelCatalog("[{\"system\":\"crm\"}]");
    }

    private static AiAnalysisScopeSelectionDTO selection() {
        return new AiAnalysisScopeSelectionDTO()
                .setApplicationId(7L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setMode("CROSS_SYSTEM")
                .setTargetSystemCodes(List.of("crm", "erp"))
                .setCatalogFingerprint("catalog-fingerprint")
                .setSystems(List.of(new AiSelectedSystemDTO()
                        .setApplicationId(9L)
                        .setAppCode("erp")
                        .setSystemName("ERP")
                        .setCurrentSystem(false)
                        .setFederationId(11L)
                        .setSystemFingerprint("system-fingerprint")))
                .setModelCatalog("[{\"system\":\"erp\"}]")
                .setSelectionFingerprint("selection-fingerprint");
    }
}
