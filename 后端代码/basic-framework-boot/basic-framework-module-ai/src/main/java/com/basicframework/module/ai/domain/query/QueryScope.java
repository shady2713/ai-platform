package com.basicframework.module.ai.domain.query;

import java.util.List;

/**
 * 行范围授权域（D06）：调用方（授权层）给出的**必须**拼进 WHERE 的行级约束。
 *
 * <p>为什么单独建模而不是"再加几个过滤器"：用户/模型的过滤条件与行范围约束来源不同、
 * 可撤销性也不同。行范围由授权层决定，编译器把它强制 AND 进语句，且**不接受空集合**——
 * 空范围意味着"没有授权约束"，那就不应该生成可执行 SQL（宁可拒绝，也不退回全库）。
 *
 * <p>条件值同样走绑定参数；条件列必须来自数据集目录（由编译器再次校验）。
 */
public record QueryScope(List<Condition> conditions) {

    /** 行范围条件：物理列 + 操作符 + 已绑定取值。 */
    public record Condition(String sourceColumn, String operator, List<Object> values) {

        public Condition {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public QueryScope {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    /** 是否具备可编译的行范围（空集合表示"没有约束"，编译器拒绝生成 SQL）。 */
    public boolean isEffective() {
        return !conditions.isEmpty();
    }

    /** 便捷构造：等值条件（最常见的行范围形式）。 */
    public static QueryScope eq(String sourceColumn, Object value) {
        return new QueryScope(List.of(new Condition(sourceColumn, "EQ", List.of(value))));
    }

    /** 便捷构造：集合条件（例如"可见客户集合"）。 */
    public static QueryScope in(String sourceColumn, List<Object> values) {
        return new QueryScope(List.of(new Condition(sourceColumn, "IN", values)));
    }
}
