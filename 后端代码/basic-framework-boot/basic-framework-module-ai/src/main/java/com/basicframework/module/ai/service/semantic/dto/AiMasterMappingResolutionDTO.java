package com.basicframework.module.ai.service.semantic.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 源键判定结果（Y02）：某个系统里的一条源键在指定版本下指向的统一对象。
 *
 * <p>结果自带 {@code revisionNo} 与 {@code revisionFingerprint}：调用方（报表版本、查询计划）把这两项
 * 连同结果一起固定下来，后续再次解释同一份产物时用它们复核，而不是重新问"现在的映射是什么"。
 */
@Data
@Accessors(chain = true)
public class AiMasterMappingResolutionDTO {

    /** 统一对象编号 */
    private Long masterObjectId;

    /** 统一对象标识 */
    private String objectCode;

    /** 对象名称（仅展示） */
    private String objectName;

    /** 对象类型 */
    private String objectType;

    /** 被解释的映射版本号 */
    private Long revisionNo;

    /** 该版本发布时冻结的内容指纹 */
    private String revisionFingerprint;

    /** 判定时刻 */
    private LocalDateTime asOf;

    /** 来源系统编号 */
    private Long applicationId;

    /** 实体类型 */
    private String entityType;

    /** 源键（登记事实） */
    private String sourceKey;

    /** 展示名（不参与判定） */
    private String sourceName;

    /** 匹配方式（MANUAL/TRUSTED_FEED） */
    private String matchMethod;

    /** 源键有效期起点（含） */
    private LocalDateTime validFrom;

    /** 源键有效期终点（不含；为空=长期有效） */
    private LocalDateTime validTo;
}
