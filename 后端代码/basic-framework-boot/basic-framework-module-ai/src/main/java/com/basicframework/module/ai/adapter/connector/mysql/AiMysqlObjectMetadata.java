package com.basicframework.module.ai.adapter.connector.mysql;

import java.util.List;

/**
 * 授权对象元数据（D03）：只包含**白名单内**的表/视图及其列。
 *
 * <p>类型只区分 {@code TABLE} 与 {@code VIEW}：授权与查询语义只依赖这个区分，
 * 引擎细节（分区、存储引擎）不进契约，避免把上游实现细节泄漏成平台契约。
 */
public record AiMysqlObjectMetadata(String schema, String name, String type, List<Column> columns) {

    /** 对象类型：基表。 */
    public static final String TYPE_TABLE = "TABLE";

    /** 对象类型：视图。 */
    public static final String TYPE_VIEW = "VIEW";

    public AiMysqlObjectMetadata {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /** 规范化对象名（{@code schema.object}，小写），与授权白名单同一词表。 */
    public String qualifiedName() {
        return schema.toLowerCase(java.util.Locale.ROOT) + "." + name.toLowerCase(java.util.Locale.ROOT);
    }

    /** 列元数据：名称、上游类型与可空性（不做类型映射，映射在数据集卡片里做）。 */
    public record Column(String name, String dataType, boolean nullable) {}
}
