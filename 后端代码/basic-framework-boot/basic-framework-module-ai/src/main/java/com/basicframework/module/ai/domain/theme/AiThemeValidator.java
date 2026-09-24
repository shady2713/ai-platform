package com.basicframework.module.ai.domain.theme;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_FONT_NOT_ALLOWED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_LAYOUT_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_TOKENS_INVALID;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.util.json.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 主题 token 与布局的受控校验（C04）。
 *
 * <p>为什么把校验做成不依赖 Spring 的纯类：主题是**唯一**一处把"外部输入"直接变成前端观感的地方，
 * 一旦它能携带任意 CSS 文本，主题就成了注入面（AT-054 的失败分支："任意 CSS/url/脚本拒绝"）。
 * 所以这里只接受**声明的 token**：色值必须是十六进制、半径必须落在契约区间、字体必须命中自托管白名单、
 * 布局只接受受控枚举与数值区间——不解析、也不透传任何 CSS 片段。
 *
 * <p>校验有两条硬口径：
 * <ol>
 *   <li><b>未知字段即拒绝</b>：与冻结协议（`docs/contracts/ai/theme-tokens.schema.json` 的
 *       `additionalProperties: false`）一致，避免"客户端填了但服务端没校验"的字段悄悄生效；</li>
 *   <li><b>规范化输出</b>：校验通过后按固定键序输出 JSON（缺省项补默认值），
 *       使同一份主题的内容摘要（{@link #fingerprint}）稳定，可用于缓存键与审计比对。</li>
 * </ol>
 *
 * <p>白名单与前端同值（前端实现在 `packages/ai-chat-ui/src/theme/tokens.ts`，
 * 规则说明见 `docs/security/ai-theme-rendering-boundary.md`）：字体是自托管/系统字体栈，
 * 不允许远程字体地址，也不允许 `url(...)`、表达式或反斜杠转义。
 */
public final class AiThemeValidator {

    /** 允许的字体栈（与前端 `ALLOWED_FONT_FAMILIES` 同值；默认主题必须命中其一）。 */
    public static final List<String> ALLOWED_FONT_FAMILIES = List.of(
            "system-ui, -apple-system, \"PingFang SC\", \"Microsoft YaHei\", sans-serif",
            "\"PingFang SC\", \"Microsoft YaHei\", system-ui, sans-serif",
            "Georgia, \"Songti SC\", \"SimSun\", serif",
            "ui-monospace, SFMono-Regular, Menlo, \"Courier New\", monospace");

    /** 半径区间（与冻结契约一致：0..24）。 */
    public static final double RADIUS_MIN = 0;

    public static final double RADIUS_MAX = 24;

    /** 深浅色（`colorScheme` 可缺省：缺省表示不限定，由宿主决定）。 */
    public static final Set<String> COLOR_SCHEMES = Set.of("dark", "light");

    /** 字号档位（受控枚举，不接受字号数值）。 */
    public static final Set<String> FONT_SCALES = Set.of("large", "normal", "small");

    /** 密度档位。 */
    public static final Set<String> DENSITIES = Set.of("compact", "normal");

    /** 窄屏切换断点（px）。 */
    public static final int BREAKPOINT_MIN = 240;

    public static final int BREAKPOINT_MAX = 1440;

    /** 侧栏最小宽度（px）。 */
    public static final int SIDEBAR_MIN = 240;

    public static final int SIDEBAR_MAX = 720;

    private static final Pattern COLOR = Pattern.compile("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");

    private static final Set<String> TOKEN_KEYS = Set.of("primaryColor", "radius", "fontFamily", "colorScheme");

    private static final Set<String> LAYOUT_KEYS =
            Set.of("fontScale", "density", "narrowBreakpoint", "minSidebarWidth");

    private static final int FONT_FAMILY_MAX = 120;

    /** 布局缺省值（与前端 `defaultLayout` 同值）。 */
    public static final Map<String, Object> DEFAULT_LAYOUT = defaultLayout();

    /** 平台默认 token（与 `@vben/ai-contracts` 的 `defaultTheme` 同值）。 */
    public static final Map<String, Object> DEFAULT_TOKENS = defaultTokens();

    private AiThemeValidator() {}

    /** 平台默认 token 的规范化 JSON（无应用发布修订时的有效主题）。 */
    public static String defaultTokensJson() {
        return JsonUtils.toJsonString(DEFAULT_TOKENS);
    }

    /** 平台默认布局的规范化 JSON。 */
    public static String defaultLayoutJson() {
        return JsonUtils.toJsonString(DEFAULT_LAYOUT);
    }

    private static Map<String, Object> defaultTokens() {
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("primaryColor", "#1677ff");
        tokens.put("radius", 6);
        tokens.put("fontFamily", ALLOWED_FONT_FAMILIES.get(0));
        return tokens;
    }

    private static Map<String, Object> defaultLayout() {
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("fontScale", "normal");
        layout.put("density", "normal");
        layout.put("narrowBreakpoint", 768);
        layout.put("minSidebarWidth", 320);
        return layout;
    }

    /**
     * 校验并规范化 ThemeTokens v1。
     *
     * @param raw 客户端提交的 token JSON（必须含 primaryColor/radius/fontFamily）
     * @return 规范化 JSON（键序固定，便于摘要比对）
     */
    public static String validateTokens(String raw) {
        Map<String, Object> input = parseObject(raw, AI_THEME_TOKENS_INVALID);
        for (String key : input.keySet()) {
            if (!TOKEN_KEYS.contains(key)) {
                throw exception(AI_THEME_TOKENS_INVALID);
            }
        }
        Object primaryColor = input.get("primaryColor");
        if (!(primaryColor instanceof String color) || !COLOR.matcher(color).matches()) {
            throw exception(AI_THEME_TOKENS_INVALID);
        }
        Object radius = input.get("radius");
        if (!(radius instanceof Number number)
                || number.doubleValue() < RADIUS_MIN
                || number.doubleValue() > RADIUS_MAX) {
            throw exception(AI_THEME_TOKENS_INVALID);
        }
        Object fontFamily = input.get("fontFamily");
        if (!(fontFamily instanceof String font) || font.isEmpty() || font.length() > FONT_FAMILY_MAX) {
            throw exception(AI_THEME_TOKENS_INVALID);
        }
        if (!ALLOWED_FONT_FAMILIES.contains(font)) {
            // 字体白名单单独给错误码：这是"远程字体/任意 CSS"最容易混进来的入口，需要可区分的审计信号
            throw exception(AI_THEME_FONT_NOT_ALLOWED);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("primaryColor", color);
        normalized.put("radius", radius);
        normalized.put("fontFamily", font);
        Object colorScheme = input.get("colorScheme");
        if (colorScheme != null) {
            if (!(colorScheme instanceof String scheme) || !COLOR_SCHEMES.contains(scheme)) {
                throw exception(AI_THEME_TOKENS_INVALID);
            }
            normalized.put("colorScheme", scheme);
        }
        return JsonUtils.toJsonString(normalized);
    }

    /**
     * 校验并规范化布局与排版选项。
     *
     * <p>只接受受控枚举与受限数值：布局不参与"像素自由配置"，避免主题变成第二套样式表。
     */
    public static String validateLayout(String raw) {
        Map<String, Object> input = parseObject(raw, AI_THEME_LAYOUT_INVALID);
        for (String key : input.keySet()) {
            if (!LAYOUT_KEYS.contains(key)) {
                throw exception(AI_THEME_LAYOUT_INVALID);
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("fontScale", enumOrDefault(input, "fontScale", FONT_SCALES));
        normalized.put("density", enumOrDefault(input, "density", DENSITIES));
        normalized.put("narrowBreakpoint", integerOrDefault(input, "narrowBreakpoint", BREAKPOINT_MIN, BREAKPOINT_MAX));
        normalized.put("minSidebarWidth", integerOrDefault(input, "minSidebarWidth", SIDEBAR_MIN, SIDEBAR_MAX));
        return JsonUtils.toJsonString(normalized);
    }

    /** tokens 与布局的内容摘要（SHA-256 十六进制）：同内容同摘要，供缓存键与审计比对。 */
    public static String fingerprint(String tokensJson, String layoutJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(tokensJson.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(layoutJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 是 JDK 必备算法；缺失说明运行环境被裁剪，此时宁可直接失败也不返回假摘要
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /** 解析 JSON 对象；非法 JSON、非对象或超长输入统一映射到给定错误码。 */
    private static Map<String, Object> parseObject(String raw, ErrorCode errorCode) {
        if (raw == null || raw.isBlank()) {
            throw exception(errorCode);
        }
        Object parsed;
        try {
            parsed = JsonUtils.parseObject(raw, Map.class);
        } catch (RuntimeException exception) {
            throw exception(errorCode);
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw exception(errorCode);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw exception(errorCode);
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static Object enumOrDefault(Map<String, Object> input, String key, Set<String> allowed) {
        Object value = input.get(key);
        if (value == null) {
            return DEFAULT_LAYOUT.get(key);
        }
        if (!(value instanceof String text) || !allowed.contains(text)) {
            throw exception(AI_THEME_LAYOUT_INVALID);
        }
        return text;
    }

    private static Object integerOrDefault(Map<String, Object> input, String key, int min, int max) {
        Object value = input.get(key);
        if (value == null) {
            return DEFAULT_LAYOUT.get(key);
        }
        if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw exception(AI_THEME_LAYOUT_INVALID);
        }
        int integer = number.intValue();
        if (integer < min || integer > max) {
            throw exception(AI_THEME_LAYOUT_INVALID);
        }
        return integer;
    }

    /** 允许的字体栈（只读副本，供管理端展示"可选字体"而无需另建接口）。 */
    public static List<String> allowedFontFamilies() {
        return new ArrayList<>(ALLOWED_FONT_FAMILIES);
    }
}
