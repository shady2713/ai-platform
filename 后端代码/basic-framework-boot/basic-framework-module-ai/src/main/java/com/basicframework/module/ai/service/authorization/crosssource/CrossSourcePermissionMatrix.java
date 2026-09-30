package com.basicframework.module.ai.service.authorization.crosssource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨源权限矩阵（Y05 必产之一）：把"授权来源 × 角色 × 可见字段"渲染成**逐格可断言**的表。
 *
 * <p>矩阵不是文档里的一张静态表格，而是由 {@link CrossSourceCallerRole} 与
 * {@link CrossSourceAccessFacts} <b>算出</b>的。这样做的理由与 A08 的授权矩阵一致：
 * 静态表格会与实现漂移，而由同一份判据算出来的表，语义一旦变化，测试立刻失败。
 *
 * <p>每一格给出四列：角色、来源是否可读、映射是否有权、可见字段数。
 * 越权行为在 {@link #describeViolation} 里逐格写明——"会怎样"必须与"能不能"同等明确，
 * 否则矩阵只回答了拒绝的一半。
 */
public final class CrossSourcePermissionMatrix {

    private static final String VIOLATION_SOURCE = "拒绝参与合并：来源系统/数据集无权";
    private static final String VIOLATION_MAPPING = "拒绝跨系统关联：映射无权（计划合法也不放行）";
    private static final String VIOLATION_NONE = "放行，字段按角色交集裁剪";

    /** 矩阵的一行：某个角色在某个来源上的判定。 */
    public record MatrixRow(
            String sourceRole,
            CrossSourceCallerRole callerRole,
            boolean sourceReadable,
            boolean mappingAuthorized,
            int visibleFieldCount,
            Set<String> visibleFields,
            String violationBehavior) {

        /** 该行是否放行（来源可读且映射有权）。 */
        public boolean allowed() {
            return sourceReadable && mappingAuthorized;
        }
    }

    /**
     * 渲染矩阵。
     *
     * @param roles    调用方角色清单
     * @param facts    各来源的授权事实（按角色）
     * @param planRole 计划声明的来源角色顺序
     */
    public List<MatrixRow> render(
            List<String> planRoles, List<CrossSourceCallerRole> roles, Map<String, CrossSourceAccessFacts> facts) {
        List<MatrixRow> rows = new ArrayList<>();
        Map<String, CrossSourceAccessFacts> source = facts == null ? Map.of() : facts;
        for (CrossSourceCallerRole role : roles) {
            for (String planRole : planRoles) {
                CrossSourceAccessFacts fact = source.get(planRole);
                boolean readable = fact != null && fact.sourceReadable();
                boolean mapping = fact != null && fact.mappingAuthorized();
                // 可见字段按角色裁剪，与来源是否可读无关：角色决定"能看什么"，
                // 来源决定"能看谁的数据"，两者是乘法关系。
                Set<String> visible = role.visibleFields();
                rows.add(new MatrixRow(
                        planRole,
                        role,
                        readable,
                        mapping,
                        visible.size(),
                        visible,
                        describeViolation(readable, mapping)));
            }
        }
        return List.copyOf(rows);
    }

    /** 越权行为逐格写明：来源无权与映射无权是**两种不同的拒绝**，处置也不同。 */
    public static String describeViolation(boolean sourceReadable, boolean mappingAuthorized) {
        if (!sourceReadable) {
            return VIOLATION_SOURCE;
        }
        if (!mappingAuthorized) {
            return VIOLATION_MAPPING;
        }
        return VIOLATION_NONE;
    }

    /**
     * 渲染成可读报告（每行 {@code 来源/角色/来源可读/映射有权/可见字段数/越权行为}）。
     *
     * <p>报告里的"越权行为"是**给人看的**：它说清楚这一格被拒之后会发生什么
     * （拒绝、报哪个编号），而不只是"不允许"。
     */
    public List<String> report(
            List<String> planRoles, List<CrossSourceCallerRole> roles, Map<String, CrossSourceAccessFacts> facts) {
        List<String> lines = new ArrayList<>();
        for (MatrixRow row : render(planRoles, roles, facts)) {
            lines.add(String.format(
                    "%s/%s/sourceReadable=%s/mappingAuthorized=%s/visibleFields=%d/behavior=%s",
                    row.sourceRole(),
                    row.callerRole(),
                    row.sourceReadable(),
                    row.mappingAuthorized(),
                    row.visibleFieldCount(),
                    row.violationBehavior()));
        }
        return List.copyOf(lines);
    }

    /** 按来源角色聚合出"该来源在所有角色下的最小可见字段集"（跨源结果按此裁剪）。 */
    public Map<String, Set<String>> narrowestPerSource(List<String> planRoles, List<CrossSourceCallerRole> roles) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String planRole : planRoles) {
            result.put(planRole, CrossSourceCallerRole.commonFields(Set.copyOf(roles)));
        }
        return result;
    }
}
