package com.basicframework.module.ai.controller.admin.application.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 联邦映射响应（协议层 VO，Y01）：状态与审批痕迹都来自服务端事实。 */
@Schema(description = "管理后台 - 跨系统主体联邦映射")
@Data
@Accessors(chain = true)
public class AiSubjectFederationRespVO {

    @Schema(description = "映射编号")
    private Long id;

    @Schema(description = "来源应用编号")
    private Long sourceApplicationId;

    @Schema(description = "来源主体类型")
    private String sourceSubjectType;

    @Schema(description = "来源主体外部用户标识")
    private String sourceExternalUserId;

    @Schema(description = "目标应用编号")
    private Long targetApplicationId;

    @Schema(description = "目标主体类型")
    private String targetSubjectType;

    @Schema(description = "目标主体外部用户标识")
    private String targetExternalUserId;

    @Schema(description = "状态（PENDING/APPROVED/REVOKED）")
    private String status;

    @Schema(description = "提交人编号")
    private Long requestedBy;

    @Schema(description = "提交时间")
    private LocalDateTime requestedTime;

    @Schema(description = "批准人编号（必须与提交人不同）")
    private Long approvedBy;

    @Schema(description = "批准时间")
    private LocalDateTime approvedTime;

    @Schema(description = "审批说明")
    private String approvalNote;

    @Schema(description = "映射版本")
    private Long revision;

    @Schema(description = "乐观锁版本")
    private Integer version;
}
