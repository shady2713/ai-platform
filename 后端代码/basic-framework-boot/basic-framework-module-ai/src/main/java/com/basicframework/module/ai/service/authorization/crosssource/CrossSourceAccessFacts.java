package com.basicframework.module.ai.service.authorization.crosssource;

import java.util.Locale;

/**
 * 跨源授权的**逐级事实**（Y05）：一个来源在一个主体视角下的授权结论。
 *
 * <p>本 record 是判定算法的输入，不是判定结果。它回答三个**互相独立**的问题，
 * 三者都必须为真才允许该来源参与合并——把它们合成一个 {@code authorized} 布尔值
 * 正是专项二要防的错误：
 * <ol>
 *   <li><b>来源系统</b>：主体能否访问该来源所在的系统（{@code systemAuthorized}）；</li>
 *   <li><b>数据集</b>：主体能否读取该来源具体的数据集（{@code datasetAuthorized}）；</li>
 *   <li><b>实体映射</b>：主体能否看到"源键↔统一实体"这条对应关系（{@code mappingAuthorized}）。</li>
 * </ol>
 *
 * <p>第 3 项与前两项**同级**而不是前两项的推论：知道"C-001 在系统 A 与系统 B 是同一实体"
 * 本身就是一份跨系统事实，即使两个数据集都可读，映射无权时关联仍必须被拒。
 *
 * <p>不可变 record：授权事实参与的是"这次能不能读"的判定，执行期间不允许被改写，
 * 否则"判定时有权、取数时已失权"就会变成一个无法复现的中间态。
 *
 * @param role               来源角色（与跨源计划里的角色一致）
 * @param systemCode         来源系统标识
 * @param datasetCode        来源数据集标识
 * @param systemAuthorized   来源系统是否可访问
 * @param datasetAuthorized  数据集是否可读
 * @param mappingAuthorized  实体映射是否可访问
 */
public record CrossSourceAccessFacts(
        String role,
        String systemCode,
        String datasetCode,
        boolean systemAuthorized,
        boolean datasetAuthorized,
        boolean mappingAuthorized) {

    /**
     * 来源数据是否可读（系统 + 数据集两级都为真）。
     *
     * <p>注意它**不包含**映射：数据可读与映射可读是两件事，把它们并成一个布尔值
     * 会让"只读数据不做关联"这条路径绕过映射判定。
     */
    public boolean sourceReadable() {
        return systemAuthorized && datasetAuthorized;
    }

    /** 该来源是否整体可参与跨源合并（三级全真）。 */
    public boolean fullyAuthorized() {
        return sourceReadable() && mappingAuthorized;
    }

    /** 无权原因（稳定词表，供诊断与拒绝路径使用；不携带任何规模或取值信息）。 */
    public String denyReason() {
        if (!systemAuthorized) {
            return "SOURCE_SYSTEM_NOT_AUTHORIZED";
        }
        if (!datasetAuthorized) {
            return "SOURCE_DATASET_NOT_AUTHORIZED";
        }
        if (!mappingAuthorized) {
            return "ENTITY_MAPPING_NOT_AUTHORIZED";
        }
        return null;
    }

    /** 稳定键（用于台账与断言；与角色同源，缺失角色时退化为系统/数据集标识）。 */
    public String key() {
        String normalizedRole = role == null ? "" : role.trim();
        if (!normalizedRole.isEmpty()) {
            return normalizedRole;
        }
        return String.format(
                Locale.ROOT, "%s/%s", systemCode == null ? "" : systemCode, datasetCode == null ? "" : datasetCode);
    }
}
