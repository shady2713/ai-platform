package com.basicframework.module.ai.service.authorization.crosssource;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 跨源读取的调用方角色（Y05）：角色决定**能看到哪些字段**，不只是"能不能进"。
 *
 * <p>为什么跨源需要单独的角色词表而不是复用 A03 的 {@code AiAction}：
 * {@code AiAction} 回答"允许哪种操作"（READ/EXECUTE/EXPORT），而跨源合并真正要防的是
 * **同一操作下的字段可见性**——一个能读订单金额的角色，未必被允许看到客户名称。
 * 把这两个维度混成一个词表，就会退化成"能读就能看全部字段"。
 *
 * <p>字段可见性按 {@link #visibleFields()} 判定，且**取交集**：跨源结果里出现的每个字段，
 * 必须被**所有**参与合并的来源角色同时允许。任何一个角色不允许，该字段就不进结果——
 * 少一个字段是可用性损失，多一个字段是越权。
 */
public enum CrossSourceCallerRole {

    /** 只读聚合值：只能看金额类聚合字段，看不到任何维度键。 */
    AGGREGATE_READER(Set.of("amount", "currency", "total")),

    /** 分析员：聚合值 + 维度键（客户/产品等业务维度），但看不到明细行。 */
    ANALYST(Set.of("amount", "currency", "total", "customer_key", "product_key", "as_of")),

    /** 数据管理员：全字段，含明细行标识。 */
    DATA_STEWARD(Set.of(
            "amount",
            "currency",
            "total",
            "customer_key",
            "product_key",
            "as_of",
            "source_system",
            "source_key",
            "row_count"));

    /** 跨源合并允许出现的字段全集（角色词表之外的字段一律不可见）。 */
    private static final Set<String> KNOWN_FIELDS = Set.of(
            "amount",
            "currency",
            "total",
            "customer_key",
            "product_key",
            "as_of",
            "source_system",
            "source_key",
            "row_count");

    private final Set<String> visibleFields;

    CrossSourceCallerRole(Set<String> visibleFields) {
        this.visibleFields = Set.copyOf(visibleFields);
    }

    /** 该角色可见的字段（不可变副本，调用方改不动）。 */
    public Set<String> visibleFields() {
        return visibleFields;
    }

    /** 该角色是否可见某字段。未知字段名同样返回 false（fail-closed）。 */
    public boolean canSee(String field) {
        return field != null && visibleFields.contains(field.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 多个角色的字段交集（跨源结果按最弱角色裁剪）。
     *
     * <p>空交集是合法结果：意味着"没有任何字段可以同时给到这些角色"，
     * 调用方据此拒绝出具明细，而不是退回到并集。
     */
    public static Set<String> commonFields(Set<CrossSourceCallerRole> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        Set<String> common = null;
        for (CrossSourceCallerRole role : roles) {
            common = common == null ? role.visibleFields() : intersect(common, role.visibleFields());
        }
        return common == null ? Set.of() : Set.copyOf(common);
    }

    /** 解析角色名；未知取值返回空（调用方按拒绝处理，不静默降级到最低角色）。 */
    public static Optional<CrossSourceCallerRole> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    private static Set<String> intersect(Set<String> left, Set<String> right) {
        return left.stream().filter(right::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static {
        // 词表自检：任何角色都不得看到 KNOWN_FIELDS 之外的字段（防止加字段时漏改角色）
        for (CrossSourceCallerRole role : values()) {
            for (String field : role.visibleFields) {
                if (!KNOWN_FIELDS.contains(field)) {
                    throw new IllegalStateException("角色 " + role + " 声明了词表外字段 " + field);
                }
            }
        }
    }
}
