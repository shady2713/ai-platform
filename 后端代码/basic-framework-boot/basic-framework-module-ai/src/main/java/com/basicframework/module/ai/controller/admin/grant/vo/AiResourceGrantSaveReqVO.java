package com.basicframework.module.ai.controller.admin.grant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import lombok.Data;
import lombok.experimental.Accessors;

/** 资源授权新增请求（协议层 VO）：动作只接受白名单。 */
@Schema(description = "管理后台 - 资源授权新增")
@Data
@Accessors(chain = true)
public class AiResourceGrantSaveReqVO {

    @Schema(description = "应用编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Long applicationId;

    @Schema(description = "主体类型（APP/USER）", requiredMode = Schema.RequiredMode.REQUIRED)
    @Pattern(regexp = "^(APP|USER)$", message = "主体类型只能是 APP 或 USER")
    private String subjectType;

    @Schema(description = "外部用户标识（USER 主体必填；APP 主体必须为空）")
    @Size(max = 128)
    private String externalUserId;

    @Schema(description = "资源类型（REPORT/KNOWLEDGE_BASE/FILE/TOOL/DATASET）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String resourceType;

    @Schema(description = "资源标识", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String resourceKey;

    @Schema(description = "动作白名单（READ/EXECUTE/EXPORT）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    private Set<String> actions;
}
