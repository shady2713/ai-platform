package com.basicframework.module.ai.controller.app.v1.embed;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 嵌入页配置（C05）。
 *
 * <pre>
 * basic-framework.ai.embed:
 *   assets-directory: /opt/ai-platform/embed-assets   # 已构建的前端产物目录（自托管）
 *   max-asset-bytes: 5242880                           # 单文件上限，防御性上限
 * </pre>
 *
 * <p>产物目录里必须包含 `asset-manifest.json`（由 `apps/ai-chat/scripts/stage-embed-assets.mjs` 生成），
 * 嵌入页只按**清单**里的文件名提供服务：目录里多出来的文件不会被暴露，清单外的路径一律 404。
 */
@Validated
@Component
@ConfigurationProperties(prefix = "basic-framework.ai.embed")
@Data
public class AiEmbedProperties {

    /** 已构建的嵌入产物目录（相对路径按进程工作目录解析）。 */
    @NotNull
    private String assetsDirectory = "embed-assets";

    /** 单文件大小上限（字节）。 */
    @Min(1024)
    private long maxAssetBytes = 5L * 1024 * 1024;
}
