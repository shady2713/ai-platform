package com.basicframework.module.ai.service.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.AiSystemCatalogService;
import com.basicframework.module.ai.service.application.dto.AiSystemCatalogDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemEntryDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemScopeDTO;
import com.basicframework.module.ai.service.context.dto.AiAnalysisScopeSelectDTO;
import com.basicframework.module.ai.service.context.dto.AiAnalysisScopeSelectionDTO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Y01 范围选择闭环：显式选择、目标全命中、指纹比对、事实变化即拒绝。
 *
 * <p>负向用例是重点：不在目录里的目标系统整体拒绝（不静默剔除）、目录指纹不一致报 409、
 * 单系统模式不接受目标清单、跨系统必须至少两个系统，历史选择在事实变化后核验失败。
 */
class AiAnalysisScopeServiceImplTest {

    private static final String CATALOG_FP = "a".repeat(64);

    private static final String EMPTY_FP = "b".repeat(64);

    private AiSystemCatalogService catalogService;

    private AiAnalysisScopeServiceImpl service;

    @BeforeEach
    void setUp() {
        catalogService = mock(AiSystemCatalogService.class);
        service = new AiAnalysisScopeServiceImpl(catalogService);
        when(catalogService.discover(any())).thenReturn(catalog());
    }

    @Test
    void currentSystemSelectionNeedsNoTargetsAndCarriesFingerprint() {
        AiAnalysisScopeSelectionDTO selection =
                service.select(select("CURRENT_SYSTEM", null).setCatalogFingerprint(CATALOG_FP));

        assertThat(selection.getMode()).isEqualTo("CURRENT_SYSTEM");
        assertThat(selection.getTargetSystemCodes()).containsExactly("crm");
        assertThat(selection.getSystems()).hasSize(1);
        assertThat(selection.getSystems().get(0).isCurrentSystem()).isTrue();
        assertThat(selection.getSelectionFingerprint()).hasSize(64);
        assertThat(selection.getModelCatalog()).contains("\"system\":\"crm\"").doesNotContain("erp");
    }

    @Test
    void crossSystemSelectionKeepsCatalogOrderAndOnlySelectedSystems() {
        AiAnalysisScopeSelectionDTO selection =
                service.select(select("CROSS_SYSTEM", List.of("erp", "crm")).setCatalogFingerprint(CATALOG_FP));

        assertThat(selection.getTargetSystemCodes()).containsExactly("crm", "erp");
        assertThat(selection.getSystems())
                .extracting(system -> system.getAppCode())
                .containsExactly("crm", "erp");
        assertThat(selection.getModelCatalog()).contains("\"system\":\"crm\"").contains("\"system\":\"erp\"");
    }

    @Test
    void selectionRejectsUnknownModeMissingFingerprintAndShapeProblems() {
        assertCode(() -> service.select(null), AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.select(select("EVERYTHING", null).setCatalogFingerprint(CATALOG_FP)),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.select(select("CURRENT_SYSTEM", null)),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.select(select("CURRENT_SYSTEM", List.of("crm")).setCatalogFingerprint(CATALOG_FP)),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.select(select("CROSS_SYSTEM", List.of()).setCatalogFingerprint(CATALOG_FP)),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.select(select("CROSS_SYSTEM", List.of("crm")).setCatalogFingerprint(CATALOG_FP)),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.select(select("CROSS_SYSTEM", List.of("crm", " ")).setCatalogFingerprint(CATALOG_FP)),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.select(
                        select("CROSS_SYSTEM", List.of("crm", "x".repeat(65))).setCatalogFingerprint(CATALOG_FP)),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
    }

    @Test
    void selectionFailsClosedWhenTargetOrCurrentSystemIsNotAuthorized() {
        // 目标系统不在目录里：整体拒绝，而不是静默剔除后继续
        assertCode(
                () -> service.select(
                        select("CROSS_SYSTEM", List.of("crm", "hr")).setCatalogFingerprint(CATALOG_FP)),
                AiErrorCodeConstants.AI_ANALYSIS_SCOPE_DENIED.getCode());
        // 跨系统但没有当前系统
        assertCode(
                () -> service.select(select("CROSS_SYSTEM", List.of("erp")).setCatalogFingerprint(CATALOG_FP)),
                AiErrorCodeConstants.AI_ANALYSIS_SCOPE_DENIED.getCode());

        when(catalogService.discover(any())).thenReturn(deniedCatalog());
        assertCode(
                () -> service.select(select("CURRENT_SYSTEM", null).setCatalogFingerprint(EMPTY_FP)),
                AiErrorCodeConstants.AI_ANALYSIS_SCOPE_DENIED.getCode());
    }

    @Test
    void staleCatalogFingerprintIsAConflict() {
        assertCode(
                () -> service.select(
                        select("CROSS_SYSTEM", List.of("crm", "erp")).setCatalogFingerprint("stale")),
                AiErrorCodeConstants.AI_ANALYSIS_SCOPE_VERSION_CONFLICT.getCode());
    }

    @Test
    void verifyReturnsServerRecomputedSelectionAndRejectsTampering() {
        AiAnalysisScopeSelectionDTO selected =
                service.select(select("CROSS_SYSTEM", List.of("crm", "erp")).setCatalogFingerprint(CATALOG_FP));

        AiAnalysisScopeSelectionDTO verified = service.verify(selected);

        assertThat(verified.getSelectionFingerprint()).isEqualTo(selected.getSelectionFingerprint());
        // 返回的是服务端重算结果：调用方携带的 systems/modelCatalog 不被采信
        assertThat(verified.getSystems()).hasSize(2);

        AiAnalysisScopeSelectionDTO tampered = new AiAnalysisScopeSelectionDTO()
                .setApplicationId(7L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setMode("CROSS_SYSTEM")
                .setTargetSystemCodes(List.of("crm", "erp"))
                .setCatalogFingerprint(CATALOG_FP)
                .setSelectionFingerprint("0".repeat(64));
        assertCode(() -> service.verify(tampered), AiErrorCodeConstants.AI_ANALYSIS_SCOPE_VERSION_CONFLICT.getCode());
    }

    @Test
    void verifyDetectsFactsChangedAfterSelection() {
        AiAnalysisScopeSelectionDTO selected =
                service.select(select("CROSS_SYSTEM", List.of("crm", "erp")).setCatalogFingerprint(CATALOG_FP));
        // 事实变化：目标系统的范围被撤销 → 目录指纹变化
        when(catalogService.discover(any())).thenReturn(catalogWithoutFederated());

        assertCode(() -> service.verify(selected), AiErrorCodeConstants.AI_ANALYSIS_SCOPE_VERSION_CONFLICT.getCode());
        verify(catalogService, times(2)).discover(any());
    }

    @Test
    void verifyValidatesHistoricalSelectionShape() {
        assertCode(() -> service.verify(null), AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.verify(new AiAnalysisScopeSelectionDTO().setMode("CROSS_SYSTEM")),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        AiAnalysisScopeSelectionDTO withoutMode = new AiAnalysisScopeSelectionDTO()
                .setApplicationId(7L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setMode("UNKNOWN")
                .setCatalogFingerprint(CATALOG_FP)
                .setSelectionFingerprint("x");
        assertCode(() -> service.verify(withoutMode), AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
    }

    @Test
    void verifyOfCurrentSystemSelectionAcceptsResultShape() {
        AiAnalysisScopeSelectionDTO selected =
                service.select(select("CURRENT_SYSTEM", null).setCatalogFingerprint(CATALOG_FP));

        // 结果形态里 targetSystemCodes 只含当前系统；核验按模式归一化，不会把它当成"单系统却带目标"
        assertThat(service.verify(selected).getSelectionFingerprint()).isEqualTo(selected.getSelectionFingerprint());
    }

    private AiAnalysisScopeSelectDTO select(String mode, List<String> targets) {
        return new AiAnalysisScopeSelectDTO()
                .setApplicationId(7L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setMode(mode)
                .setTargetSystemCodes(targets);
    }

    private static AiSystemCatalogDTO catalog() {
        return new AiSystemCatalogDTO()
                .setApplicationId(7L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setDenied(false)
                .setEntries(List.of(entry(7L, "crm", true, null), entry(9L, "erp", false, 11L)))
                .setCatalogFingerprint(CATALOG_FP)
                .setModelCatalog("[]");
    }

    private static AiSystemCatalogDTO catalogWithoutFederated() {
        return new AiSystemCatalogDTO()
                .setApplicationId(7L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setDenied(false)
                .setEntries(List.of(entry(7L, "crm", true, null)))
                .setCatalogFingerprint("c".repeat(64))
                .setModelCatalog("[]");
    }

    private static AiSystemCatalogDTO deniedCatalog() {
        return new AiSystemCatalogDTO()
                .setApplicationId(7L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setDenied(true)
                .setEntries(List.of())
                .setCatalogFingerprint(EMPTY_FP)
                .setModelCatalog("[]");
    }

    private static AiSystemEntryDTO entry(Long applicationId, String appCode, boolean current, Long federationId) {
        return new AiSystemEntryDTO()
                .setApplicationId(applicationId)
                .setAppCode(appCode)
                .setSystemName(appCode + " 系统")
                .setCurrentSystem(current)
                .setFederationId(federationId)
                .setFederationRevision(federationId == null ? null : 1L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setScopeSource("crm-auth")
                .setScopeVersion(2L)
                .setScopes(List.of(new AiSystemScopeDTO()
                        .setResourceType("REPORT")
                        .setResourceKey("q3")
                        .setActions(List.of("READ"))))
                .setSystemFingerprint(appCode.equals("crm") ? "1".repeat(64) : "2".repeat(64));
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, Integer code) {
        assertThatThrownBy(callable)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(code);
    }
}
