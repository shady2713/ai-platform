package com.basicframework.module.ai.controller.app.v1.embed;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EMBED_ASSETS_NOT_STAGED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EMBED_ASSET_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EMBED_ORIGIN_INVALID;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.module.ai.controller.app.v1.embed.vo.AiEmbedBootstrapRespVO;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.service.theme.AiThemeService;
import com.basicframework.module.ai.service.theme.dto.AiThemeEffectiveDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 嵌入页入口（C05，应用端公开路径）。
 *
 * <p>公开的部分只有三样：**固定启动壳 HTML**、**公开启动配置**、**自托管静态资产**。
 * 三者都不含凭据、会话正文或资源清单；所有 AI 调用（换票、运行、事件流）仍然要求票据，
 * 宿主凭据也从不在浏览器里出现。
 *
 * <p>安全头的分工（与设计契约 7.3 一致）：
 * <ul>
 *   <li>允许域与 CSP 由**响应头**给出（`frame-ancestors` 不能用 meta 标签替代）；允许域取应用配置的
 *       `origins`，非法或未配置一律 fail-closed（409），不做"先放行再纠正"；</li>
 *   <li>缓存键 = 应用 + 应用配置版本 + 主题指纹：撤销允许域或发布新主题后，浏览器必须重新取，
 *       不同应用之间不会串用（`AiEmbedPolicy.cacheKey`）；</li>
 *   <li>资产按**构建清单**提供且 `immutable`（文件名带内容哈希），清单外路径 404；</li>
 *   <li>X-Frame-Options 只对 `/app-api/ai/v1/embed/**` 关闭（见 `AiEmbedSecurityConfiguration`），
 *       管理端保持全局 SAMEORIGIN。</li>
 * </ul>
 */
@Tag(name = "应用端 - AI 嵌入页")
@RestController
@RequestMapping("/ai/v1/embed")
@Validated
@RequiredArgsConstructor
@Slf4j
public class AiEmbedShellController {

    private static final String SHELL_TEMPLATE =
            """
            <!doctype html>
            <html lang="zh-CN">
              <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width,initial-scale=1" />
                <meta name="referrer" content="no-referrer" />
                <title>%s</title>
                %s
              </head>
              <body>
                <div id="ai-chat-root" data-app-code="%s"></div>
                <noscript>当前环境未启用脚本，无法加载 AI 助手。</noscript>
                <script type="module" src="%s"></script>
              </body>
            </html>
            """;

    private final AiEmbedApplicationResolver applicationResolver;

    private final AiEmbedAssetCatalog assetCatalog;

    private final AiThemeService themeService;

    @GetMapping("/{appCode}")
    @Operation(summary = "嵌入页启动壳（固定构建 HTML；按应用配置精确输出 frame-ancestors）")
    @PermitAll
    public ResponseEntity<String> shell(
            @Parameter(description = "应用标识", required = true) @PathVariable("appCode") @NotBlank String appCode,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        AiApplicationDO application = applicationResolver.resolveEnabled(appCode);
        List<String> allowedOrigins = requireAllowedOrigins(application);
        AiEmbedAssetCatalog.Manifest manifest = requireManifest();
        String cacheKey = cacheKeyOf(application);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noCache().mustRevalidate())
                .eTag(cacheKey)
                .header("Content-Security-Policy", AiEmbedPolicy.contentSecurityPolicy(allowedOrigins))
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff");
        if (AiEmbedPolicy.etag(cacheKey).equals(ifNoneMatch)) {
            // 配置或主题未变化：304 不带正文，浏览器继续用同一份壳（壳本身与主题无关，主题在 bootstrap 里）
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(cacheKey)
                    .header("Content-Security-Policy", AiEmbedPolicy.contentSecurityPolicy(allowedOrigins))
                    .build();
        }
        return builder.body(renderShell(application, manifest, appCode));
    }

    @GetMapping("/{appCode}/bootstrap")
    @Operation(summary = "公开启动配置（应用标识、协议版本、握手允许域、品牌与已发布主题；不含凭据与资源清单）")
    @PermitAll
    public ResponseEntity<CommonResult<AiEmbedBootstrapRespVO>> bootstrap(
            @Parameter(description = "应用标识", required = true) @PathVariable("appCode") @NotBlank String appCode,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        AiApplicationDO application = applicationResolver.resolveEnabled(appCode);
        List<String> allowedOrigins = requireAllowedOrigins(application);
        AiThemeEffectiveDTO effective = themeService.resolveEffective(application.getId());
        String cacheKey = cacheKeyOf(application);
        AiEmbedBootstrapRespVO respVO = new AiEmbedBootstrapRespVO()
                .setAppCode(application.getAppCode())
                .setProtocolVersion(AiEmbedPolicy.PROTOCOL_VERSION)
                .setAllowedOrigins(allowedOrigins)
                .setBrandName(application.getName())
                .setAssetsBase("/app-api/ai/v1/embed/" + application.getAppCode() + "/assets")
                .setThemeRevision(effective.getRevision())
                .setThemeFingerprint(effective.getFingerprint())
                .setThemeSource(effective.getSource())
                .setThemeSource(effective.getSource())
                .setTokensJson(effective.getTokensJson())
                .setLayoutJson(effective.getLayoutJson());
        if (AiEmbedPolicy.etag(cacheKey).equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(cacheKey)
                    .header("Content-Security-Policy", AiEmbedPolicy.contentSecurityPolicy(allowedOrigins))
                    .build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().mustRevalidate())
                .eTag(cacheKey)
                .header("Content-Security-Policy", AiEmbedPolicy.contentSecurityPolicy(allowedOrigins))
                .header("X-Content-Type-Options", "nosniff")
                .body(CommonResult.success(respVO));
    }

    @GetMapping("/{appCode}/assets/{file}")
    @Operation(summary = "自托管静态资产（只提供构建清单内的文件，内容哈希命名，长缓存）")
    @PermitAll
    public ResponseEntity<byte[]> asset(
            @Parameter(description = "应用标识", required = true) @PathVariable("appCode") @NotBlank String appCode,
            @Parameter(description = "资产文件名（构建清单内）", required = true) @PathVariable("file") @NotBlank String file) {
        // 应用解析与允许域校验同样适用于资产路径：未启用应用不能借着资产路径探测产物
        AiApplicationDO application = applicationResolver.resolveEnabled(appCode);
        requireAllowedOrigins(application);
        AiEmbedAssetCatalog.Manifest manifest = requireManifest();
        AiEmbedAssetCatalog.Asset asset = manifest.files().get(file);
        if (asset == null) {
            throw exception(AI_EMBED_ASSET_NOT_EXISTS);
        }
        byte[] content;
        try {
            content = assetCatalog.read(asset);
        } catch (IOException unreadable) {
            // 清单声明的文件读不到：按"产物未就位"处理（部署问题，不应伪装成 404）
            // 只记录应用标识，不记录请求路径（请求参数不进日志，避免日志注入与探测痕迹外泄）
            log.warn("嵌入资产读取失败：appCode={}", appCode);
            throw exception(AI_EMBED_ASSETS_NOT_STAGED);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(AiEmbedAssetCatalog.contentType(file)))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365))
                        .cachePublic()
                        .immutable())
                .eTag("\"" + file + "-" + asset.size() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(content);
    }

    /** 允许域：配置非法或为空即 fail-closed（409），不返回一个"谁都能嵌"的壳。 */
    private List<String> requireAllowedOrigins(AiApplicationDO application) {
        try {
            return AiEmbedPolicy.allowedOrigins(application.getOrigins());
        } catch (ServiceException | IllegalArgumentException invalid) {
            // 只记录应用标识，不记录配置内容（配置里可能含有内网域名，不进日志）
            log.warn("嵌入允许域配置不可用：appCode={}", application.getAppCode());
            throw exception(AI_EMBED_ORIGIN_INVALID);
        }
    }

    private AiEmbedAssetCatalog.Manifest requireManifest() {
        AiEmbedAssetCatalog.Manifest manifest = assetCatalog.load();
        if (manifest == null) {
            throw exception(AI_EMBED_ASSETS_NOT_STAGED);
        }
        return manifest;
    }

    private String cacheKeyOf(AiApplicationDO application) {
        AiThemeEffectiveDTO effective = themeService.resolveEffective(application.getId());
        return AiEmbedPolicy.cacheKey(application.getAppCode(), application.getVersion(), effective.getFingerprint());
    }

    private static String renderShell(
            AiApplicationDO application, AiEmbedAssetCatalog.Manifest manifest, String appCode) {
        String assetBase = "/app-api/ai/v1/embed/" + appCode + "/assets/";
        String style = manifest.entryCss() == null
                ? ""
                : "<link rel=\"stylesheet\" href=\"" + assetBase + manifest.entryCss() + "\" />";
        return SHELL_TEMPLATE.formatted(application.getName(), style, appCode, assetBase + manifest.entryJs());
    }
}
