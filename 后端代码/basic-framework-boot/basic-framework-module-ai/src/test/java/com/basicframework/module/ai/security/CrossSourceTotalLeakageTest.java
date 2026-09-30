package com.basicframework.module.ai.security;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_ROLE_NOT_AUTHORIZED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_SOURCE_COUNT_LEAKS_FORBIDDEN;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_TOTAL_EXPOSES_FORBIDDEN_DETAIL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.authorization.crosssource.AiCrossSourceAuthorizationErrors;
import com.basicframework.module.ai.service.authorization.crosssource.AiCrossSourceAuthorizationJudge;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceAccessFacts;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceCallerRole;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 专项一：组合多个有权总数不得暴露已禁止的明细（Y05）。
 *
 * <p>本类的重点是<b>反向</b>。正向（全部有权时出合计）只证明功能可用，
 * 反向才证明安全语义成立——因此每个用例都写成"必须拒绝，且拒绝编号正确"，
 * 而不是"拒绝或不拒绝都行"。
 */
@DisplayName("Y05 专项一：合计不得暴露已禁止明细")
class CrossSourceTotalLeakageTest {

    private final AiCrossSourceAuthorizationJudge judge = new AiCrossSourceAuthorizationJudge();

    private static final List<String> PLAN = List.of("order", "invoice", "payment");

    private static CrossSourceAccessFacts granted(String role) {
        return new CrossSourceAccessFacts(role, "sys-" + role, "y04_" + role, true, true, true);
    }

    // ---------- 正向 ----------

    @Test
    @DisplayName("正向：三个来源全部有权时出具合计与来源数")
    void allAuthorizedSourcesProduceATotal() {
        Map<String, CrossSourceAccessFacts> facts =
                Map.of("order", granted("order"), "invoice", granted("invoice"), "payment", granted("payment"));

        var grant = judge.judge(PLAN, facts, Set.of(CrossSourceCallerRole.ANALYST), Set.of());

        assertThat(grant.allowed()).isTrue();
        assertThat(grant.sources()).containsExactly("order", "invoice", "payment");
        // 合计可出具：三个来源都无权缺失时，"总额"不含任何被禁部分
        judge.requireDisclosableTotal(new BigDecimal("130.00"), grant.sources(), Set.of(), Set.of());
        // 条数可数：没有被禁来源，来源数就是来源数
        assertThat(judge.discloseSourceCount(grant.sources(), Set.of())).isEqualTo(3);
    }

    // ---------- 反向：差额可解 ----------

    @Test
    @DisplayName("反向：被禁来源参与口径时拒绝出具合计，调用方无法用有权部分做减法")
    void aTotalSpanningAForbiddenSourceIsRefusedSoItCannotBeDifferenced() {
        Map<String, CrossSourceAccessFacts> facts = Map.of(
                "order",
                granted("order"),
                "invoice",
                granted("invoice"),
                // payment 系统无权：调用方有权 order+invoice 共 125.00，但没有 payment 的 5.00
                "payment",
                new CrossSourceAccessFacts("payment", "sys-payment", "y04_payments", false, true, true));

        assertThatThrownBy(() -> judge.judge(PLAN, facts, Set.of(CrossSourceCallerRole.ANALYST), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                // 断言**具体错误码常量**而不是裸数字：编号是长期协议的一部分
                .isEqualTo(AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED.getCode());

        // 即使调用方已经自己算出了有权部分（125.00），平台也不出具"总额"
        assertThatThrownBy(() -> judge.requireDisclosableTotal(
                        new BigDecimal("130.00"), List.of("order", "invoice"), Set.of("payment"), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AI_CROSS_SOURCE_AUTHZ_TOTAL_EXPOSES_FORBIDDEN_DETAIL.getCode());
    }

    @Test
    @DisplayName("反向：差额可解——调用方此前拿到过含被禁来源的合计，失权后重放必须被拒")
    void replayingAfterRevocationIsRefusedBecauseTheOldTotalMakesTheDetailSolvable() {
        // 失权后重放：payment 从有权变成无权，而调用方**曾经**见过含 payment 的合计
        Set<String> lost = Set.of("payment");
        Set<String> previouslySeen = Set.of("order", "invoice", "payment");

        assertThatThrownBy(() -> judge.judgeAfterRevocation(PLAN, lost, previouslySeen))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AI_CROSS_SOURCE_AUTHZ_TOTAL_EXPOSES_FORBIDDEN_DETAIL.getCode());

        // 同一份历史 + 一个**只含有权来源**的请求：相减仍可解 payment，因此同样拒绝
        assertThatThrownBy(() -> judge.requireDisclosableTotal(
                        new BigDecimal("125.00"), List.of("order", "invoice"), Set.of(), previouslySeen))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AI_CROSS_SOURCE_AUTHZ_TOTAL_EXPOSES_FORBIDDEN_DETAIL.getCode());
    }

    @Test
    @DisplayName("反向：条数可数——来源计数同样不得暴露被禁来源")
    void sourceCountIsAlsoRefusedWhenItWouldRevealAForbiddenSource() {
        // 差额不可解（调用方从未见过 payment），但"这次合并了几个来源"仍会泄露存在性
        assertThatThrownBy(() -> judge.discloseSourceCount(List.of("order", "invoice"), Set.of("payment")))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AI_CROSS_SOURCE_AUTHZ_SOURCE_COUNT_LEAKS_FORBIDDEN.getCode());
    }

    // ---------- 反向：失权但从未见过（错误码与"可反推"不同） ----------

    @Test
    @DisplayName("反向：失权且从未见过该来源时，编号是 SOURCE_NOT_AUTHORIZED（该去申请授权而非销毁旧产物）")
    void aLossWithoutPriorExposureIsReportedAsUnauthorizedNotAsDifferencing() {
        assertThatThrownBy(() -> judge.judgeAfterRevocation(PLAN, Set.of("payment"), Set.of("order", "invoice")))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED.getCode());
    }

    // ---------- 反向：拒绝消息不得点名被禁角色 ----------

    @Test
    @DisplayName("反向：拒绝消息不得包含被禁角色名——错误信息本身不能成为枚举通道")
    void theRejectionMessageNeverNamesTheForbiddenRole() {
        Map<String, CrossSourceAccessFacts> facts = Map.of(
                "order", granted("order"),
                "invoice", granted("invoice"),
                "payment", new CrossSourceAccessFacts("payment", "sys-payment", "y04_payments", false, true, true));

        assertThatThrownBy(() -> judge.judge(PLAN, facts, Set.of(CrossSourceCallerRole.ANALYST), Set.of()))
                .isInstanceOf(ServiceException.class)
                // 断言的是"消息里既没有角色名也没有来源系统/数据集标识"：
                // 角色名会告诉调用方存在一个回款来源，消息必须是最小化的静态文案
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("payment"))
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("sys-payment"))
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("y04_payments"))
                .satisfies(thrown -> assertThat(thrown.getMessage())
                        .isEqualTo(AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED.getMsg()));
    }

    // ---------- 反向：入参非法一律 fail-closed ----------

    @Test
    @DisplayName("反向：入参非法时拒绝而不是按默认放行")
    void invalidInputIsRefusedInsteadOfDefaultingToAllow() {
        Map<String, CrossSourceAccessFacts> facts = Map.of("order", granted("order"));

        // 计划来源为空
        assertThatThrownBy(() -> judge.judge(List.of(), facts, Set.of(CrossSourceCallerRole.ANALYST), Set.of()))
                .isInstanceOf(ServiceException.class);
        // 角色清单为空：没有角色就没有可见字段，按放行等于交出全量
        assertThatThrownBy(() -> judge.judge(List.of("order"), facts, Set.of(), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AI_CROSS_SOURCE_AUTHZ_ROLE_NOT_AUTHORIZED.getCode());
        // 事实缺失按无权处理（fail-closed），不"按计划自称的有权放行"
        assertThatThrownBy(() -> judge.judge(
                        List.of("order", "ghost"), facts, Set.of(CrossSourceCallerRole.DATA_STEWARD), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED.getCode());
        // 空白角色名
        assertThatThrownBy(() -> judge.judge(List.of(" "), facts, Set.of(CrossSourceCallerRole.ANALYST), Set.of()))
                .isInstanceOf(ServiceException.class);
        // 合计金额为 null
        assertThatThrownBy(() -> judge.requireDisclosableTotal(null, List.of("order"), Set.of(), Set.of()))
                .isInstanceOf(ServiceException.class);
        // 无被禁来源时，null 来源清单归零而不是抛错（缺省即"零个来源"）
        assertThat(judge.discloseSourceCount(null, Set.of())).isZero();
    }

    @Test
    @DisplayName("授权凭据 exposes()：未放行的凭据即使字段在集合里也不暴露任何字段")
    void aGrantThatIsNotAllowedExposesNoFieldEvenWhenTheFieldIsInItsSet() {
        // allowed=false 且 visibleFields 非空：exposes 必须仍为 false，
        // 否则"拿一份旧凭据重放"就能把字段带出来
        var stale = new AiCrossSourceAuthorizationJudge.CrossSourceGrant(
                List.of("order"), Set.of("amount", "source_key"), Set.of(), false);

        assertThat(stale.allowed()).isFalse();
        assertThat(stale.exposes("amount")).isFalse();
        assertThat(stale.exposes("source_key")).isFalse();
        assertThat(stale.asRoleMap()).containsExactly(java.util.Map.entry("order", Boolean.TRUE));
    }

    @Test
    @DisplayName("授权凭据的防御性归零：null 集合归一为空而不是保留 null")
    void theGrantRecordNormalizesNullCollections() {
        var normalized = new AiCrossSourceAuthorizationJudge.CrossSourceGrant(null, null, null, true);

        assertThat(normalized.sources()).isEmpty();
        assertThat(normalized.visibleFields()).isEmpty();
        assertThat(normalized.callerRoles()).isEmpty();
        assertThat(normalized.asRoleMap()).isEmpty();
        // 空集合下 exposes 一律 false（fail-closed）
        assertThat(normalized.exposes("amount")).isFalse();
    }

    @Test
    @DisplayName("反向：失权集合为空时放行（撤销重放的对照路径，避免把没失权也拦掉）")
    void anEmptyLostRoleSetStillAllowsTheReplay() {
        var grant = judge.judgeAfterRevocation(PLAN, Set.of(), previouslyNothing());

        assertThat(grant.allowed()).isTrue();
        assertThat(grant.sources()).containsExactly("order", "invoice", "payment");
        // null 与空集合等价：都没失权
        assertThat(judge.judgeAfterRevocation(PLAN, null, previouslyNothing()).allowed())
                .isTrue();
        // 计划非法时仍然拒绝，不因为"没失权"就放行
        assertThatThrownBy(() -> judge.judgeAfterRevocation(List.of(), Set.of(), previouslyNothing()))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("反向：来源无权且调用方此前见过该来源 → 报『可反推』编号而非『无权』编号")
    void aDenialThatIsAlsoDifferenciableReportsTheDifferencingCode() {
        Map<String, CrossSourceAccessFacts> facts = Map.of(
                "order", granted("order"),
                "invoice", granted("invoice"),
                "payment", new CrossSourceAccessFacts("payment", "sys-payment", "y04_payments", false, true, true));

        // previouslySeen 覆盖 payment：既无权、又能与旧合计相减 → 报 TOTAL_EXPOSES
        assertThatThrownBy(() -> judge.judge(PLAN, facts, Set.of(CrossSourceCallerRole.ANALYST), Set.copyOf(PLAN)))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AI_CROSS_SOURCE_AUTHZ_TOTAL_EXPOSES_FORBIDDEN_DETAIL.getCode());
    }

    @Test
    @DisplayName("反向：事实集合为空时判定直接拒绝（不得把『没有事实』当成『全部有权』）")
    void anEmptyFactSetIsRefusedInsteadOfBeingReadAsAllAuthorized() {
        // judge() 路径：事实为空即入参非法，fail-closed 拒绝
        assertThatThrownBy(() -> judge.judge(PLAN, Map.of(), Set.of(CrossSourceCallerRole.ANALYST), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_REQUEST_INVALID.getCode());
    }

    @Test
    @DisplayName("反向：逐格判定在事实为空时把每一格都判为不可放行，而不是默认放行")
    void evaluateEachRendersEveryCellAsDeniedWhenNoFactsExist() {
        // evaluateEach 是矩阵渲染口径：**不抛异常**，但每一格都必须判为不可放行。
        // 如果这里默认放行，矩阵上就会显示一整片"有权"，比报错危险得多。
        var verdicts = judge.evaluateEach(PLAN, Map.of(), Set.of(CrossSourceCallerRole.ANALYST));

        assertThat(verdicts).hasSize(3);
        assertThat(verdicts).allMatch(verdict -> !verdict.allowed());
        assertThat(verdicts).allMatch(verdict -> "SOURCE_FACTS_MISSING".equals(verdict.denyReason()));
        // 空角色集同样每一格不可放行
        assertThat(judge.evaluateEach(PLAN, Map.of(), Set.of())).allMatch(verdict -> !verdict.allowed());
    }

    @Test
    @DisplayName("反向：执行记录不存在的 404 出口有稳定编号，且本域一律不可重试")
    void theNotExistsExitIsStableAndTheDomainIsNeverRetryable() {
        var notExists = AiCrossSourceAuthorizationErrors.executionNotExists();

        assertThat(((ServiceException) notExists).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_EXECUTION_NOT_EXISTS.getCode());
        assertThat(AiCrossSourceAuthorizationErrors.retryable(null)).isFalse();
    }

    private static Set<String> previouslyNothing() {
        return Set.of();
    }
}
