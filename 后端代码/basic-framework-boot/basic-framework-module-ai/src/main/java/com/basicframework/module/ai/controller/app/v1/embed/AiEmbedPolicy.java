package com.basicframework.module.ai.controller.app.v1.embed;

import com.basicframework.module.ai.domain.application.ApplicationOrigins;
import java.util.List;

/**
 * 嵌入页的安全策略（C05）：**纯粹的头部取值计算**，不依赖 Spring，便于逐条证明。
 *
 * <p>三条不可让步的规则：
 * <ol>
 *   <li><b>允许域只来自应用配置</b>：Origin 的校验与归一化统一由 A01 的
 *       {@link ApplicationOrigins} 负责（精确 Origin、禁通配、禁路径/查询/用户信息），
 *       本类不复制一份更宽松或更严格的规则——两侧各写一份必然漂移；</li>
 *   <li><b>CSP 不给脚本开口子</b>：`script-src 'self'`（无 `unsafe-inline`、无 `unsafe-eval`）、
 *       `object-src 'none'`、`base-uri 'none'`、`form-action 'none'`，脚本与样式全部来自自托管产物；</li>
 *   <li><b>缓存键包含应用 + 配置版本 + 主题指纹</b>：漏掉任何一个都会让"撤销允许域后仍可嵌入"
 *       或"发布新主题后外观不更新"或"跨应用串用配置"成为可能。</li>
 * </ol>
 */
public final class AiEmbedPolicy {

    /** 公开嵌入协议版本（与 `docs/contracts/ai/README.md` 的 v1 对齐）。 */
    public static final String PROTOCOL_VERSION = "1.0";

    private AiEmbedPolicy() {}

    /**
     * 应用的允许嵌入域（校验 + 归一化，保序）。
     *
     * <p>先按 A01 的持久化形态读取，再走同一套校验/归一化：脏数据（路径、通配、空列表、默认端口混写）
     * 在这里被判为非法并抛错，调用方据此 fail-closed——不返回一个"谁都能嵌"的壳，
     * 也不把两种写法（`https://a.com` 与 `https://a.com:443`）当成两条允许域。
     */
    public static List<String> allowedOrigins(String originsJson) {
        return ApplicationOrigins.normalize(ApplicationOrigins.parse(originsJson));
    }

    /**
     * 嵌入页 CSP：`frame-ancestors` 只列应用配置的精确 Origin，其余指令一律收紧到自托管。
     *
     * <p>不使用 meta 标签替代响应头（设计契约 7.3）：`frame-ancestors` 在 meta 中会被浏览器忽略。
     */
    public static String contentSecurityPolicy(List<String> allowedOrigins) {
        return "default-src 'self'; "
                + "script-src 'self'; "
                + "style-src 'self'; "
                + "img-src 'self' data:; "
                + "font-src 'self'; "
                + "connect-src 'self'; "
                + "object-src 'none'; "
                + "base-uri 'none'; "
                + "form-action 'none'; "
                + "frame-src 'none'; "
                + "frame-ancestors " + String.join(" ", allowedOrigins);
    }

    /**
     * 响应缓存键：应用 + 应用配置版本 + 主题指纹。
     *
     * <p>配置版本取应用的乐观锁版本（改允许域/停用都会 +1），主题指纹取 C04 的有效主题摘要。
     */
    public static String cacheKey(String appCode, Integer applicationVersion, String themeFingerprint) {
        return "embed-" + appCode + "-" + (applicationVersion == null ? "0" : applicationVersion) + "-"
                + (themeFingerprint == null || themeFingerprint.isEmpty() ? "none" : themeFingerprint);
    }

    /** 强 ETag（带引号）；内容变化时必然不同，撤销/发布后浏览器必须重新取。 */
    public static String etag(String cacheKey) {
        return "\"" + cacheKey + "\"";
    }
}
