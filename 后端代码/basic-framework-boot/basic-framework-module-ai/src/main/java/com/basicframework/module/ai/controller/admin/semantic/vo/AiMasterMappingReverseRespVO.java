package com.basicframework.module.ai.controller.admin.semantic.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 源键反查结果（协议层 VO，Y02）：未映射返回 mapped=false（不报错），冲突/过期/停用是错误响应。 */
@Schema(description = "管理后台 - 主数据源键反查结果")
@Data
@Accessors(chain = true)
public class AiMasterMappingReverseRespVO {

    @Schema(description = "是否命中唯一映射")
    private boolean mapped;

    @Schema(description = "未命中原因（NOT_REGISTERED）")
    private String reason;

    @Schema(description = "来源系统编号")
    private Long applicationId;

    @Schema(description = "实体类型")
    private String entityType;

    @Schema(description = "源键（原样回显）")
    private String sourceKey;

    @Schema(description = "判定时刻")
    private LocalDateTime asOf;

    @Schema(description = "统一对象编号（未命中为空）")
    private Long masterObjectId;

    @Schema(description = "统一对象标识（未命中为空）")
    private String objectCode;

    @Schema(description = "对象名称（仅展示）")
    private String objectName;

    @Schema(description = "对象类型")
    private String objectType;

    @Schema(description = "命中的映射版本号")
    private Long revisionNo;

    @Schema(description = "命中版本的冻结指纹")
    private String revisionFingerprint;

    @Schema(description = "匹配方式")
    private String matchMethod;

    @Schema(description = "展示名（不参与判定）")
    private String sourceName;

    @Schema(description = "源键有效期起点（含）")
    private LocalDateTime validFrom;

    @Schema(description = "源键有效期终点（不含；为空=长期有效）")
    private LocalDateTime validTo;
}
