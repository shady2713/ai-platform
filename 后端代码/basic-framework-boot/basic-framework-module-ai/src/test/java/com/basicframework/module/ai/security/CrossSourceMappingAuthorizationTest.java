package com.basicframework.module.ai.security;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_MAPPING_NOT_AUTHORIZED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.service.authorization.crosssource.AiCrossSourceAuthorizationErrors;
import com.basicframework.module.ai.service.authorization.crosssource.AiCrossSourceAuthorizationJudge;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceAccessFacts;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceCallerRole;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 专项二：映射本身无权也拒绝（Y05）。
 *
 * <p>映射（源键↔统一实体）是一份**跨系统事实**。知道"C-001 在系统 A 与系统 B 是同一实体"
 * 已经泄露了两个系统之间的对应关系，因此"我只是在做关联"不构成豁免理由：
 * 两个数据集都能读、查询计划完全合法，只要映射无权，关联仍然必须被拒。
 *
 * <p>本类的核心是钉住"数据可读 ≠ 映射可读"这两个**独立**判据不会合并。
 */
@DisplayName("Y05 专项二：映射无权即拒绝")
class CrossSourceMappingAuthorizationTest {

    private final AiCrossSourceAuthorizationJudge judge = new AiCrossSourceAuthorizationJudge();

    private static final List<String> PLAN = List.of("order", "invoice");

    @Test
    @DisplayName("正向：映射有权时关联成功，并按角色交集裁剪可见字段")
    void anAuthorizedMappingAllowsTheAssociation() {
        Map<String, CrossSourceAccessFacts> facts = Map.of(
                "order", new CrossSourceAccessFacts("order", "sys-crm", "y04_orders", true, true, true),
                "invoice", new CrossSourceAccessFacts("invoice", "sys-erp", "y04_invoices", true, true, true));

        var grant = judge.judge(PLAN, facts, Set.of(CrossSourceCallerRole.ANALYST), Set.of());

        assertThat(grant.allowed()).isTrue();
        // ANALYST 可见 customer_key，但看不到 source_key（那是 DATA_STEWARD 才有）
        assertThat(grant.exposes("customer_key")).isTrue();
        assertThat(grant.exposes("source_key")).isFalse();
    }

    @Test
    @DisplayName("反向：两个数据集都能读、计划合法，但映射无权 —— 仍然拒绝关联")
    void anUnauthorizedMappingIsRefusedEvenThoughThePlanIsLegalAndBothDatasetsAreReadable() {
        Map<String, CrossSourceAccessFacts> facts = Map.of(
                "order", new CrossSourceAccessFacts("order", "sys-crm", "y04_orders", true, true, true),
                // invoice 数据集可读（系统、数据集都为真），但**映射无权**
                "invoice", new CrossSourceAccessFacts("invoice", "sys-erp", "y04_invoices", true, true, false));

        assertThatThrownBy(() -> judge.judge(PLAN, facts, Set.of(CrossSourceCallerRole.ANALYST), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AI_CROSS_SOURCE_AUTHZ_MAPPING_NOT_AUTHORIZED.getCode());
    }

    @Test
    @DisplayName("反向：映射无权不得被『我只是在做关联』绕过——只读数据的路径同样不存在放行分支")
    void thereIsNoAssociationFreePathThatBypassesMappingAuthorization() {
        // sourceReadable() 只看系统+数据集两级；fullyAuthorized() 才把映射算进来。
        // 这两个方法必须给出**不同**答案，否则"只读数据不关联"会退化成映射豁免。
        CrossSourceAccessFacts noMapping =
                new CrossSourceAccessFacts("invoice", "sys-erp", "y04_invoices", true, true, false);
        assertThat(noMapping.sourceReadable()).as("数据可读").isTrue();
        assertThat(noMapping.fullyAuthorized()).as("整体仍无权").isFalse();
        assertThat(noMapping.denyReason()).isEqualTo("ENTITY_MAPPING_NOT_AUTHORIZED");
    }

    @Test
    @DisplayName("反向：系统无权与映射无权是两种不同的拒绝，处置也不同")
    void systemDenialAndMappingDenialAreDifferentConclusions() {
        CrossSourceAccessFacts noSystem = new CrossSourceAccessFacts("x", "sys-x", "ds-x", false, true, true);
        CrossSourceAccessFacts noDataset = new CrossSourceAccessFacts("x", "sys-x", "ds-x", true, false, true);
        CrossSourceAccessFacts noMapping = new CrossSourceAccessFacts("x", "sys-x", "ds-x", true, true, false);

        assertThat(noSystem.denyReason()).isEqualTo("SOURCE_SYSTEM_NOT_AUTHORIZED");
        assertThat(noDataset.denyReason()).isEqualTo("SOURCE_DATASET_NOT_AUTHORIZED");
        assertThat(noMapping.denyReason()).isEqualTo("ENTITY_MAPPING_NOT_AUTHORIZED");
        // 三者都不 fullyAuthorized，但原因不同：调用方据此知道该申请哪一级权限
        assertThat(noSystem.fullyAuthorized()).isFalse();
        assertThat(noDataset.fullyAuthorized()).isFalse();
        assertThat(noMapping.fullyAuthorized()).isFalse();
    }

    @Test
    @DisplayName("反向：逐格判定对映射无权的来源给出 ENTITY_MAPPING_NOT_AUTHORIZED（矩阵与判定同源）")
    void theMatrixAndTheJudgeAgreeOnMappingDenial() {
        Map<String, CrossSourceAccessFacts> facts = Map.of(
                "order", new CrossSourceAccessFacts("order", "sys-crm", "y04_orders", true, true, true),
                "invoice", new CrossSourceAccessFacts("invoice", "sys-erp", "y04_invoices", true, true, false));

        var verdicts = judge.evaluateEach(PLAN, facts, Set.of(CrossSourceCallerRole.ANALYST));

        assertThat(verdicts).hasSize(2);
        assertThat(verdicts.get(0).allowed()).isTrue();
        assertThat(verdicts.get(1).allowed()).isFalse();
        assertThat(verdicts.get(1).denyReason()).isEqualTo("ENTITY_MAPPING_NOT_AUTHORIZED");
        // 事实缺失同样被标为不可放行，理由与"映射无权"不同，便于诊断
        var missing = judge.evaluateEach(List.of("ghost"), Map.of(), Set.of(CrossSourceCallerRole.ANALYST));
        assertThat(missing.get(0).allowed()).isFalse();
        assertThat(missing.get(0).denyReason()).isEqualTo("SOURCE_FACTS_MISSING");
        // 角色为空时即便来源全有权也不放行
        var noRole = judge.evaluateEach(PLAN, facts, Set.of());
        assertThat(noRole).allMatch(verdict -> !verdict.allowed());
    }

    @Test
    @DisplayName("反向：拒绝消息不点名被禁映射所属的来源")
    void mappingDenialDoesNotNameTheSource() {
        Map<String, CrossSourceAccessFacts> facts = Map.of(
                "order", new CrossSourceAccessFacts("order", "sys-crm", "y04_orders", true, true, true),
                "invoice", new CrossSourceAccessFacts("invoice", "sys-erp", "y04_invoices", true, true, false));

        assertThatThrownBy(() -> judge.judge(PLAN, facts, Set.of(CrossSourceCallerRole.ANALYST), Set.of()))
                .isInstanceOf(ServiceException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("invoice"))
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("sys-erp"))
                .satisfies(thrown -> assertThat(thrown.getMessage())
                        .isEqualTo(AI_CROSS_SOURCE_AUTHZ_MAPPING_NOT_AUTHORIZED.getMsg()));
    }

    @Test
    @DisplayName("映射无权与来源无权走**不同**的稳定编号，处置也不同")
    void theTwoDenialPathsUseStableConstants() {
        // 映射专用出口在错误登记册里有独立编号，便于调用方区分"该申请映射权限"
        assertThat(AI_CROSS_SOURCE_AUTHZ_MAPPING_NOT_AUTHORIZED.getCode()).isEqualTo(1_003_018_002);
        // 来源无权是另一个编号
        assertThat(AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED.getCode()).isEqualTo(1_003_018_001);
        // 两者都不可重试：授权拒绝重试没有意义
        assertThat(AiCrossSourceAuthorizationErrors.retryable(AI_CROSS_SOURCE_AUTHZ_MAPPING_NOT_AUTHORIZED))
                .isFalse();
        assertThat(AiCrossSourceAuthorizationErrors.retryable(AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED))
                .isFalse();
        assertThat(AiCrossSourceAuthorizationErrors.retryable(AI_CROSS_SOURCE_AUTHZ_REQUEST_INVALID))
                .isFalse();
    }

    @Test
    @DisplayName("来源事实的 key()：角色缺失时退化为 系统/数据集，仍是稳定键")
    void theSourceKeyFallsBackWhenTheRoleIsMissing() {
        CrossSourceAccessFacts withRole =
                new CrossSourceAccessFacts("order", "sys-crm", "y04_orders", true, true, true);
        assertThat(withRole.key()).isEqualTo("order");

        // 角色为 null/空白时不抛 NPE，退化为系统/数据集拼接
        assertThat(new CrossSourceAccessFacts(null, "sys-crm", "y04_orders", true, true, true).key())
                .isEqualTo("sys-crm/y04_orders");
        assertThat(new CrossSourceAccessFacts("  ", "sys-crm", "y04_orders", true, true, true).key())
                .isEqualTo("sys-crm/y04_orders");
        // 系统与数据集也缺失时给出空键而不是崩溃
        assertThat(new CrossSourceAccessFacts(null, null, null, true, true, true).key())
                .isEqualTo("/");
    }

    @Test
    @DisplayName("来源事实的 denyReason()：全部有权时返回 null（三级都真）")
    void denyReasonIsNullOnlyWhenEveryLevelIsGranted() {
        assertThat(new CrossSourceAccessFacts("order", "sys", "ds", true, true, true).denyReason())
                .isNull();
        // 任一级为假都必须给出原因，且先判系统再判数据集
        assertThat(new CrossSourceAccessFacts("o", "s", "d", false, false, false).denyReason())
                .isEqualTo("SOURCE_SYSTEM_NOT_AUTHORIZED");
        assertThat(new CrossSourceAccessFacts("o", "s", "d", true, false, false).denyReason())
                .isEqualTo("SOURCE_DATASET_NOT_AUTHORIZED");
    }
}
