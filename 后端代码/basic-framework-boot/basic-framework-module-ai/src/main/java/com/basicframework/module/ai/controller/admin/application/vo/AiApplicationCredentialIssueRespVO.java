package com.basicframework.module.ai.controller.admin.application.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 凭据签发响应（协议层 VO）：**唯一**会返回秘密明文的响应，只在创建/轮换时出现一次。
 *
 * <p>控制器不记录该响应内容；对接方拿到后自行保存，平台只保留摘要，无法再次出示。
 */
@Schema(description = "管理后台 - AI 应用凭据签发（仅此响应包含明文秘密）")
@Data
@Accessors(chain = true)
public class AiApplicationCredentialIssueRespVO {

    @Schema(description = "应用编号")
    private Long applicationId;

    @Schema(description = "应用标识")
    private String appCode;

    @Schema(description = "凭据编号")
    @ToString.Exclude
    private Long credentialId;

    @Schema(description = "一次性客户端秘密明文；平台不保存，请立即妥善保存")
    @ToString.Exclude
    private String secret;
}
