package com.basicframework.module.ai.service.audit;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 日志脱敏（Q01）：**不把秘密与业务正文写进日志**的统一入口。
 *
 * <p>三条规则：
 * <ol>
 *   <li><b>白名单优先</b>：需要排查的字段（错误码、主机名、模型标识、耗时）一律保留原值；
 *       只有"看起来像秘密或正文"的内容被替换成固定占位；</li>
 *   <li><b>替换而不是截断</b>：截断会留下前缀（票据前 8 位足以定位账号），所以整段替换为
 *       `[已脱敏]`，并保留"这里原本有内容"的事实；</li>
 *   <li><b>可断言</b>：{@link #containsSensitiveContent} 供测试与运维自检使用——
 *       断言某段文本不含票据、密钥、连接串与 SQL 参数。</li>
 * </ol>
 */
public final class AiAuditLogSanitizer {

    /** 脱敏占位（固定文本，便于日志平台统计"发生过多少次脱敏"）。 */
    public static final String REDACTED = "[已脱敏]";

    /** 票据前缀（A04 的短期票据）。 */
    private static final Pattern TICKET = Pattern.compile("aitkt_[A-Za-z0-9_-]{6,}");

    /** 应用客户端密钥（A01）。 */
    private static final Pattern APP_SECRET = Pattern.compile("aiapp_[A-Za-z0-9_-]{6,}");

    /** 常见密钥字面量：key=value / "password": "..." 这类赋值。 */
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(?:password|passwd|secret|token|api[_-]?key|authorization|credential)\\b['\"]?\\s*[:=]\\s*\\S+");

    /** 连接串（含用户名/口令的 URL）。 */
    private static final Pattern CONNECTION_STRING =
            Pattern.compile("(?i)\\b(?:jdbc|redis|mongodb|mysql|postgresql)://\\S+");

    /** Bearer 令牌。 */
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+\\S+");

    /** SQL 文本（关键字 + 断言：日志里不该出现完整语句）。 */
    private static final Pattern SQL_STATEMENT = Pattern.compile(
            "(?i)\\b(?:select|insert|update|delete|alter|drop)\\b[^;\\n]{0,200}\\bfrom\\b|\\bwhere\\b\\s+\\S+\\s*=\\s*\\S+");

    /** 私钥块。 */
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");

    private static final List<Pattern> PATTERNS =
            List.of(TICKET, APP_SECRET, SECRET_ASSIGNMENT, CONNECTION_STRING, BEARER, SQL_STATEMENT, PRIVATE_KEY);

    private AiAuditLogSanitizer() {}

    /**
     * 脱敏：命中任一敏感形态即整段替换为 {@link #REDACTED}。
     *
     * <p>输入为 null/空即原样返回（不制造噪声）。
     */
    public static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String result = value;
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(result).find()) {
                return REDACTED;
            }
        }
        return result;
    }

    /** 文本中是否含敏感内容（测试与运维自检使用；命中即意味着不该写日志）。 */
    public static boolean containsSensitiveContent(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(value).find()) {
                return true;
            }
        }
        return false;
    }

    /** 结构化字段的脱敏：允许排查用的短值，敏感值与超长值一律替换。 */
    public static String sanitizeField(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        if (containsSensitiveContent(value)) {
            return REDACTED;
        }
        return value.length() <= maxLength ? value : REDACTED;
    }
}
