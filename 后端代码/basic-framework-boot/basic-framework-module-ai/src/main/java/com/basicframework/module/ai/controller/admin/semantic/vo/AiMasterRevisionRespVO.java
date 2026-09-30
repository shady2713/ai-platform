package com.basicframework.module.ai.controller.admin.semantic.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 映射版本响应（协议层 VO，Y02）：已发布版本的指纹与条目数冻结，可用于复核。 */
@Schema(description = "管理后台 - 映射版本")
@Data
@Accessors(chain = true)
public class AiMasterRevisionRespVO {

    @Schema(description = "统一对象编号")
    private Long masterObjectId;

    @Schema(description = "映射版本号")
    private Long revisionNo;

    @Schema(description = "状态（DRAFT/PUBLISHED）")
    private String status;

    @Schema(description = "版本有效期起点（含）")
    private LocalDateTime validFrom;

    @Schema(description = "版本有效期终点（不含；为空=长期有效）")
    private LocalDateTime validTo;

    @Schema(description = "发布时冻结的条目数")
    private Integer entryCount;

    @Schema(description = "发布时冻结的内容指纹")
    private String mappingFingerprint;

    @Schema(description = "草稿创建人编号")
    private Long createdBy;

    @Schema(description = "发布人编号（必须与草稿创建人不同）")
    private Long publishedBy;

    @Schema(description = "发布时间")
    private LocalDateTime publishedTime;

    @Schema(description = "乐观锁版本")
    private Integer version;
}
