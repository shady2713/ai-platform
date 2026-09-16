package com.basicframework.framework.common.pojo;

import jakarta.validation.constraints.Pattern;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 排序字段 DTO
 *
 * 类名加了 ing 的原因是，避免和 ES SortField 重名。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SortingField implements Serializable {

    /**
     * 顺序 - 升序
     */
    public static final String ORDER_ASC = "asc";
    /**
     * 顺序 - 降序
     */
    public static final String ORDER_DESC = "desc";

    /**
     * 合法字段名正则：仅允许字母、数字、下划线
     *
     * 同时供 {@link #field} 的 Bean Validation 注解与 {@link #isValidField(String)} 使用，保证单一来源
     */
    public static final String FIELD_REGEX = "^[a-zA-Z0-9_]{1,64}$";

    /**
     * 合法排序方向正则：与 {@link #ORDER_ASC}/{@link #ORDER_DESC} 组成的封闭域一致
     *
     * 同时供 {@link #order} 的 Bean Validation 注解与 {@link #isValidOrder(String)} 使用，保证单一来源
     */
    public static final String ORDER_REGEX = "^(asc|desc)$";

    /**
     * {@link #FIELD_REGEX} 的预编译形式
     */
    private static final java.util.regex.Pattern FIELD_PATTERN = java.util.regex.Pattern.compile(FIELD_REGEX);

    /**
     * 字段（仅允许字母、数字、下划线，防止 SQL 注入）
     */
    @Pattern(regexp = FIELD_REGEX, message = "排序字段名不合法")
    private String field;
    /**
     * 顺序（仅允许 asc 或 desc）
     */
    @Pattern(regexp = ORDER_REGEX, message = "排序方向只能为 asc 或 desc")
    private String order;

    /**
     * 校验字段名是否合法（防止 SQL 注入）
     *
     * @param fieldName 字段名
     * @return 是否合法
     */
    public static boolean isValidField(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            return false;
        }
        return FIELD_PATTERN.matcher(fieldName).matches();
    }

    /**
     * 校验排序方向是否合法
     *
     * @param order 排序方向
     * @return 是否合法
     */
    public static boolean isValidOrder(String order) {
        return ORDER_ASC.equalsIgnoreCase(order) || ORDER_DESC.equalsIgnoreCase(order);
    }
}
