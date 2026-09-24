package com.basicframework.module.ai.controller.app.v1.embed.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 嵌入页公开启动配置（协议层 VO）。
 *
 * <p>**只含公开信息**：应用标识、协议版本、握手允许域、品牌名与已发布主题的 token——
 * 不含模型/连接器/资源清单，也不含任何凭据；正文与 AI 调用仍需票据。
 */
@Schema(description = "应用端 - AI 嵌入页公开启动配置")
@Data
@Accessors(chain = true)
public class AiEmbedBootstrapRespVO {

    @Schema(description = "应用标识", requiredMode = Schema.RequiredMode.REQUIRED, example = "crm-portal")
    private String appCode;

    @Schema(description = "嵌入协议版本", requiredMode = Schema.RequiredMode.REQUIRED, example = "1.0")
    private String protocolVersion;

    @Schema(description = "握手允许域（精确 Origin；宿主必须与此一致）", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> allowedOrigins;

    @Schema(description = "品牌名（应用名，公开信息）")
    private String brandName;

    @Schema(description = "自托管资产基路径（脚本与样式都在此路径下）")
    private String assetsBase;

    @Schema(description = "已发布主题修订号（无应用主题时为空）")
    private Integer themeRevision;

    @Schema(description = "主题内容摘要（缓存键；无应用主题时为平台默认摘要）")
    private String themeFingerprint;

    @Schema(description = "主题来源（PLATFORM_DEFAULT / APPLICATION_PUBLISHED）")
    private String themeSource;

    @Schema(description = "有效 ThemeTokens v1（规范化 JSON；平台默认或应用已发布）")
    @ToString.Exclude
    private String tokensJson;

    @Schema(description = "有效布局与排版选项（规范化 JSON）")
    private String layoutJson;
}
