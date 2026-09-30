package com.basicframework.module.ai.service.semantic.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 映射目录中的一个系统条目（Y02）。
 *
 * <p>条目只在"主体在该系统里有可访问范围"且"该版本在该系统里登记了源键"时出现；
 * {@code usable=false} 时 {@code problem} 说明原因（冲突/过期），条目仍然列出——
 * 发现路径负责**暴露**问题，判定路径负责**阻断**。
 */
@Data
@Accessors(chain = true)
public class AiMasterCatalogEntryDTO {

    /** 系统（应用）编号 */
    private Long applicationId;

    /** 系统标识（应用 appCode） */
    private String appCode;

    /** 系统名称（仅展示） */
    private String systemName;

    /** 该系统内的实体类型 */
    private String entityType;

    /** 源键（登记事实；只出现在有权系统的结果里） */
    private String sourceKey;

    /** 展示名（不参与判定） */
    private String sourceName;

    /** 匹配方式（MANUAL/TRUSTED_FEED） */
    private String matchMethod;

    /** 源键有效期起点（含） */
    private LocalDateTime validFrom;

    /** 源键有效期终点（不含；为空=长期有效） */
    private LocalDateTime validTo;

    /** 判定时刻是否在有效期内 */
    private boolean inForce;

    /** 该条目当前是否可用于判定（无冲突且生效） */
    private boolean usable;

    /** 问题类型（NONE/EXPIRED/CONFLICT） */
    private String problem;
}
