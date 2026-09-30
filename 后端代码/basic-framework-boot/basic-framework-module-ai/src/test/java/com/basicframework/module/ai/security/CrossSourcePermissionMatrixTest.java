package com.basicframework.module.ai.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceAccessFacts;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceCallerRole;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourcePermissionMatrix;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 跨源权限矩阵（Y05 必产之一）的逐格校验。
 *
 * <p>矩阵由词表<b>算出</b>而不是抄进文档，因此语义一旦漂移这里立刻失败——
 * 与 A08 授权矩阵同一条理由：静态表格会与实现分家。
 *
 * <p>本类同时钉住"角色决定可见字段"这条独立于"来源决定可否读"的语义：
 * 两者是乘法关系，把它们并成一个布尔值就会退化成"能读就能看全部"。
 */
@DisplayName("Y05 跨源权限矩阵逐格校验")
class CrossSourcePermissionMatrixTest {

    private final CrossSourcePermissionMatrix matrix = new CrossSourcePermissionMatrix();

    private static final List<String> PLAN = List.of("order", "invoice", "payment");

    private static final List<CrossSourceCallerRole> ROLES = List.of(CrossSourceCallerRole.values());

    private static CrossSourceAccessFacts readable(String role) {
        return new CrossSourceAccessFacts(role, "sys-" + role, "y04_" + role, true, true, true);
    }

    @Test
    @DisplayName("矩阵规模：计划来源数 × 角色数，每格都渲染出来")
    void theMatrixCoversEverySourceRoleCombination() {
        Map<String, CrossSourceAccessFacts> facts =
                Map.of("order", readable("order"), "invoice", readable("invoice"), "payment", readable("payment"));

        var rows = matrix.render(PLAN, ROLES, facts);

        assertThat(rows).hasSize(PLAN.size() * ROLES.size());
        // 每个来源 × 每个角色都恰好一格，不漏格
        for (String role : PLAN) {
            for (CrossSourceCallerRole caller : ROLES) {
                assertThat(rows.stream()
                                .filter(row -> row.sourceRole().equals(role) && row.callerRole() == caller)
                                .count())
                        .as("来源 %s × 角色 %s 恰好一格", role, caller)
                        .isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("全部有权时每一格都放行，越权行为写明为字段裁剪")
    void everyCellAllowsWhenAllSourcesAreAuthorized() {
        Map<String, CrossSourceAccessFacts> facts =
                Map.of("order", readable("order"), "invoice", readable("invoice"), "payment", readable("payment"));

        var rows = matrix.render(PLAN, ROLES, facts);

        assertThat(rows).allMatch(CrossSourcePermissionMatrix.MatrixRow::allowed);
        assertThat(rows).allMatch(row -> row.violationBehavior().contains("字段按角色交集裁剪"));
    }

    @Test
    @DisplayName("来源无权的格：越权行为逐格写明是拒绝参与合并")
    void cellsWithAnUnreadableSourceStateTheRejectionBehavior() {
        Map<String, CrossSourceAccessFacts> facts = Map.of(
                "order", readable("order"),
                "invoice", readable("invoice"),
                "payment", new CrossSourceAccessFacts("payment", "sys-payment", "y04_payments", false, true, true));

        var rows = matrix.render(PLAN, ROLES, facts);

        // 三个角色 × payment 一格 = 3 格被拒
        assertThat(rows.stream().filter(row -> !row.allowed()).count()).isEqualTo(ROLES.size());
        assertThat(rows.stream()
                        .filter(row -> row.sourceRole().equals("payment"))
                        .allMatch(row -> row.violationBehavior().contains("拒绝参与合并")))
                .isTrue();
        // 有权的两个来源不受牵连
        assertThat(rows.stream()
                        .filter(row -> row.sourceRole().equals("order")
                                || row.sourceRole().equals("invoice"))
                        .allMatch(CrossSourcePermissionMatrix.MatrixRow::allowed))
                .isTrue();
    }

    @Test
    @DisplayName("映射无权的格：越权行为与来源无权**不同**（拒绝跨系统关联）")
    void cellsWithAnUnauthorizedMappingStateADifferentRejection() {
        Map<String, CrossSourceAccessFacts> facts = Map.of(
                "order", readable("order"),
                "invoice", new CrossSourceAccessFacts("invoice", "sys-erp", "y04_invoices", true, true, false),
                "payment", readable("payment"));

        var rows = matrix.render(PLAN, ROLES, facts);

        var denied =
                rows.stream().filter(row -> row.sourceRole().equals("invoice")).toList();
        assertThat(denied).isNotEmpty();
        assertThat(denied).allMatch(row -> !row.allowed());
        assertThat(denied).allMatch(row -> row.violationBehavior().contains("拒绝跨系统关联"));
        // 关键：这两种拒绝的措辞不同，调用方据此知道该申请哪一级权限
        assertThat(matrix.describeViolation(false, true)).isNotEqualTo(matrix.describeViolation(true, false));
        assertThat(matrix.describeViolation(true, true)).contains("字段按角色交集裁剪");
    }

    @Test
    @DisplayName("角色决定可见字段：同一来源在不同角色下的可见字段数不同")
    void visibleFieldsDependOnTheCallerRoleNotOnlyOnTheSource() {
        Map<String, CrossSourceAccessFacts> facts = Map.of("order", readable("order"));

        var rows = matrix.render(List.of("order"), ROLES, facts);

        var byRole = rows.stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> row.callerRole().name(), row -> row.visibleFieldCount()));
        assertThat(byRole.get("AGGREGATE_READER")).isLessThan(byRole.get("ANALYST"));
        assertThat(byRole.get("ANALYST")).isLessThan(byRole.get("DATA_STEWARD"));
    }

    @Test
    @DisplayName("可见字段按角色取交集：多角色时取最弱")
    void visibleFieldsAreTheIntersectionAcrossRoles() {
        // ANALYST 看不到 source_key；与 DATA_STEWARD 同时持有时仍看不到
        Set<CrossSourceCallerRole> both = Set.of(CrossSourceCallerRole.ANALYST, CrossSourceCallerRole.DATA_STEWARD);
        Set<String> common = CrossSourceCallerRole.commonFields(both);

        assertThat(common).doesNotContain("source_key");
        assertThat(common).contains("amount", "customer_key");
        // 空角色集 → 空交集（fail-closed，不退回并集）
        assertThat(CrossSourceCallerRole.commonFields(Set.of())).isEmpty();
        assertThat(CrossSourceCallerRole.commonFields(null)).isEmpty();
    }

    @Test
    @DisplayName("报告可读：每行含来源/角色/两级授权/可见字段数/越权行为")
    void theReportIsRenderedPerCell() {
        Map<String, CrossSourceAccessFacts> facts = Map.of(
                "order", readable("order"),
                "invoice", new CrossSourceAccessFacts("invoice", "sys-erp", "y04_invoices", true, true, false),
                "payment", new CrossSourceAccessFacts("payment", "sys-payment", "y04_payments", false, true, true));

        var report = matrix.report(PLAN, ROLES, facts);

        assertThat(report).hasSize(PLAN.size() * ROLES.size());
        assertThat(report)
                .allMatch(line -> line.contains("sourceReadable=")
                        && line.contains("mappingAuthorized=")
                        && line.contains("visibleFields=")
                        && line.contains("behavior="));
        // 缺失事实的来源也必须出现在矩阵里（不能因为"查不到"就从表上消失）
        assertThat(matrix.report(List.of("ghost"), List.of(CrossSourceCallerRole.ANALYST), Map.of()))
                .hasSize(1);
        assertThat(matrix.render(List.of("ghost"), List.of(CrossSourceCallerRole.ANALYST), null))
                .allMatch(row -> !row.sourceReadable() && !row.mappingAuthorized());
    }

    @Test
    @DisplayName("每来源最窄可见字段集：跨源结果按此裁剪")
    void narrowestPerSourceIsTheRoleIntersection() {
        var narrowest = matrix.narrowestPerSource(
                PLAN, List.of(CrossSourceCallerRole.ANALYST, CrossSourceCallerRole.DATA_STEWARD));

        assertThat(narrowest).containsOnlyKeys("order", "invoice", "payment");
        assertThat(narrowest.get("order")).doesNotContain("source_key");
    }

    @Test
    @DisplayName("角色词表 fail-closed：未知字段不可见，未知角色名解析为空")
    void theRoleVocabularyIsFailClosed() {
        assertThat(CrossSourceCallerRole.ANALYST.canSee("unknown_field")).isFalse();
        assertThat(CrossSourceCallerRole.ANALYST.canSee(null)).isFalse();
        assertThat(CrossSourceCallerRole.ANALYST.canSee("  AMOUNT  "))
                .as("大小写与空白归一")
                .isTrue();
        // 未知角色名不静默降级到最低角色
        assertThat(CrossSourceCallerRole.parse("superuser")).isEmpty();
        assertThat(CrossSourceCallerRole.parse("")).isEmpty();
        assertThat(CrossSourceCallerRole.parse(null)).isEmpty();
        assertThat(CrossSourceCallerRole.parse("analyst")).isPresent();
        // 可见字段集合不可被调用方改写
        var fields = CrossSourceCallerRole.ANALYST.visibleFields();
        assertThatThrownByImmutable(fields);
    }

    private static void assertThatThrownByImmutable(Set<String> fields) {
        try {
            fields.add("injected");
            org.assertj.core.api.Assertions.fail("可见字段集合必须不可变");
        } catch (UnsupportedOperationException expected) {
            org.assertj.core.api.Assertions.assertThat(expected).isNotNull();
        }
    }
}
