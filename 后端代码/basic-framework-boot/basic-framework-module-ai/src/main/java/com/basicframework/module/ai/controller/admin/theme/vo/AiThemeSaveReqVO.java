package com.basicframework.module.ai.controller.admin.theme.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 主题修订新增（协议层 VO）：token 与布局都是已校验 JSON 文本，不接受任意 CSS。 */
@Schema(description = "管理后台 - AI 主题修订新增")
@Data
@Accessors(chain = true)
public class AiThemeSaveReqVO {

    @Schema(description = "所属应用编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "应用编号不能为空")
    @Positive(message = "应用编号必须为正数")
    private Long applicationId;

    @Schema(
            description = "ThemeTokens v1 JSON（primaryColor/radius/fontFamily[/colorScheme]）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "主题 token 不能为空")
    @Size(max = 2000, message = "主题 token 长度不能超过 2000")
    @ToString.Exclude
    private String tokensJson;

    @Schema(description = "布局与排版选项 JSON（fontScale/density/narrowBreakpoint/minSidebarWidth，可空）")
    @Size(max = 1000, message = "主题布局长度不能超过 1000")
    private String layoutJson;
}
