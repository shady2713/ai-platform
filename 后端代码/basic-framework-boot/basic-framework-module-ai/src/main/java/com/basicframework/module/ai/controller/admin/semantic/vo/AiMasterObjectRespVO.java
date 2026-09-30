package com.basicframework.module.ai.controller.admin.semantic.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 企业统一对象响应（协议层 VO，Y02）。 */
@Schema(description = "管理后台 - 企业统一对象")
@Data
@Accessors(chain = true)
public class AiMasterObjectRespVO {

    @Schema(description = "统一对象编号")
    private Long id;

    @Schema(description = "统一对象标识")
    private String objectCode;

    @Schema(description = "对象名称（仅展示）")
    private String objectName;

    @Schema(description = "对象类型")
    private String objectType;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "状态（ACTIVE/DISABLED）")
    private String status;

    @Schema(description = "当前已发布的映射版本（0=尚无已发布版本）")
    private Long currentRevision;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
