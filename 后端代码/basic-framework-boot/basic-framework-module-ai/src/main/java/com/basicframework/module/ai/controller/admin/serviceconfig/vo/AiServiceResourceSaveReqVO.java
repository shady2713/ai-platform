package com.basicframework.module.ai.controller.admin.serviceconfig.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 资源绑定请求（协议层 VO）。 */
@Schema(description = "管理后台 - 服务资源绑定")
@Data
@Accessors(chain = true)
public class AiServiceResourceSaveReqVO {

    @Schema(description = "服务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Long serviceId;

    @Schema(description = "资源类型（REPORT/KNOWLEDGE_BASE/FILE/TOOL/DATASET）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String resourceType;

    @Schema(description = "资源标识", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String resourceKey;

    @Schema(description = "需要的动作（READ/EXECUTE/EXPORT；缺省为 READ）")
    private List<String> actions;
}
