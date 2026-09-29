package com.basicframework.module.ai.domain.workflow;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/**
 * 条件节点的受控比较表达式（X08）。
 *
 * <p>只有这六种操作符，没有表达式语言、没有脚本：左边是**被引用节点的输出文本**，
 * 右边是发布期冻结的字面量。数值比较（GT/LT）两边都无法解析为十进制数时按 FALSE 分支处理——
 * 条件是路由判定而不是校验器，"不可比较"是确定性结论，不是运行错误（不会因此绕过预算）。
 */
public enum AiWorkflowConditionOperator {

    /** 输出文本等于字面量。 */
    EQ {
        @Override
        public boolean evaluate(String left, String right) {
            return left != null && left.equals(right);
        }
    },

    /** 输出文本不等于字面量。 */
    NE {
        @Override
        public boolean evaluate(String left, String right) {
            return !EQ.evaluate(left, right);
        }
    },

    /** 输出文本包含字面量。 */
    CONTAINS {
        @Override
        public boolean evaluate(String left, String right) {
            return left != null && left.contains(right);
        }
    },

    /** 输出文本不包含字面量。 */
    NOT_CONTAINS {
        @Override
        public boolean evaluate(String left, String right) {
            return !CONTAINS.evaluate(left, right);
        }
    },

    /** 输出文本按十进制数大于字面量（任一侧不可解析按 FALSE 分支）。 */
    GT {
        @Override
        public boolean evaluate(String left, String right) {
            return compareNumeric(left, right) > 0;
        }
    },

    /** 输出文本按十进制数小于字面量（任一侧不可解析按 FALSE 分支）。 */
    LT {
        @Override
        public boolean evaluate(String left, String right) {
            return compareNumeric(left, right) < 0;
        }
    };

    /** 求值（left 为被引用节点输出，right 为冻结字面量）。 */
    public abstract boolean evaluate(String left, String right);

    private static int compareNumeric(String left, String right) {
        BigDecimal leftNumber = toNumber(left);
        BigDecimal rightNumber = toNumber(right);
        if (leftNumber == null || rightNumber == null) {
            return 0;
        }
        return leftNumber.compareTo(rightNumber);
    }

    private static BigDecimal toNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** 解析操作符；未知取值返回空（发布期按类型不匹配拒绝）。 */
    public static Optional<AiWorkflowConditionOperator> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknownOperator) {
            return Optional.empty();
        }
    }
}
