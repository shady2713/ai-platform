package com.basicframework.module.ai.controller.app.v1.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 换票请求（应用端 VO）。
 *
 * <p>必须同时提供应用客户端凭据与应用侧的**可信外部用户断言**：只有 Origin 或只有前端会话
 * 无法换到任何用户票据。范围由服务端解析并裁剪，请求里没有角色/部门字段。
 */
@Schema(description = "应用端 - 换票请求")
@Data
@Accessors(chain = true)
public class AiTicketReqVO {

    @Schema(description = "应用标识", requiredMode = Schema.RequiredMode.REQUIRED, example = "crm-portal")
    @NotBlank
    @Size(max = 64)
    private String appCode;

    @Schema(description = "应用客户端秘密", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 2048)
    @ToString.Exclude
    private String appSecret;

    @Schema(description = "主体类型（APP/USER）", requiredMode = Schema.RequiredMode.REQUIRED, example = "USER")
    @NotBlank
    @Pattern(regexp = "^(APP|USER)$", message = "主体类型只能是 APP 或 USER")
    private String subjectType;

    @Schema(description = "可信外部用户断言（USER 主体必填；由应用服务端在验签后提交）")
    @Size(max = 128)
    private String externalUserId;

    @Schema(description = "本次需要的对象标识（用于裁剪票据范围；越界对象会被裁掉）")
    private List<String> resourceKeys;
}
