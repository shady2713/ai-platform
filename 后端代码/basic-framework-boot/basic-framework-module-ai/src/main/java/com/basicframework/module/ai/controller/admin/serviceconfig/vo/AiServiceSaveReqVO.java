package com.basicframework.module.ai.controller.admin.serviceconfig.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 服务草稿新增/修改（协议层 VO）。 */
@Schema(description = "管理后台 - AI 服务草稿新增/修改")
@Data
@Accessors(chain = true)
public class AiServiceSaveReqVO {

    @Schema(description = "服务编号（修改时必填）")
    private Long id;

    @Schema(description = "所属应用编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Long appId;

    @Schema(description = "服务标识（应用内唯一，创建后不可修改）", example = "order-qa")
    @Pattern(regexp = "^[a-z][a-z0-9_-]{2,63}$", message = "服务标识只能是小写字母开头的字母、数字、下划线或连字符")
    private String code;

    @Schema(description = "服务名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String name;

    @Schema(description = "服务说明")
    @Size(max = 512)
    private String description;

    @Schema(description = "模型端点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Long modelEndpointId;

    @Schema(description = "提示词模板", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String promptTemplate;

    @Schema(description = "输入 JSON Schema（对象）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String inputSchema;

    @Schema(description = "输出 JSON Schema（结构化输出时必填）")
    private String outputSchema;

    @Schema(
            description = "所需能力（TEXT/TEXT_STREAM/STRUCTURED_OUTPUT/TOOL_CALLING/EMBEDDING）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    private List<String> requiredCapabilities;

    @Schema(description = "运行主体类型（APP/USER）", requiredMode = Schema.RequiredMode.REQUIRED)
    @Pattern(regexp = "^(APP|USER)$", message = "运行主体类型只能是 APP 或 USER")
    private String runSubjectType;

    @Schema(description = "乐观锁版本（修改时必填）")
    private Integer version;
}
