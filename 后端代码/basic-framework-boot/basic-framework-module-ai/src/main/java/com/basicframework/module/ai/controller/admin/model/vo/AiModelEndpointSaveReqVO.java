package com.basicframework.module.ai.controller.admin.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.ToString;

/** 创建/修改模型端点的请求（创建与修改使用独立 DTO，避免字段语义混用）。 */
@Data
public class AiModelEndpointSaveReqVO {

    @Schema(description = "端点编号；创建时为空")
    private Long id;

    @Schema(description = "端点名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "openai-生产")
    @NotBlank(message = "端点名称不能为空")
    @Size(max = 128, message = "端点名称长度不能超过 128")
    private String name;

    @Schema(description = "提供方标识", requiredMode = Schema.RequiredMode.REQUIRED, example = "openai_compatible")
    @NotBlank(message = "提供方不能为空")
    @Size(max = 32, message = "提供方长度不能超过 32")
    private String provider;

    @Schema(description = "基础地址（https）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "基础地址不能为空")
    @Size(max = 512, message = "基础地址长度不能超过 512")
    private String baseUrl;

    @Schema(description = "模型标识", requiredMode = Schema.RequiredMode.REQUIRED, example = "gpt-4o-mini")
    @NotBlank(message = "模型标识不能为空")
    @Size(max = 128, message = "模型标识长度不能超过 128")
    private String modelId;

    @Schema(description = "能力集合（TEXT/EMBEDDING）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "能力集合不能为空")
    private List<String> capabilities;

    @Schema(description = "凭据（仅在创建/轮换时提交；修改端点不携带表示不变更）")
    @Size(max = 1024, message = "凭据长度不能超过 1024")
    @ToString.Exclude
    private String credential;

    @Schema(description = "乐观锁版本（修改时必填）")
    private Integer version;
}
