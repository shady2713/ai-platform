package com.basicframework.module.ai.controller.admin.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/** 凭据轮换请求：只递增 credentialRevision，历史配置版本不保存任何秘密。 */
@Data
public class AiModelEndpointCredentialReqVO {

    @Schema(description = "端点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "端点编号不能为空")
    private Long id;

    @Schema(description = "新凭据", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "凭据不能为空")
    @Size(max = 1024, message = "凭据长度不能超过 1024")
    @ToString.Exclude
    private String credential;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "乐观锁版本不能为空")
    private Integer version;
}
