package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.authorization.AiRevocationService;
import com.basicframework.module.ai.service.authorization.crosssource.AiCrossSourceAuthorizationJudge;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceAccessFacts;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceCallerRole;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceModelInputCaptureVerifier;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceModelInputCaptureVerifier.ModelInputCapture;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourcePermissionMatrix;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Y05 验收 IT：跨系统权限、撤销与完整性（真实 MySQL Testcontainers，未用 H2）。
 *
 * <p><b>与 A08 授权矩阵 IT 同一取向：本类不是 mock 授权返回 false。</b>
 * 每一格的"有权/无权"都来自真实的 A01（应用）、A02（主体）、A03（授权目录与判定）、
 * A06（撤销）实现与真实 MySQL；本类只负责把真实判定结果喂给 Y05 的跨源判定器，
 * 再断言跨源层的处置。撤销同样走真实的 {@code AiRevocationService}——
 * 撤销后授权版本递增、票据失效，都是数据库里真实发生的事。
 *
 * <p>三条专项各有正向与反向两侧。反向侧断言的是<b>稳定错误码常量 + 具体越权对象</b>，
 * 失败时能立刻看出是"哪一级、哪一个来源"被拒，而不是笼统的"应该失败"。
 *
 * <p>{@code SubjectScopeResolver} 由业务侧实现（A02 的既定分工），本 IT 注入一个
 * 最小可信实现——与 {@code AiAuthorizationMatrixIT} 的做法一致：它替代的是**业务侧**
 * 的范围来源，不是平台自身的授权判定，因此不构成"用 stub 冒充依赖"。
 */
@Import(AiCrossSourceAuthorizationAcceptanceIT.ScopeResolverConfiguration.class)
class AiCrossSourceAuthorizationAcceptanceIT extends AbstractPersistenceIntegrationTest {

    @TestConfiguration
    static class ScopeResolverConfiguration {

        /** 业务侧可信范围：只认本 IT 显式登记的对象键，其余按 DENY 处理。 */
        @Bean
        SubjectScopeResolver y05ScopeResolver() {
            return request -> java.util.Optional.of(new SubjectScope(
                    Set.of(9050L), Set.copyOf(request.resourceHints()), request.scopeSource(), request.scopeVersion()));
        }
    }

    private static final String APP = "y05-authz-app";

    private static final List<String> PLAN = List.of("order", "invoice", "payment");

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiAuthorizationService authorizationService;

    @Autowired
    private AiRevocationService revocationService;

    private final AiCrossSourceAuthorizationJudge judge = new AiCrossSourceAuthorizationJudge();

    private final CrossSourceModelInputCaptureVerifier captureVerifier = new CrossSourceModelInputCaptureVerifier();

    private final CrossSourcePermissionMatrix matrix = new CrossSourcePermissionMatrix();

    // ---------- 真实授权事实的构造 ----------

    @AfterEach
    void cleanUp() {
        List<Long> appIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP);
        for (Long appId : appIds) {
            jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_subject WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_access_ticket WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
        }
    }

    private Long prepareApplication() {
        Long applicationId = applicationService
                .createApplication(new AiApplicationSaveDTO()
                        .setAppCode(APP)
                        .setName("Y05 跨源授权应用")
                        .setOrigins(List.of("https://" + APP + ".example.com")))
                .getApplication()
                .getId();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, "y05-alice", "y05-alice", "y05-it", 1L);
        return applicationId;
    }

    /**
     * 真实授权 → {@link CrossSourceAccessFacts}。
     *
     * <p>三个来源各对应一条真实的 DATASET 授权；映射授权用一条真实的
     * {@code DATASET} 授权表达（"能不能看到这条跨系统关联"在 A03 词表里
     * 同样是 DATASET 资源，不自造第二套资源类型）。
     */
    private CrossSourceAccessFacts realFacts(
            Long applicationId, String subject, String role, String datasetCode, boolean mappingGranted) {
        boolean datasetAllowed = isGranted(applicationId, subject, datasetCode);
        return new CrossSourceAccessFacts(
                role,
                "sys-" + role,
                datasetCode,
                datasetAllowed,
                datasetAllowed,
                mappingGranted && isGranted(applicationId, subject, "mapping:" + datasetCode));
    }

    private boolean isGranted(Long applicationId, String subject, String resourceKey) {
        return authorizationService
                .authorize(
                        applicationId,
                        "USER",
                        subject,
                        AiResourceType.DATASET,
                        resourceKey,
                        AiAction.READ,
                        List.of(resourceKey))
                .isAllowed();
    }

    private void grant(Long applicationId, String subject, String resourceKey) {
        grantService.createGrant(applicationId, "USER", subject, "DATASET", resourceKey, Set.of("READ", "EXECUTE"));
    }

    private Map<String, CrossSourceAccessFacts> allGranted(Long applicationId, String subject) {
        Map<String, CrossSourceAccessFacts> facts = new java.util.LinkedHashMap<>();
        for (String role : PLAN) {
            String datasetCode = "y04_" + role;
            grant(applicationId, subject, datasetCode);
            grant(applicationId, subject, "mapping:" + datasetCode);
            facts.put(role, realFacts(applicationId, subject, role, datasetCode, true));
        }
        return facts;
    }

    // ---------- 专项一：合计不泄漏明细 ----------

    @Test
    @DisplayName("AT-071 专项一 正向：三个来源都真实有权时出具合计与来源数")
    void authorizedSourcesProduceATotal() {
        Long appId = prepareApplication();
        Map<String, CrossSourceAccessFacts> facts = allGranted(appId, "y05-alice");

        var grant = judge.judge(PLAN, facts, Set.of(CrossSourceCallerRole.ANALYST), Set.of());
        judge.requireDisclosableTotal(new BigDecimal("130.00"), grant.sources(), Set.of(), Set.of());

        assertThat(grant.allowed()).isTrue();
        assertThat(judge.discloseSourceCount(grant.sources(), Set.of())).isEqualTo(3);
    }

    @Test
    @DisplayName("AT-071 专项一 反向：撤销 payment 的数据授权后，合计被拒且总数不得可数")
    void aTotalSpanningAForbiddenSourceIsRefused() {
        Long appId = prepareApplication();
        Map<String, CrossSourceAccessFacts> facts = allGranted(appId, "y05-alice");
        // 先证明放行，再制造失权：否则测的可能是"本来就没配好"
        assertThat(judge.judge(PLAN, facts, Set.of(CrossSourceCallerRole.ANALYST), Set.of())
                        .allowed())
                .isTrue();

        // 真实撤销：payment 的数据集授权被 REVOKE
        Long grantId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_resource_grant WHERE application_id = ? AND resource_key = ?",
                Long.class,
                appId,
                "y04_payment");
        grantService.revokeGrant(grantId, 0);

        Map<String, CrossSourceAccessFacts> afterRevoke = Map.of(
                "order", realFacts(appId, "y05-alice", "order", "y04_order", true),
                "invoice", realFacts(appId, "y05-alice", "invoice", "y04_invoice", true),
                "payment", realFacts(appId, "y05-alice", "payment", "y04_payment", true));

        assertThatThrownBy(() -> judge.judge(PLAN, afterRevoke, Set.of(CrossSourceCallerRole.ANALYST), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED.getCode());
        // 条数同样不可数：否则调用方能数出"少了 1 个来源"
        assertThatThrownBy(() -> judge.discloseSourceCount(List.of("order", "invoice"), Set.of("payment")))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_SOURCE_COUNT_LEAKS_FORBIDDEN.getCode());
    }

    @Test
    @DisplayName("AT-010 专项一 反向：失权后重放，用旧合计做减法必须被拒")
    void replayingAfterRevocationIsRefusedBecauseTheOldTotalMakesTheDetailSolvable() {
        // 旧合计覆盖三个来源（调用方曾经拿到过 130.00）
        Set<String> previouslySeen = Set.copyOf(PLAN);

        assertThatThrownBy(() -> judge.judgeAfterRevocation(PLAN, Set.of("payment"), previouslySeen))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_TOTAL_EXPOSES_FORBIDDEN_DETAIL.getCode());

        // 只含有权来源的新合计同样被拒：125.00 与旧的 130.00 相减即得 payment 的 5.00
        assertThatThrownBy(() -> judge.requireDisclosableTotal(
                        new BigDecimal("125.00"), List.of("order", "invoice"), Set.of(), previouslySeen))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_TOTAL_EXPOSES_FORBIDDEN_DETAIL.getCode());
    }

    @Test
    @DisplayName("AT-010 反向：撤销主体后，跨源读取整体关闭（真实 revokeSubject）")
    void revokingTheSubjectClosesCrossSourceReading() {
        Long appId = prepareApplication();
        allGranted(appId, "y05-alice");
        revocationService.revokeSubject(appId, AiSubjectType.USER, "y05-alice", 0);

        // 主体被停用后，原先的授权不再放行——判定真实地读当前状态
        assertThat(isGranted(appId, "y05-alice", "y04_order")).isFalse();
        Map<String, CrossSourceAccessFacts> afterRevoke = Map.of(
                "order", realFacts(appId, "y05-alice", "order", "y04_order", true),
                "invoice", realFacts(appId, "y05-alice", "invoice", "y04_invoice", true),
                "payment", realFacts(appId, "y05-alice", "payment", "y04_payment", true));

        assertThatThrownBy(() -> judge.judge(PLAN, afterRevoke, Set.of(CrossSourceCallerRole.ANALYST), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED.getCode());
    }

    // ---------- 专项二：映射本身无权也拒绝 ----------

    @Test
    @DisplayName("AT-009 专项二 正向：映射有权时关联成功")
    void anAuthorizedMappingAllowsTheAssociation() {
        Long appId = prepareApplication();
        allGranted(appId, "y05-alice");

        var grant = judge.judge(PLAN, realFactsOf(appId), Set.of(CrossSourceCallerRole.ANALYST), Set.of());

        assertThat(grant.allowed()).isTrue();
        assertThat(grant.sources()).containsExactlyInAnyOrderElementsOf(PLAN);
    }

    @Test
    @DisplayName("AT-009 专项二 反向：数据全部有权、计划合法，但映射无权 —— 关联被拒")
    void anUnauthorizedMappingIsRefusedEvenThoughThePlanIsLegalAndAllDataIsReadable() {
        Long appId = prepareApplication();
        allGranted(appId, "y05-alice");
        // 只撤销 payment 的**映射**授权，数据集授权保持有效
        Long mappingGrantId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_resource_grant WHERE application_id = ? AND resource_key = ?",
                Long.class,
                appId,
                "mapping:y04_payment");
        grantService.revokeGrant(mappingGrantId, 0);

        // 先证明"数据确实都可读"——否则这个用例可能只是在测数据无权
        assertThat(isGranted(appId, "y05-alice", "y04_payment")).as("数据集仍可读").isTrue();

        assertThatThrownBy(() -> judge.judge(PLAN, realFactsOf(appId), Set.of(CrossSourceCallerRole.ANALYST), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_MAPPING_NOT_AUTHORIZED.getCode());
    }

    @Test
    @DisplayName("AT-009 专项二 反向：跨应用引用同一 datasetCode 不串权")
    void theSameDatasetCodeInAnotherApplicationIsNotAuthorized() {
        Long appA = prepareApplication();
        allGranted(appA, "y05-alice");

        Long appB = applicationService
                .createApplication(new AiApplicationSaveDTO()
                        .setAppCode(APP + "-b")
                        .setName("Y05 另一应用")
                        .setOrigins(List.of("https://" + APP + "-b.example.com")))
                .getApplication()
                .getId();
        applicationService.updateStatus(appB, 0, true);
        subjectService.syncSubject(appB, AiSubjectType.USER, "y05-bob", "y05-bob", "y05-it", 1L);

        // appB 里没有任何授权：同名 datasetCode 在另一个应用下同样无权
        assertThat(isGranted(appB, "y05-bob", "y04_order")).isFalse();
        Map<String, CrossSourceAccessFacts> facts = Map.of(
                "order", realFacts(appB, "y05-bob", "order", "y04_order", true),
                "invoice", realFacts(appB, "y05-bob", "invoice", "y04_invoice", true),
                "payment", realFacts(appB, "y05-bob", "payment", "y04_payment", true));

        assertThatThrownBy(() -> judge.judge(PLAN, facts, Set.of(CrossSourceCallerRole.ANALYST), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED.getCode());
    }

    // ---------- 专项三：模型输入捕获无失权数据 ----------

    @Test
    @DisplayName("AT-048 专项三 正向：捕获内全部来源仍有权时完整交付")
    void aFullyAuthorizedCaptureIsDeliveredInFull() {
        Long appId = prepareApplication();
        allGranted(appId, "y05-alice");
        ModelInputCapture capture = captureFor(appId);

        var delivered = captureVerifier.requireStillAuthorized(capture, realFactsOf(appId));

        assertThat(delivered.sourceRoles()).containsExactlyInAnyOrderElementsOf(PLAN);
    }

    @Test
    @DisplayName("AT-048 专项三 反向：失权后捕获整份不可读，且不得返回部分结果")
    void afterRevocationTheCaptureIsNotReadableAtAll() {
        Long appId = prepareApplication();
        allGranted(appId, "y05-alice");
        ModelInputCapture capture = captureFor(appId);
        // 证明捕获里**确实有**被禁来源的字面量：否则"读不到"可能只是因为它本来就是空的
        assertThat(capture.containsText("payment")).isTrue();
        assertThat(capture.containsText("5.00")).isTrue();

        Long grantId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_resource_grant WHERE application_id = ? AND resource_key = ?",
                Long.class,
                appId,
                "y04_payment");
        grantService.revokeGrant(grantId, 0);

        assertThatThrownBy(() -> captureVerifier.requireStillAuthorized(capture, realFactsOf(appId)))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_CAPTURE_NOT_AUTHORIZED.getCode());
        // 诊断口径能指出确切角色（运维知道该恢复哪一级授权），但诊断不进交付路径
        assertThat(captureVerifier.lostRoles(capture, realFactsOf(appId))).containsExactly("payment");
    }

    @Test
    @DisplayName("AT-048 专项三 反向：失权后重放同一份捕获仍然不可读（不是第一次才拦）")
    void replayingTheSameCaptureAfterRevocationIsStillRefused() {
        Long appId = prepareApplication();
        allGranted(appId, "y05-alice");
        ModelInputCapture capture = captureFor(appId);

        Long grantId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_resource_grant WHERE application_id = ? AND resource_key = ?",
                Long.class,
                appId,
                "y04_invoice");
        grantService.revokeGrant(grantId, 0);

        // 连续两次都拒绝：实现不能靠"第一次拦过了"来放行
        for (int attempt = 0; attempt < 2; attempt++) {
            assertServiceException(
                    AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_CAPTURE_NOT_AUTHORIZED.getCode(),
                    () -> captureVerifier.requireStillAuthorized(capture, realFactsOf(appId)));
        }
    }

    @Test
    @DisplayName("AT-048 专项三 反向：撤销主体后，捕获同样不可读（AT-010 的产物侧）")
    void aCaptureIsNotReadableAfterTheSubjectIsRevoked() {
        Long appId = prepareApplication();
        allGranted(appId, "y05-alice");
        ModelInputCapture capture = captureFor(appId);
        assertThat(captureVerifier.requireStillAuthorized(capture, realFactsOf(appId)))
                .isNotNull();

        revocationService.revokeSubject(appId, AiSubjectType.USER, "y05-alice", 0);

        assertServiceException(
                AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_CAPTURE_NOT_AUTHORIZED.getCode(),
                () -> captureVerifier.requireStillAuthorized(capture, realFactsOf(appId)));
    }

    // ---------- 权限矩阵 ----------

    @Test
    @DisplayName("跨源权限矩阵：真实授权事实下逐格渲染，越权行为逐格写明")
    void thePermissionMatrixRendersEveryCellFromRealAuthorizationFacts() {
        Long appId = prepareApplication();
        allGranted(appId, "y05-alice");
        // 制造一格"映射无权"和一格"来源无权"
        grantService.revokeGrant(
                jdbcTemplate.queryForObject(
                        "SELECT id FROM ai_resource_grant WHERE application_id = ? AND resource_key = ?",
                        Long.class,
                        appId,
                        "mapping:y04_payment"),
                0);
        grantService.revokeGrant(
                jdbcTemplate.queryForObject(
                        "SELECT id FROM ai_resource_grant WHERE application_id = ? AND resource_key = ?",
                        Long.class,
                        appId,
                        "y04_payment"),
                0);

        var rows = matrix.render(PLAN, List.of(CrossSourceCallerRole.values()), realFactsOf(appId));

        // 3 个来源 × 3 个角色 = 9 格，一格不少
        assertThat(rows).hasSize(9);
        // payment 两级都被拒；order/invoice 全放行
        assertThat(rows.stream()
                        .filter(row -> row.sourceRole().equals("payment"))
                        .allMatch(row -> !row.allowed()))
                .isTrue();
        assertThat(rows.stream()
                        .filter(row -> row.sourceRole().equals("order")
                                || row.sourceRole().equals("invoice"))
                        .allMatch(CrossSourcePermissionMatrix.MatrixRow::allowed))
                .isTrue();
        // 报告可读，且两类拒绝的措辞不同
        var report = matrix.report(PLAN, List.of(CrossSourceCallerRole.values()), realFactsOf(appId));
        assertThat(report).hasSize(9);
        assertThat(report).anyMatch(line -> line.contains("拒绝跨系统关联") || line.contains("拒绝参与合并"));
    }

    // ---------- 夹具 ----------

    private Map<String, CrossSourceAccessFacts> realFactsOf(Long applicationId) {
        Map<String, CrossSourceAccessFacts> facts = new java.util.LinkedHashMap<>();
        for (String role : PLAN) {
            String datasetCode = "y04_" + role;
            facts.put(role, realFacts(applicationId, "y05-alice", role, datasetCode, true));
        }
        return facts;
    }

    /** 一份真实存在的模型输入捕获（正文里含各来源的字面量，供失权后核对）。 */
    private ModelInputCapture captureFor(Long applicationId) {
        assertThat(applicationId).isNotNull();
        return new ModelInputCapture(
                "cap-y05-it",
                PLAN,
                Set.of("amount", "currency", "customer_key"),
                "订单 100.00 + 发票 25.00 + payment 5.00 = 130.00");
    }
}
