package com.basicframework.module.ai.controller.admin.theme.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 有效主题（协议层 VO）：继承顺序的结果 + 来源标记。 */
@Schema(description = "管理后台 - AI 有效主题")
@Data
@Accessors(chain = true)
public class AiThemeEffectiveRespVO {

    @Schema(description = "应用编号")
    private Long applicationId;

    @Schema(description = "主题对外标识（平台默认时为空）")
    private String publicId;

    @Schema(description = "修订号（平台默认时为空）")
    private Integer revision;

    @Schema(description = "内容摘要（缓存键）")
    private String fingerprint;

    @Schema(description = "来源（PLATFORM_DEFAULT/APPLICATION_PUBLISHED）")
    private String source;

    @Schema(description = "有效 ThemeTokens v1（规范化 JSON）")
    @ToString.Exclude
    private String tokensJson;

    @Schema(description = "有效布局与排版选项（规范化 JSON）")
    private String layoutJson;
}
