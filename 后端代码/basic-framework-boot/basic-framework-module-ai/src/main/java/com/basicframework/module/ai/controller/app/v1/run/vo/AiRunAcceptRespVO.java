package com.basicframework.module.ai.controller.app.v1.run.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 受理结果（应用端协议层 VO）：只有运行引用、状态与固定版本，**不含**凭据、token 或请求正文。
 */
@Schema(description = "应用端 - AI 运行受理结果")
@Data
@Accessors(chain = true)
public class AiRunAcceptRespVO {

    @Schema(description = "运行编号")
    private Long runId;

    @Schema(description = "运行业务键（后续查询与事件订阅的稳定引用）")
    private String runKey;

    @Schema(description = "运行状态（ACCEPTED 表示已受理，等待执行）")
    private String status;

    @Schema(description = "固定的发布版本编号")
    private Long releaseId;

    @Schema(description = "固定的发布版本号")
    private Integer releaseVersion;

    @Schema(description = "是否命中幂等（复用首次受理的运行，平台不会重新发起模型调用）")
    private boolean reused;
}
