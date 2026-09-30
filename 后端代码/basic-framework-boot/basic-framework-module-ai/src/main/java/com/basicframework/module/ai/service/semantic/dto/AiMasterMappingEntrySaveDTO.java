package com.basicframework.module.ai.service.semantic.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 在草稿版本里登记一条源键映射的请求（Y02）。
 *
 * <p>源键（{@code sourceKey}）是**来源系统里的业务主键**，必须由登记方给出；{@code sourceName}
 * 只是展示名——平台不会、也无法按名称推断同一实体（同名不同实体因此不可能被合并）。
 */
@Data
@Accessors(chain = true)
public class AiMasterMappingEntrySaveDTO {

    /** 统一对象编号 */
    private Long masterObjectId;

    /** 目标草稿版本号 */
    private Long revisionNo;

    /** 来源系统（接入应用）编号 */
    private Long applicationId;

    /** 该系统中的实体类型（小写标识符，如 customer） */
    private String entityType;

    /** 该系统中的业务主键（显式登记事实） */
    private String sourceKey;

    /** 展示名（不参与判定） */
    private String sourceName;

    /** 匹配方式（MANUAL/TRUSTED_FEED） */
    private String matchMethod;

    /** 源键有效期起点（含） */
    private LocalDateTime validFrom;

    /** 源键有效期终点（不含；为空表示长期有效） */
    private LocalDateTime validTo;
}
