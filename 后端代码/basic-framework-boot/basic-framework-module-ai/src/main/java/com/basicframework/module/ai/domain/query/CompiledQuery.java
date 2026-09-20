package com.basicframework.module.ai.domain.query;

import java.util.List;

/**
 * 编译结果（D06）：只读 SQL 结构 + 绑定参数 + 结果列 Schema + 超时/行数预算。
 *
 * <p>不含数据库地址与凭据；SQL 文本里没有任何数据值（全部走 {@code ?} 绑定），
 * 因此它可以安全地进日志、审计与快照比对。标识符只来自审核目录，
 * 语句结构在编译期固定（无 JOIN、无子查询拼接、无动态标识符）。
 */
public record CompiledQuery(
        String sql,
        List<SqlParameter> parameters,
        List<ResultColumn> resultColumns,
        int maxRows,
        int timeoutMillis,
        Long datasetVersionId,
        String planHash,
        String sourceObject) {

    /** 结果列 Schema：逻辑码 + 展示标签 + 语义类型（不含数据）。 */
    public record ResultColumn(String code, String label, String type) {}

    public CompiledQuery {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        resultColumns = resultColumns == null ? List.of() : List.copyOf(resultColumns);
    }

    /** 绑定值（按占位符顺序；只给执行器使用，不参与日志）。 */
    public List<Object> parameterValues() {
        return parameters.stream().map(SqlParameter::value).toList();
    }

    @Override
    public String toString() {
        return "CompiledQuery[source=" + sourceObject + ", columns=" + resultColumns.size() + ", parameters="
                + parameters.size() + ", maxRows=" + maxRows + ", timeoutMillis=" + timeoutMillis + "]";
    }
}
