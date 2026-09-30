package com.basicframework.module.ai.controller.admin.semantic.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 源键判定结果（协议层 VO，Y02）：携带版本号与冻结指纹，供调用方固定为解释依据。 */
@Schema(description = "管理后台 - 主数据映射判定结果")
@Data
@Accessors(chain = true)
public class AiMasterMappingResolutionRespVO {

    @Schema(description = "统一对象编号")
    private Long masterObjectId;

    @Schema(description = "统一对象标识")
    private String objectCode;

    @Schema(description = "对象名称（仅展示）")
    private String objectName;

    @Schema(description = "对象类型")
    private String objectType;

    @Schema(description = "被解释的映射版本号")
    private Long revisionNo;

    @Schema(description = "该版本发布时冻结的内容指纹")
    private String revisionFingerprint;

    @Schema(description = "判定时刻")
    private LocalDateTime asOf;

    @Schema(description = "来源系统编号")
    private Long applicationId;

    @Schema(description = "实体类型")
    private String entityType;

    @Schema(description = "源键（登记事实）")
    private String sourceKey;

    @Schema(description = "展示名（不参与判定）")
    private String sourceName;

    @Schema(description = "匹配方式（MANUAL/TRUSTED_FEED）")
    private String matchMethod;

    @Schema(description = "源键有效期起点（含）")
    private LocalDateTime validFrom;

    @Schema(description = "源键有效期终点（不含；为空=长期有效）")
    private LocalDateTime validTo;
}
