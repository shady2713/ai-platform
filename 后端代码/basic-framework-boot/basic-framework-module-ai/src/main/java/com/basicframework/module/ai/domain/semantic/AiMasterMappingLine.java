package com.basicframework.module.ai.domain.semantic;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_MAPPING_ENTRY_INVALID;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 一条跨系统源键映射（Y02）：某业务对象在**某个系统**里的**一个显式登记的标识**。
 *
 * <p>它是判定的最小事实单位，把"哪些字符串算同一个实体"从名称层面彻底剥离：
 * <ul>
 *   <li><b>匹配只认源键</b>：{@code sourceKey} 是来源系统里的业务主键（订单号、客户编号……），
 *       {@code sourceName} 只是展示名——**同名不是同一实体**，判定路径从不读取名称；</li>
 *   <li><b>匹配方式显式</b>：只有人工登记（{@code MANUAL}）与可信主数据接口导入
 *       （{@code TRUSTED_FEED}）两种；两者都必须给出源键，未知方式一律拒绝（不猜）；</li>
 *   <li><b>有效期是判定条件</b>：半开区间 {@code [validFrom, validTo)}；{@code validTo} 为空表示
 *       长期有效。判定时刻不落在区间内的行**不参与**判定，且"全部过期"与"从未登记"分开表达。</li>
 * </ul>
 *
 * <p>本记录是 domain 层的纯事实（不带 DAL 依赖），DAL 行对象由 Service 映射进来。
 */
public record AiMasterMappingLine(
        Long masterObjectId,
        Long applicationId,
        String entityType,
        String sourceKey,
        String sourceName,
        String matchMethod,
        LocalDateTime validFrom,
        LocalDateTime validTo) {

    /** 匹配方式：人工登记。 */
    public static final String MATCH_MANUAL = "MANUAL";

    /** 匹配方式：可信主数据接口导入（仍必须携带源键，不做名称匹配）。 */
    public static final String MATCH_TRUSTED_FEED = "TRUSTED_FEED";

    /** 系统内实体类型：小写标识符，如 {@code customer}、{@code order}。 */
    private static final Pattern ENTITY_TYPE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

    /** 源键：来源系统主键，允许字母数字与 {@code . _ - / :}（外部编码可能带前缀）。 */
    private static final Pattern SOURCE_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._\\-/:]{0,127}$");

    /** 展示名长度上限（与列宽 128 对齐）。 */
    public static final int MAX_SOURCE_NAME_LENGTH = 128;

    /**
     * 校验并归一化一条登记事实（登记入口与可信导入共用；不合规一律抛 {@code 422} 语义）。
     *
     * <p>归一化只做去除首尾空白与匹配方式大写——**不做**大小写折叠、不做名称相似度：源键的大小写
     * 属于来源系统的事实，平台不替来源系统改键。
     */
    public static AiMasterMappingLine of(
            Long masterObjectId,
            Long applicationId,
            String entityType,
            String sourceKey,
            String sourceName,
            String matchMethod,
            LocalDateTime validFrom,
            LocalDateTime validTo) {
        if (masterObjectId == null || masterObjectId <= 0 || applicationId == null || applicationId <= 0) {
            throw exception(AI_MASTER_MAPPING_ENTRY_INVALID);
        }
        if (entityType == null || !ENTITY_TYPE_PATTERN.matcher(entityType).matches()) {
            throw exception(AI_MASTER_MAPPING_ENTRY_INVALID);
        }
        if (sourceKey == null || !SOURCE_KEY_PATTERN.matcher(sourceKey).matches()) {
            throw exception(AI_MASTER_MAPPING_ENTRY_INVALID);
        }
        String normalizedName = sourceName == null ? "" : sourceName.trim();
        if (normalizedName.length() > MAX_SOURCE_NAME_LENGTH) {
            throw exception(AI_MASTER_MAPPING_ENTRY_INVALID);
        }
        if (validFrom == null || (validTo != null && !validTo.isAfter(validFrom))) {
            // 空窗口与倒挂窗口都拒绝：有效期是判定条件，不能用"必然不生效"的窗口登记
            throw exception(AI_MASTER_MAPPING_ENTRY_INVALID);
        }
        AiMasterMappingMatchMethod method = AiMasterMappingMatchMethod.parse(matchMethod)
                .orElseThrow(() -> exception(AI_MASTER_MAPPING_ENTRY_INVALID));
        return new AiMasterMappingLine(
                masterObjectId,
                applicationId,
                entityType,
                sourceKey,
                normalizedName,
                method.name(),
                validFrom,
                validTo);
    }

    /** 判定时刻是否落在本行有效期内（半开区间）。 */
    public boolean inForceAt(LocalDateTime asOf) {
        return AiMasterMappingFacts.inForce(validFrom, validTo, asOf);
    }

    /** 稳定行文本（指纹输入：只含判定事实，不含展示名——改名不该改变映射指纹）。 */
    public String canonicalText() {
        return masterObjectId + "|" + applicationId + "|" + entityType + "|" + sourceKey + "|" + matchMethod + "|"
                + validFrom + "|" + (validTo == null ? "" : validTo);
    }

    /** 归属键（系统 + 实体类型 + 源键）：跨对象比对冲突时使用。 */
    public String sourceIdentity() {
        return applicationId + "/" + entityType + "/" + sourceKey;
    }

    /** 归属键（对象 + 系统 + 实体类型）：对象内一对多冲突检测时使用。 */
    public String objectSystemIdentity() {
        return masterObjectId + "/" + applicationId + "/" + entityType;
    }

    /** 匹配方式枚举化（已校验，未知不可能出现）。 */
    public AiMasterMappingMatchMethod matchMethodEnum() {
        return AiMasterMappingMatchMethod.parse(matchMethod)
                .orElseThrow(() -> new IllegalStateException("未归一化的匹配方式：" + matchMethod));
    }
}
