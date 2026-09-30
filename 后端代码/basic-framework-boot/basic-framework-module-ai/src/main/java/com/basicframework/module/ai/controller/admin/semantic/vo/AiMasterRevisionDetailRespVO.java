package com.basicframework.module.ai.controller.admin.semantic.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 映射版本详情响应（协议层 VO，Y02）：版本头 + 条目 + 冲突预览 + 是否可发布。
 *
 * <p>冲突条目**照常列出**（{@code problem} 标注原因），页面据此要求操作员明确处理；
 * {@code publishable} 用与发布完全相同的规则算出，避免"页面看起来能发、后端拒绝"的错位。
 */
@Schema(description = "管理后台 - 映射版本详情")
@Data
@Accessors(chain = true)
public class AiMasterRevisionDetailRespVO {

    @Schema(description = "版本头")
    private AiMasterRevisionRespVO revision;

    @Schema(description = "映射条目（含冲突/过期标注）")
    private List<MappingEntry> entries;

    @Schema(description = "冲突键（对象/系统/实体类型 或 系统/实体类型/源键）")
    private List<String> conflictKeys;

    @Schema(description = "当前是否可直接发布")
    private boolean publishable;

    /** 版本内的一条映射条目。 */
    @Schema(description = "管理后台 - 映射条目")
    @Data
    @Accessors(chain = true)
    public static class MappingEntry {

        @Schema(description = "条目编号")
        private Long id;

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

        @Schema(description = "乐观锁版本")
        private Integer version;

        @Schema(description = "问题（NONE/EXPIRED/CONFLICT）")
        private String problem;
    }
}
