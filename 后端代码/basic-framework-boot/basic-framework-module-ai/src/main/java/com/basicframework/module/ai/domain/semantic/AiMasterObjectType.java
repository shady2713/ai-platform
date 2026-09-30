package com.basicframework.module.ai.domain.semantic;

import java.util.Locale;
import java.util.Optional;

/**
 * 企业统一对象类型（Y02）：主数据映射锚点的业务分类。
 *
 * <p>类型是**封闭词表**而不是自由文本：它只用于分类展示与按类型过滤（例如"客户类对象的映射"），
 * 不参与任何实体判定。未知类型一律拒绝（{@link Optional#empty()}）——把分类写成自由文本会让
 * "客户"出现十种拼写，后续无法按类型做任何可靠统计。
 */
public enum AiMasterObjectType {

    /** 客户。 */
    CUSTOMER,

    /** 供应商。 */
    SUPPLIER,

    /** 产品/物料。 */
    PRODUCT,

    /** 员工。 */
    EMPLOYEE,

    /** 组织/门店/法人。 */
    ORGANIZATION,

    /** 其它主数据对象（平台未预置分类时使用，仍必须显式声明）。 */
    OTHER;

    /** 解析对象类型；未知（含 null、空串）返回空，调用方按拒绝处理。 */
    public static Optional<AiMasterObjectType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
