package com.basicframework.module.ai.domain.query;

/**
 * 绑定参数（D06）：值 + JDBC 类型 + 敏感级别。
 *
 * <p>为什么值必须单独成对象：编译结果会进日志、审计与错误上下文，
 * 而参数值可能包含客户数据。本类的 {@code toString()} **不输出值**（只给类型与长度），
 * 因此"打印编译结果"不会顺带把数据打出去。
 */
public record SqlParameter(Object value, Type type, Sensitivity sensitivity) {

    /** 绑定类型：决定 JDBC 侧如何设置参数（不做隐式字符串拼接）。 */
    public enum Type {
        /** 文本。 */
        STRING,
        /** 整数。 */
        LONG,
        /** 十进制（金额等，禁止转 double）。 */
        DECIMAL,
        /** 布尔。 */
        BOOLEAN,
        /** 时间戳（按数据集声明的时区换算后绑定）。 */
        TIMESTAMP
    }

    /** 敏感级别：用于外发与日志策略（与数据分级一致）。 */
    public enum Sensitivity {
        /** 公开。 */
        PUBLIC,
        /** 内部。 */
        INTERNAL,
        /** 个人信息或敏感。 */
        PERSONAL
    }

    public SqlParameter {
        if (type == null || sensitivity == null) {
            throw new IllegalArgumentException("绑定参数必须声明类型与敏感级别");
        }
    }

    /** 便捷构造：内部等级的文本参数。 */
    public static SqlParameter string(String value) {
        return new SqlParameter(value, Type.STRING, Sensitivity.INTERNAL);
    }

    /** 便捷构造：整数参数。 */
    public static SqlParameter number(long value) {
        return new SqlParameter(value, Type.LONG, Sensitivity.INTERNAL);
    }

    /** 便捷构造：十进制参数（保持 BigDecimal 精度）。 */
    public static SqlParameter decimal(java.math.BigDecimal value) {
        return new SqlParameter(value, Type.DECIMAL, Sensitivity.INTERNAL);
    }

    @Override
    public String toString() {
        return "SqlParameter[type=" + type + ", sensitivity=" + sensitivity + ", valueLength="
                + (value == null ? 0 : String.valueOf(value).length()) + "]";
    }
}
