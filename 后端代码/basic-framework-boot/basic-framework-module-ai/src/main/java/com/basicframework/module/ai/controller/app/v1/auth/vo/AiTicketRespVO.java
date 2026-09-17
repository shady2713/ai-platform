package com.basicframework.module.ai.controller.app.v1.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 换票响应（应用端 VO）：**唯一**返回票据明文的响应；平台只保存摘要。 */
@Schema(description = "应用端 - 换票响应（仅此响应包含票据明文）")
@Data
@Accessors(chain = true)
public class AiTicketRespVO {

    @Schema(description = "票据明文（短期有效；平台只保存摘要，请立即使用）")
    @ToString.Exclude
    private String token;

    @Schema(description = "到期时间")
    private LocalDateTime expiresTime;

    @Schema(description = "裁剪后的组织白名单")
    private Set<Long> organizationIds;

    @Schema(description = "裁剪后的对象白名单")
    private Set<String> resourceKeys;

    @Schema(description = "范围指纹：范围收窄后变化，历史产物需重新鉴权")
    private String scopeFingerprint;
}
