package com.basicframework.module.ai.domain.semantic;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 语义类型与上游数据类型的兼容映射（D04）。
 *
 * <p>数据集定义里的字段类型是**语义类型**（平台侧承诺），上游列类型是数据库事实。
 * 发布前的验证必须同时满足：列存在 + 类型兼容。这里给出唯一映射，避免各处各写一份判断。
 *
 * <p>只映射平台实际会用的类型；未知上游类型一律不兼容（保守拒绝，宁可让定义改小）。
 */
public final class AiSemanticTypes {

    /** 语义类型：文本。 */
    public static final String STRING = "STRING";

    /** 语义类型：整数。 */
    public static final String NUMBER = "NUMBER";

    /** 语义类型：小数。 */
    public static final String DECIMAL = "DECIMAL";

    /** 语义类型：布尔。 */
    public static final String BOOLEAN = "BOOLEAN";

    /** 语义类型：日期。 */
    public static final String DATE = "DATE";

    /** 语义类型：日期时间。 */
    public static final String DATETIME = "DATETIME";

    /** 语义类型：枚举（取值由定义声明，上游只提供存储）。 */
    public static final String ENUM = "ENUM";

    /** 允许的语义类型白名单。 */
    public static final Set<String> TYPES = Set.of(STRING, NUMBER, DECIMAL, BOOLEAN, DATE, DATETIME, ENUM);

    /** 可参与 SUM/AVG 的语义类型。 */
    public static final Set<String> AGGREGATABLE = Set.of(NUMBER, DECIMAL);

    /** 可作为时间轴的语义类型。 */
    public static final Set<String> TIME_TYPES = Set.of(DATE, DATETIME);

    /** 语义类型 -> 可接受的上游 data_type（小写）。 */
    private static final Map<String, Set<String>> UPSTREAM_TYPES = Map.of(
            STRING, Set.of("varchar", "char", "tinytext", "text", "mediumtext", "longtext", "json"),
            NUMBER, Set.of("tinyint", "smallint", "mediumint", "int", "integer", "bigint", "year"),
            DECIMAL, Set.of("decimal", "numeric", "float", "double", "real"),
            BOOLEAN, Set.of("tinyint", "bit", "bool", "boolean"),
            DATE, Set.of("date"),
            DATETIME, Set.of("datetime", "timestamp"),
            ENUM, Set.of("enum", "varchar", "char"));

    private AiSemanticTypes() {}

    /** 语义类型是否被上游类型满足（大小写不敏感；未知上游类型不兼容）。 */
    public static boolean isCompatible(String semanticType, String upstreamDataType) {
        if (semanticType == null || upstreamDataType == null) {
            return false;
        }
        Set<String> accepted = UPSTREAM_TYPES.get(semanticType.toUpperCase(Locale.ROOT));
        return accepted != null && accepted.contains(upstreamDataType.trim().toLowerCase(Locale.ROOT));
    }
}
