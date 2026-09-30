package com.basicframework.module.ai.service.semantic.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 源键反查结果（Y02）。
 *
 * <p>{@code mapped=false} 只有一种成因：该源键**根本没有已发布的映射**（未映射即不关联，
 * 跨系统统计不得把它与任何对象合并）。"有登记但有效期不覆盖判定时刻"与"同一源键指向多个对象"
 * 都是**阻断**（抛稳定错误码），不会退化成 {@code mapped=false}——否则调用方会把"冲突/过期"
 * 误读成"未映射"而静默降级。
 */
@Data
@Accessors(chain = true)
public class AiMasterMappingReverseResultDTO {

    /** 是否命中唯一映射 */
    private boolean mapped;

    /** 未命中原因（仅 {@code mapped=false} 时有值：NOT_REGISTERED） */
    private String reason;

    /** 来源系统编号 */
    private Long applicationId;

    /** 实体类型 */
    private String entityType;

    /** 源键（原样回显，便于调用方核对） */
    private String sourceKey;

    /** 判定时刻 */
    private LocalDateTime asOf;

    /** 统一对象编号（未命中为空） */
    private Long masterObjectId;

    /** 统一对象标识（未命中为空） */
    private String objectCode;

    /** 对象名称（仅展示；未命中为空） */
    private String objectName;

    /** 对象类型（未命中为空） */
    private String objectType;

    /** 命中的映射版本号（未命中为空） */
    private Long revisionNo;

    /** 命中版本的冻结指纹（未命中为空） */
    private String revisionFingerprint;

    /** 匹配方式（未命中为空） */
    private String matchMethod;

    /** 该源键的展示名（未命中为空） */
    private String sourceName;

    /** 源键有效期起点（含） */
    private LocalDateTime validFrom;

    /** 源键有效期终点（不含；为空=长期有效） */
    private LocalDateTime validTo;
}
