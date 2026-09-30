package com.basicframework.module.ai.controller.admin.semantic.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 主数据映射目录响应（协议层 VO，Y02）：无权系统不出现；拒绝目录与"主体未登记"同形。 */
@Schema(description = "管理后台 - 主数据映射目录")
@Data
@Accessors(chain = true)
public class AiMasterObjectCatalogRespVO {

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

    @Schema(description = "是否被拒绝（与主体未登记同语义）")
    private boolean denied;

    @Schema(description = "可见映射条目（含问题标注）")
    private List<Entry> entries;

    @Schema(description = "目录指纹：身份 + 对象 + 版本 + 条目的稳定摘要")
    private String catalogFingerprint;

    @Schema(description = "模型可见目录（只含对象/系统/实体类型/可用性，不含源键值）")
    private String modelCatalog;

    /** 目录中的一个系统条目。 */
    @Schema(description = "管理后台 - 映射目录条目")
    @Data
    @Accessors(chain = true)
    public static class Entry {

        @Schema(description = "系统编号")
        private Long applicationId;

        @Schema(description = "系统标识（appCode）")
        private String appCode;

        @Schema(description = "系统名称（仅展示）")
        private String systemName;

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

        @Schema(description = "判定时刻是否在有效期内")
        private boolean inForce;

        @Schema(description = "是否可用于判定")
        private boolean usable;

        @Schema(description = "问题（NONE/EXPIRED/CONFLICT）")
        private String problem;
    }
}
