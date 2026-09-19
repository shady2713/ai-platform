package com.basicframework.module.ai.controller.app.v1.run.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 运行（应用端协议层 VO）：只暴露状态与固定版本标识，不回显输入正文或凭据。 */
@Schema(description = "应用端 - AI 运行")
@Data
@Accessors(chain = true)
public class AiRunRespVO {

    @Schema(description = "运行编号")
    private Long id;

    @Schema(description = "运行业务键")
    private String runKey;

    @Schema(description = "会话编号")
    private Long conversationId;

    @Schema(description = "服务编号")
    private Long serviceId;

    @Schema(description = "固定的发布版本编号")
    private Long releaseId;

    @Schema(description = "固定的发布内容摘要")
    private String contentHash;

    @Schema(description = "固定的端点配置版本")
    private Integer endpointConfigRevision;

    @Schema(description = "状态（ACCEPTED/RUNNING/SUCCEEDED/FAILED/CANCELLED）")
    private String status;

    @Schema(description = "已执行步数")
    private Integer stepCount;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
