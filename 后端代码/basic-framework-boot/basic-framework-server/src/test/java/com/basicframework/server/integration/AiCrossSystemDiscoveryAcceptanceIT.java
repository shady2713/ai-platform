package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.federation.AiSubjectFederationDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.AiSystemCatalogService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemCatalogDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemCatalogQueryDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemEntryDTO;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.authorization.AiSubjectFederationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.ai.service.authorization.dto.AiSubjectFederationSubmitDTO;
import com.basicframework.module.ai.service.context.AiAnalysisScopeService;
import com.basicframework.module.ai.service.context.dto.AiAnalysisScopeSelectDTO;
import com.basicframework.module.ai.service.context.dto.AiAnalysisScopeSelectionDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Y01 多系统授权发现与范围选择（真实 MySQL + Redis）。
 *
 * <p>卡片验收在真实取证上逐条对应：
 * <ol>
 *   <li><b>单系统流程回归</b>：只有一个系统时（无联邦映射），发现只返回当前系统，范围选择
 *       CURRENT_SYSTEM 可用，A03 授权判定照旧放行（本卡不改动单系统语义）；</li>
 *   <li><b>无权系统不出现（枚举不泄漏）</b>：未批准的映射、目标应用里没有该主体、
 *       目标系统没有 ACTIVE 授权，目标系统都**完全不出现在目录与模型目录里**；</li>
 *   <li><b>同 externalUserId 跨应用隔离</b>：两个应用里同名的 alice 是不同主体，
 *       没有联邦映射就互不可见；映射是**有向**的（A→B 不等于 B→A）。</li>
 * </ol>
 *
 * <p>另加"显式选择 + 独立审批 + 撤销即时生效 + 历史选择核验失败"的闭环验收。
 */
@Import(AiCrossSystemDiscoveryAcceptanceIT.CrossSystemScopeConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiCrossSystemDiscoveryAcceptanceIT extends AbstractPersistenceIntegrationTest {

    private static final String CRM_APP = "it-cross-crm";

    private static final String ERP_APP = "it-cross-erp";

    private static final String CRM_REPORT = "crm_q3_report";

    private static final String ERP_DATASET = "erp_orders";

    /** 与既有 AI IT 同源：范围解析器给出受控范围（不改生产解析器）。 */
    @TestConfiguration
    static class CrossSystemScopeConfiguration {

        @Bean
        com.basicframework.module.ai.domain.identity.SubjectScopeResolver crossSystemScopeResolver() {
            return request -> java.util.Optional.of(new com.basicframework.module.ai.domain.identity.SubjectScope(
                    Set.of(10L), Set.copyOf(request.resourceHints()), request.scopeSource(), request.scopeVersion()));
        }
    }

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiAuthorizationService authorizationService;

    @Autowired
    private AiSystemCatalogService catalogService;

    @Autowired
    private AiAnalysisScopeService analysisScopeService;

    @Autowired
    private AiSubjectFederationService federationService;

    private Long crmApplicationId;

    private Long erpApplicationId;

    @BeforeEach
    void prepare() {
        cleanUpData();
        crmApplicationId = createApplication(CRM_APP, "CRM 系统");
        erpApplicationId = createApplication(ERP_APP, "ERP 系统");
        for (String subject : List.of("alice", "bob")) {
            subjectService.syncSubject(crmApplicationId, AiSubjectType.USER, subject, subject, "crm-auth", 1L);
            subjectService.syncSubject(erpApplicationId, AiSubjectType.USER, subject, subject, "erp-auth", 1L);
        }
        grant(crmApplicationId, "alice", AiResourceType.REPORT, CRM_REPORT, Set.of(AiAction.READ, AiAction.EXECUTE));
        grant(erpApplicationId, "alice", AiResourceType.DATASET, ERP_DATASET, Set.of(AiAction.READ));
        grant(erpApplicationId, "bob", AiResourceType.DATASET, ERP_DATASET, Set.of(AiAction.READ));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        cleanUpData();
        crmApplicationId = null;
        erpApplicationId = null;
    }

    @Test
    void singleSystemFlowStaysIntactWithoutAnyFederationLink() {
        AiSystemCatalogDTO catalog = discover(crmApplicationId, "alice");

        assertThat(catalog.isDenied()).isFalse();
        assertThat(catalog.getEntries())
                .extracting(AiSystemEntryDTO::getAppCode)
                .containsExactly(CRM_APP);
        assertThat(catalog.getEntries().get(0).isCurrentSystem()).isTrue();
        assertThat(catalog.getModelCatalog()).contains(CRM_APP).doesNotContain(ERP_APP);

        // 单系统选择闭环：显式选择当前系统 + 指纹核验
        AiAnalysisScopeSelectionDTO selection = analysisScopeService.select(
                selectRequest(crmApplicationId, "CURRENT_SYSTEM", null, catalog.getCatalogFingerprint()));
        assertThat(selection.getTargetSystemCodes()).containsExactly(CRM_APP);
        assertThat(selection.getSystems()).hasSize(1);
        assertThat(analysisScopeService.verify(selection).getSelectionFingerprint())
                .isEqualTo(selection.getSelectionFingerprint());

        // 单系统授权判定（A03）不受本卡影响
        AiAuthorizationDecisionDTO decision = authorizationService.authorize(
                crmApplicationId,
                AiSubjectType.USER.name(),
                "alice",
                AiResourceType.REPORT,
                CRM_REPORT,
                AiAction.READ,
                List.of());
        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    void unauthorizedSystemsNeverAppearInCatalogOrModelCatalog() {
        // 1) 未批准的映射（PENDING）不参与发现：目标系统完全不出现
        loginAsOperator(1001L);
        Long federationId = federationService.submit(federationSubmit());
        assertThat(federationStatus(federationId)).isEqualTo(AiSubjectFederationDO.STATUS_PENDING);
        assertThat(discover(crmApplicationId, "alice").getEntries()).hasSize(1);
        assertThat(discover(crmApplicationId, "alice").getModelCatalog()).doesNotContain(ERP_APP);

        // 2) 目标应用里没有这个主体：即使映射已批准，系统也不出现（不按同名推断）
        loginAsOperator(1002L);
        federationService.approve(federationId, 0, "复核通过");
        AiSystemCatalogDTO approved = discover(crmApplicationId, "alice");
        assertThat(approved.getEntries())
                .extracting(AiSystemEntryDTO::getAppCode)
                .containsExactly(CRM_APP, ERP_APP);

        subjectService.disableSubject(erpApplicationId, AiSubjectType.USER, "alice");
        AiSystemCatalogDTO withoutTargetSubject = discover(crmApplicationId, "alice");
        assertThat(withoutTargetSubject.getEntries())
                .extracting(AiSystemEntryDTO::getAppCode)
                .containsExactly(CRM_APP);
        assertThat(withoutTargetSubject.getModelCatalog()).doesNotContain(ERP_APP);
        assertThat(withoutTargetSubject.getCatalogFingerprint()).isNotEqualTo(approved.getCatalogFingerprint());

        // 主体恢复后重新可见（停用只是让系统暂时不出现在目录里，映射仍有效）
        subjectService.syncSubject(erpApplicationId, AiSubjectType.USER, "alice", "Alice", "erp-auth", 2L);
        assertThat(discover(crmApplicationId, "alice").getEntries())
                .extracting(AiSystemEntryDTO::getAppCode)
                .containsExactly(CRM_APP, ERP_APP);

        // 3) 目标系统没有 ACTIVE 授权：系统不出现（此处用 bob 的 ERP 授权反向验证隔离）
        assertThat(discover(erpApplicationId, "bob").getEntries())
                .extracting(AiSystemEntryDTO::getAppCode)
                .containsExactly(ERP_APP);
    }

    @Test
    void deniedCatalogIsIndistinguishableForRegisteredAndUnregisteredSubjects() {
        // 同一个 (应用, 外部用户标识) 在"已登记但无可用系统"与"从未登记"两种状态下，
        // 发现结果必须完全一致（目录指纹只由身份与条目决定，条目为空时不泄露登记状态）
        AiSystemCatalogDTO registeredWithoutAccess = discover(crmApplicationId, "bob");
        jdbcTemplate.update(
                "DELETE FROM ai_subject WHERE application_id = ? AND external_user_id = ?", crmApplicationId, "bob");
        AiSystemCatalogDTO neverRegistered = discover(crmApplicationId, "bob");

        assertThat(registeredWithoutAccess.isDenied()).isTrue();
        assertThat(neverRegistered.isDenied()).isTrue();
        assertThat(neverRegistered.getEntries()).isEmpty();
        assertThat(neverRegistered.getModelCatalog()).isEqualTo("[]");
        assertThat(neverRegistered.getCatalogFingerprint()).isEqualTo(registeredWithoutAccess.getCatalogFingerprint());
        assertThat(neverRegistered.getApplicationId()).isEqualTo(registeredWithoutAccess.getApplicationId());
        assertThat(neverRegistered.getExternalUserId()).isEqualTo(registeredWithoutAccess.getExternalUserId());
    }

    @Test
    void sameExternalUserIdInTwoApplicationsStaysIsolatedAndFederationIsDirectional() {
        AiSystemCatalogDTO crmAlice = discover(crmApplicationId, "alice");
        AiSystemCatalogDTO erpAlice = discover(erpApplicationId, "alice");

        // 同名不同应用：没有联邦映射时互不可见（不推断同一身份）
        assertThat(crmAlice.getEntries())
                .extracting(AiSystemEntryDTO::getAppCode)
                .containsExactly(CRM_APP);
        assertThat(erpAlice.getEntries())
                .extracting(AiSystemEntryDTO::getAppCode)
                .containsExactly(ERP_APP);
        assertThat(crmAlice.getModelCatalog()).doesNotContain(ERP_APP);
        assertThat(erpAlice.getModelCatalog()).doesNotContain(CRM_APP);

        // 有向映射：CRM→ERP 批准后，只有 CRM 侧看得见 ERP；ERP 的 alice 仍然看不见 CRM
        loginAsOperator(1001L);
        Long federationId = federationService.submit(federationSubmit());
        loginAsOperator(1002L);
        federationService.approve(federationId, 0, "复核通过");

        AiSystemCatalogDTO federated = discover(crmApplicationId, "alice");
        assertThat(federated.getEntries())
                .extracting(AiSystemEntryDTO::getAppCode)
                .containsExactly(CRM_APP, ERP_APP);
        AiSystemEntryDTO erp = federated.getEntries().get(1);
        assertThat(erp.getFederationId()).isEqualTo(federationId);
        assertThat(erp.getFederationRevision()).isEqualTo(2L);
        assertThat(erp.getExternalUserId()).isEqualTo("alice");
        assertThat(discover(erpApplicationId, "alice").getEntries())
                .extracting(AiSystemEntryDTO::getAppCode)
                .containsExactly(ERP_APP);
    }

    @Test
    void federationRequiresIndependentApprovalAndRevocationTakesEffectImmediately() {
        loginAsOperator(1001L);
        Long federationId = federationService.submit(federationSubmit());

        // 提交人不能批准自己的申请（独立审批）
        assertCode(
                () -> federationService.approve(federationId, 0, "self"),
                AiErrorCodeConstants.AI_SUBJECT_FEDERATION_APPROVER_CONFLICT);
        loginAsOperator(1002L);
        federationService.approve(federationId, 0, "复核通过");
        assertThat(federationStatus(federationId)).isEqualTo(AiSubjectFederationDO.STATUS_APPROVED);
        assertThat(federationRevision(federationId)).isEqualTo(2L);

        AiSystemCatalogDTO approved = discover(crmApplicationId, "alice");
        AiAnalysisScopeSelectionDTO selection = analysisScopeService.select(selectRequest(
                crmApplicationId, "CROSS_SYSTEM", List.of(CRM_APP, ERP_APP), approved.getCatalogFingerprint()));
        assertThat(selection.getSystems()).hasSize(2);

        // 撤销：下一次发现立即不含目标系统，历史选择核验失败（不静默换目标）
        federationService.revoke(federationId, 1);
        assertThat(federationStatus(federationId)).isEqualTo(AiSubjectFederationDO.STATUS_REVOKED);
        assertThat(discover(crmApplicationId, "alice").getEntries())
                .extracting(AiSystemEntryDTO::getAppCode)
                .containsExactly(CRM_APP);
        assertCode(
                () -> analysisScopeService.verify(selection), AiErrorCodeConstants.AI_ANALYSIS_SCOPE_VERSION_CONFLICT);

        // 撤销幂等 + 重新提交复用同一行（审批痕迹清空、版本继续递增）
        federationService.revoke(federationId, 1);
        loginAsOperator(1001L);
        assertThat(federationService.submit(federationSubmit())).isEqualTo(federationId);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT approved_by FROM ai_subject_federation WHERE id = ?", Long.class, federationId))
                .isNull();
        assertThat(federationRevision(federationId)).isEqualTo(4L);
    }

    @Test
    void scopeSelectionFailsClosedAndDetectsFactChanges() {
        loginAsOperator(1001L);
        Long federationId = federationService.submit(federationSubmit());
        loginAsOperator(1002L);
        federationService.approve(federationId, 0, null);
        AiSystemCatalogDTO catalog = discover(crmApplicationId, "alice");

        // 目录里没有的系统：整体拒绝（不静默剔除）
        assertCode(
                () -> analysisScopeService.select(selectRequest(
                        crmApplicationId, "CROSS_SYSTEM", List.of(CRM_APP, "hr-app"), catalog.getCatalogFingerprint())),
                AiErrorCodeConstants.AI_ANALYSIS_SCOPE_DENIED);
        // 过期目录指纹：409
        assertCode(
                () -> analysisScopeService.select(
                        selectRequest(crmApplicationId, "CROSS_SYSTEM", List.of(CRM_APP, ERP_APP), "stale")),
                AiErrorCodeConstants.AI_ANALYSIS_SCOPE_VERSION_CONFLICT);
        // 跨系统但不含当前系统：拒绝
        assertCode(
                () -> analysisScopeService.select(selectRequest(
                        crmApplicationId, "CROSS_SYSTEM", List.of(ERP_APP), catalog.getCatalogFingerprint())),
                AiErrorCodeConstants.AI_ANALYSIS_SCOPE_DENIED);

        AiAnalysisScopeSelectionDTO selection = analysisScopeService.select(selectRequest(
                crmApplicationId, "CROSS_SYSTEM", List.of(ERP_APP, CRM_APP), catalog.getCatalogFingerprint()));
        assertThat(selection.getTargetSystemCodes()).containsExactly(CRM_APP, ERP_APP);
        assertThat(analysisScopeService.verify(selection).getSystems()).hasSize(2);

        // 撤销 CRM 侧授权：选择的依据事实变化，核验必须失败（fail closed）
        grantService.revokeGrant(crmGrantId("alice"), grantVersion("alice"));
        assertCode(
                () -> analysisScopeService.verify(selection), AiErrorCodeConstants.AI_ANALYSIS_SCOPE_VERSION_CONFLICT);

        // 当前系统失去全部可用范围后：它从目录与模型目录里消失（联邦目标仍按自身授权事实列出），
        // 但任何范围选择都会因缺少当前系统而整体拒绝（不能从无权系统发起跨系统分析）
        AiSystemCatalogDTO afterRevoke = discover(crmApplicationId, "alice");
        assertThat(afterRevoke.getEntries())
                .extracting(AiSystemEntryDTO::getAppCode)
                .doesNotContain(CRM_APP);
        assertThat(afterRevoke.getModelCatalog()).doesNotContain(CRM_APP);
        assertCode(
                () -> analysisScopeService.select(
                        selectRequest(crmApplicationId, "CURRENT_SYSTEM", null, afterRevoke.getCatalogFingerprint())),
                AiErrorCodeConstants.AI_ANALYSIS_SCOPE_DENIED);
    }

    @Test
    void foreignSubjectsCanNeverBeDiscoveredThroughAnotherApplicationsRequest() {
        // bob 只被授权在 ERP 侧：CRM 侧的 bob 目录为空（跨应用不串权）
        assertThat(discover(crmApplicationId, "bob").isDenied()).isTrue();
        // alice 的 ERP 授权不会因为 CRM 侧查询而出现（未批准映射）
        assertThat(discover(crmApplicationId, "alice").getEntries())
                .extracting(AiSystemEntryDTO::getAppCode)
                .containsExactly(CRM_APP);
        // APP 主体（应用自身）与 USER 主体互不串线：APP 主体没有登记授权，目录为空
        assertThat(catalogService
                        .discover(new AiSystemCatalogQueryDTO()
                                .setApplicationId(crmApplicationId)
                                .setSubjectType(AiSubjectType.APP.name()))
                        .isDenied())
                .isTrue();
    }

    private AiSystemCatalogDTO discover(Long applicationId, String externalUserId) {
        return catalogService.discover(new AiSystemCatalogQueryDTO()
                .setApplicationId(applicationId)
                .setSubjectType(AiSubjectType.USER.name())
                .setExternalUserId(externalUserId));
    }

    private AiAnalysisScopeSelectDTO selectRequest(
            Long applicationId, String mode, List<String> targets, String catalogFingerprint) {
        return new AiAnalysisScopeSelectDTO()
                .setApplicationId(applicationId)
                .setSubjectType(AiSubjectType.USER.name())
                .setExternalUserId("alice")
                .setMode(mode)
                .setTargetSystemCodes(targets)
                .setCatalogFingerprint(catalogFingerprint);
    }

    private AiSubjectFederationSubmitDTO federationSubmit() {
        return new AiSubjectFederationSubmitDTO()
                .setSourceApplicationId(crmApplicationId)
                .setSourceSubjectType(AiSubjectType.USER.name())
                .setSourceExternalUserId("alice")
                .setTargetApplicationId(erpApplicationId)
                .setTargetSubjectType(AiSubjectType.USER.name())
                .setTargetExternalUserId("alice");
    }

    private Long createApplication(String appCode, String name) {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(appCode)
                .setName(name)
                .setOrigins(List.of("https://crm.example.com")));
        applicationService.updateStatus(issue.getApplication().getId(), 0, true);
        return issue.getApplication().getId();
    }

    private void grant(
            Long applicationId, String externalUserId, AiResourceType type, String resourceKey, Set<AiAction> actions) {
        grantService.createGrant(
                applicationId,
                AiSubjectType.USER.name(),
                externalUserId,
                type.name(),
                resourceKey,
                actions.stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()));
    }

    private Long crmGrantId(String externalUserId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ai_resource_grant WHERE application_id = ? AND external_user_id = ? "
                        + "AND resource_key = ?",
                Long.class,
                crmApplicationId,
                externalUserId,
                CRM_REPORT);
    }

    private Integer grantVersion(String externalUserId) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM ai_resource_grant WHERE id = ?", Integer.class, crmGrantId(externalUserId));
    }

    private String federationStatus(Long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM ai_subject_federation WHERE id = ?", String.class, id);
    }

    private Long federationRevision(Long id) {
        return jdbcTemplate.queryForObject("SELECT revision FROM ai_subject_federation WHERE id = ?", Long.class, id);
    }

    private void loginAsOperator(Long operatorId) {
        LoginUser loginUser = new LoginUser().setId(operatorId).setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private void cleanUpData() {
        jdbcTemplate.update("DELETE FROM ai_subject_federation");
        for (String appCode : List.of(CRM_APP, ERP_APP)) {
            List<Long> appIds =
                    jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, appCode);
            for (Long appId : appIds) {
                jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE application_id = ?", appId);
                jdbcTemplate.update("DELETE FROM ai_subject WHERE application_id = ?", appId);
                jdbcTemplate.update("DELETE FROM ai_access_ticket WHERE application_id = ?", appId);
                jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", appId);
                jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
            }
        }
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, ErrorCode expected) {
        assertThatThrownBy(callable)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }
}
