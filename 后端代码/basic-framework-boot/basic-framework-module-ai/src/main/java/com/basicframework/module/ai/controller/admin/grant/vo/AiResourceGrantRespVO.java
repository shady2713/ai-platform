package com.basicframework.module.ai.controller.admin.grant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.Data;
import lombok.experimental.Accessors;

/** 资源授权响应（协议层 VO）。 */
@Schema(description = "管理后台 - 资源授权")
@Data
@Accessors(chain = true)
public class AiResourceGrantRespVO {

    @Schema(description = "授权编号")
    private Long id;

    @Schema(description = "应用编号")
    private Long applicationId;

    @Schema(description = "主体类型")
    private String subjectType;

    @Schema(description = "外部用户标识")
    private String externalUserId;

    @Schema(description = "资源类型")
    private String resourceType;

    @Schema(description = "资源标识")
    private String resourceKey;

    @Schema(description = "动作白名单")
    private Set<String> actions;

    @Schema(description = "状态（ACTIVE/REVOKED）")
    private String status;

    @Schema(description = "授权版本")
    private Long authzRevision;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
