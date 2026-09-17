package com.basicframework.module.ai.controller.admin.grant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import lombok.Data;
import lombok.experimental.Accessors;

/** 资源授权修改请求（协议层 VO）：只允许改动作白名单，授权内容变化会递增授权版本。 */
@Schema(description = "管理后台 - 资源授权修改")
@Data
@Accessors(chain = true)
public class AiResourceGrantUpdateReqVO {

    @Schema(description = "授权编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Long id;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Integer version;

    @Schema(description = "动作白名单（READ/EXECUTE/EXPORT）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    private Set<String> actions;
}
