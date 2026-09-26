package com.basicframework.module.ai.service.evaluation;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.service.audit.AiAuditLogSanitizer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 评测期望规则（Q04）：**程序化核验**，不用模型打分。
 *
 * <p>规则词表（{@code checks_json} 数组，每项一个规则）：
 *
 * <ul>
 *   <li>{@code MONEY}：金额按数值比较（四舍五入到 2 位小数后相等），期望值可写 {@code "450.00"}；
 *       金额是"必须精确"的断言，因此不用字符串比较、接受 {@code 450} 与 {@code 450.00} 等价。</li>
 *   <li>{@code DATE}：按日期比较（ISO {@code yyyy-MM-dd}；带时区的实际值可用 {@code zone} 指定换算区，
 *       缺省 UTC），只比较到天，避免"同一天不同时刻"被判失败。</li>
 *   <li>{@code VALUE}：精确相等（数字按数值比较，其它按文本比较）。</li>
 *   <li>{@code STRUCTURE}：{@code requiredPaths} 全部存在且非 null。</li>
 *   <li>{@code CITATION}：引用必须来自允许集合（{@code mustReferenceAnyOf}，按等值或前缀匹配）——
 *       用于"引用只能来自本次候选"的核验。</li>
 *   <li>{@code NO_SECRET}：指定路径（缺省为整个实际值）不得出现凭据/票据/连接串等敏感内容，
 *       复用审计脱敏器的判定（{@link AiAuditLogSanitizer#containsSensitiveContent}）。</li>
 *   <li>{@code VERSION}：实际版本标识必须等于期望值（模型端点修订、服务发布版本等）。</li>
 * </ul>
 *
 * <p>规则不合规（未知 kind、缺字段、类型不符）一律抛 {@link IllegalArgumentException}：
 * 调用方把它落成 ERROR 判定，而不是把"规则写错"当成"样例通过"。
 */
public final class AiEvalChecks {

    /** 单样例规则条数上限（避免一个样例拖垮评测）。 */
    public static final int MAX_RULES = 32;

    /** 金额比较的标度（元，2 位小数，四舍五入）。 */
    public static final int MONEY_SCALE = 2;

    /** 只允许纯日期字面量的形态（其它形态按时区换算）。 */
    private static final java.util.regex.Pattern DATE_ONLY = java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** 常见密钥前缀（评测报告额外严格：这些串一旦出现在结果里就判失败）。 */
    private static final java.util.regex.Pattern SECRET_PREFIX =
            java.util.regex.Pattern.compile("(?i)\\b(?:sk|rk|pk)-[A-Za-z0-9_-]{8,}");

    private static final Set<String> KINDS =
            Set.of("VALUE", "MONEY", "DATE", "STRUCTURE", "CITATION", "NO_SECRET", "VERSION");

    private AiEvalChecks() {}

    /** 单条规则的判定（只含稳定值与摘要，不含正文）。 */
    public record Verdict(
            int index, String kind, String path, boolean passed, String expected, String observed, String message) {}

    /**
     * 核验一个样例。
     *
     * @param checksJson   期望规则数组（样例配置）
     * @param observedJson 实际事实（执行器给出的结构化观测值）
     * @return 逐条判定（与规则顺序一致）
     * @throws IllegalArgumentException 规则不合规
     */
    public static List<Verdict> verify(String checksJson, String observedJson) {
        List<Map<String, Object>> rules = parseRules(checksJson);
        Map<String, Object> observed = parseObserved(observedJson);
        List<Verdict> verdicts = new ArrayList<>();
        for (int index = 0; index < rules.size(); index++) {
            verdicts.add(verifyOne(index, rules.get(index), observed));
        }
        return verdicts;
    }

    /** 只校验规则本身是否合规（入库与冻结时用；实际事实用空对象，只看规则不抛异常）。 */
    public static void validateRules(String checksJson) {
        verify(checksJson, "{}");
    }

    /** 是否全部通过（空判定视为未核验，返回 false）。 */
    public static boolean allPassed(List<Verdict> verdicts) {
        return verdicts != null && !verdicts.isEmpty() && verdicts.stream().allMatch(Verdict::passed);
    }

    private static List<Map<String, Object>> parseRules(String checksJson) {
        if (checksJson == null || !JsonUtils.isJson(checksJson)) {
            throw new IllegalArgumentException("期望规则必须是 JSON 数组");
        }
        List<Map<String, Object>> rules;
        try {
            rules = JsonUtils.parseArray(checksJson, Map.class).stream()
                    .map(rule -> (Map<String, Object>) rule)
                    .toList();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("期望规则必须是 JSON 数组");
        }
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("期望规则不能为空");
        }
        if (rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("期望规则超过上限 " + MAX_RULES);
        }
        return rules;
    }

    private static Map<String, Object> parseObserved(String observedJson) {
        if (observedJson == null || !JsonUtils.isJsonObject(observedJson)) {
            throw new IllegalArgumentException("实际事实必须是 JSON 对象");
        }
        Map<String, Object> observed;
        try {
            observed = (Map<String, Object>) JsonUtils.parseObject(observedJson, Map.class);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("实际事实必须是 JSON 对象");
        }
        if (observed == null) {
            throw new IllegalArgumentException("实际事实必须是 JSON 对象");
        }
        return observed;
    }

    private static Verdict verifyOne(int index, Map<String, Object> rule, Map<String, Object> observed) {
        if (rule == null) {
            throw new IllegalArgumentException("第 " + index + " 条规则必须是对象");
        }
        String kind = String.valueOf(text(rule, "kind")).toUpperCase(Locale.ROOT);
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("未知规则类型：" + kind);
        }
        String path = text(rule, "path");
        return switch (kind) {
            case "MONEY" -> money(index, path, rule, observed);
            case "DATE" -> date(index, path, rule, observed);
            case "VALUE" -> value(index, path, rule, observed);
            case "STRUCTURE" -> structure(index, rule, observed);
            case "CITATION" -> citation(index, path, rule, observed);
            case "NO_SECRET" -> noSecret(index, path, observed);
            case "VERSION" -> version(index, path, rule, observed);
            default -> throw new IllegalArgumentException("未知规则类型：" + kind);
        };
    }

    private static Verdict money(int index, String path, Map<String, Object> rule, Map<String, Object> observed) {
        BigDecimal expected = money(required(rule, "expected"), index);
        Object actual = lookup(observed, path).orElse(null);
        BigDecimal actualValue = toBigDecimal(actual);
        if (actualValue == null) {
            return new Verdict(index, "MONEY", path, false, expected.toPlainString(), describe(actual), "实际值不是数字或缺失");
        }
        BigDecimal normalizedExpected = expected.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal normalizedActual = actualValue.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        boolean passed = normalizedExpected.compareTo(normalizedActual) == 0;
        return new Verdict(
                index,
                "MONEY",
                path,
                passed,
                normalizedExpected.toPlainString(),
                normalizedActual.toPlainString(),
                passed ? "金额相符（按 2 位小数比较）" : "金额不符");
    }

    private static Verdict date(int index, String path, Map<String, Object> rule, Map<String, Object> observed) {
        LocalDate expected = parseDate(required(rule, "expected"), index);
        String zone = text(rule, "zone");
        ZoneId zoneId = zone == null ? ZoneId.of("UTC") : parseZone(zone, index);
        Object actual = lookup(observed, path).orElse(null);
        LocalDate actualDate = toLocalDate(actual, zoneId);
        if (actualDate == null) {
            return new Verdict(index, "DATE", path, false, expected.toString(), describe(actual), "实际值不是日期或缺失");
        }
        boolean passed = expected.equals(actualDate);
        return new Verdict(
                index,
                "DATE",
                path,
                passed,
                expected.toString(),
                actualDate.toString(),
                passed ? "日期相符（比较到天，时区 " + zoneId + "）" : "日期不符");
    }

    private static Verdict value(int index, String path, Map<String, Object> rule, Map<String, Object> observed) {
        Object expectedNode = expectedValue(rule);
        Object actual = lookup(observed, path).orElse(null);
        boolean passed = sameValue(expectedNode, actual);
        return new Verdict(
                index, "VALUE", path, passed, describe(expectedNode), describe(actual), passed ? "值相符" : "值与期望不一致");
    }

    private static Verdict structure(int index, Map<String, Object> rule, Map<String, Object> observed) {
        List<String> requiredPaths = textList(rule.get("requiredPaths"));
        if (requiredPaths.isEmpty()) {
            throw new IllegalArgumentException("第 " + index + " 条 STRUCTURE 规则缺少 requiredPaths");
        }
        List<String> missing = new ArrayList<>();
        for (String path : requiredPaths) {
            if (lookup(observed, path).isEmpty()) {
                missing.add(path);
            }
        }
        boolean passed = missing.isEmpty();
        return new Verdict(
                index,
                "STRUCTURE",
                null,
                passed,
                String.join("、", requiredPaths),
                missing.isEmpty() ? "全部存在" : missing.toString(),
                passed ? "结构完整" : "缺少字段：" + String.join("、", missing));
    }

    private static Verdict citation(int index, String path, Map<String, Object> rule, Map<String, Object> observed) {
        List<String> allowedRefs = textList(rule.get("mustReferenceAnyOf"));
        if (allowedRefs.isEmpty()) {
            throw new IllegalArgumentException("第 " + index + " 条 CITATION 规则缺少 mustReferenceAnyOf");
        }
        Object actual = lookup(observed, path).orElse(null);
        List<String> actualRefs = toStringList(actual);
        Optional<String> hit = actualRefs.stream()
                .filter(ref -> allowedRefs.stream().anyMatch(allow -> ref.equals(allow) || ref.startsWith(allow)))
                .findFirst();
        boolean passed = hit.isPresent();
        return new Verdict(
                index,
                "CITATION",
                path,
                passed,
                String.join("、", allowedRefs),
                actualRefs.isEmpty() ? describe(actual) : String.join("、", actualRefs),
                passed ? "引用来自允许来源" : "引用不在允许来源内");
    }

    private static Verdict noSecret(int index, String path, Map<String, Object> observed) {
        Object actual = path == null ? observed : lookup(observed, path).orElse(null);
        String text = actual == null ? "" : JsonUtils.toJsonString(actual);
        // 双重判定：审计脱敏器的模式 + 常见密钥前缀（评测报告比日志更严格，宁可误报也不回显疑似密钥）
        boolean sensitive = AiAuditLogSanitizer.containsSensitiveContent(text)
                || SECRET_PREFIX.matcher(text).find();
        return new Verdict(
                index,
                "NO_SECRET",
                path,
                !sensitive,
                "无凭据/票据/连接串",
                sensitive ? "[已脱敏]" : "未发现敏感内容",
                sensitive ? "实际结果里出现疑似敏感内容" : "未发现敏感内容");
    }

    private static Verdict version(int index, String path, Map<String, Object> rule, Map<String, Object> observed) {
        String expected = required(rule, "expected");
        Object actual = lookup(observed, path).orElse(null);
        String actualText = actual == null ? null : String.valueOf(actual);
        boolean passed = expected.equals(actualText);
        return new Verdict(index, "VERSION", path, passed, expected, describe(actual), passed ? "版本相符" : "版本与期望不一致");
    }

    /** 规则里的必填文本；缺失或空白即规则不合规。 */
    private static String required(Map<String, Object> rule, String field) {
        String value = text(rule, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("规则缺少字段：" + field);
        }
        return value;
    }

    private static String text(Map<String, Object> rule, String field) {
        Object node = rule.get(field);
        return node == null ? null : String.valueOf(node);
    }

    /** 期望值原样取出（数字/布尔/文本都可，不强制转字符串）。 */
    private static Object expectedValue(Map<String, Object> rule) {
        Object value = rule.get("expected");
        if (value == null) {
            throw new IllegalArgumentException("规则缺少字段：expected");
        }
        return value;
    }

    private static List<String> textList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                texts.add(String.valueOf(item));
            }
        }
        return texts;
    }

    /** 点号路径取数（支持 {@code a.b[0].c}）：不存在返回空，区别于"存在但为 null"。 */
    static Optional<Object> lookup(Map<String, Object> root, String path) {
        if (path == null || path.isBlank()) {
            return Optional.of(root);
        }
        Object current = root;
        for (String rawSegment : path.split("\\.")) {
            String segment = rawSegment;
            while (segment.indexOf('[') >= 0) {
                int bracket = segment.indexOf('[');
                String name = segment.substring(0, bracket);
                int close = segment.indexOf(']', bracket);
                if (close < 0) {
                    return Optional.empty();
                }
                String indexText = segment.substring(bracket + 1, close);
                if (!name.isEmpty()) {
                    current = step(current, name);
                }
                current = stepIndex(current, indexText);
                segment = segment.substring(close + 1);
            }
            if (!segment.isEmpty()) {
                current = step(current, segment);
            }
            if (current == null) {
                return Optional.empty();
            }
        }
        return Optional.of(current);
    }

    private static Object step(Object current, String name) {
        if (current instanceof Map<?, ?> map) {
            return map.containsKey(name) ? map.get(name) : null;
        }
        return null;
    }

    private static Object stepIndex(Object current, String indexText) {
        if (!(current instanceof List<?> list)) {
            return null;
        }
        try {
            int index = Integer.parseInt(indexText);
            return index >= 0 && index < list.size() ? list.get(index) : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static BigDecimal money(String text, int index) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("第 " + index + " 条规则金额不是数字：" + text);
        }
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    private static LocalDate parseDate(String text, int index) {
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("第 " + index + " 条规则日期不是 ISO 日期：" + text);
        }
    }

    private static ZoneId parseZone(String zone, int index) {
        try {
            return ZoneId.of(zone);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("第 " + index + " 条规则时区非法：" + zone);
        }
    }

    private static LocalDate toLocalDate(Object value, ZoneId zoneId) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (DATE_ONLY.matcher(text).matches()) {
            return LocalDate.parse(text);
        }
        try {
            // 带偏移/时区的实际值：按规则指定的时区换算到天（默认 UTC）
            return ZonedDateTime.parse(text).withZoneSameInstant(zoneId).toLocalDate();
        } catch (DateTimeParseException exception) {
            try {
                // 无时区的本地时间：按字面日期比较，不做换算
                return LocalDateTime.parse(text).toLocalDate();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static boolean sameValue(Object expected, Object actual) {
        BigDecimal expectedNumber = toBigDecimal(expected);
        BigDecimal actualNumber = toBigDecimal(actual);
        if (expectedNumber != null && actualNumber != null) {
            return expectedNumber.compareTo(actualNumber) == 0;
        }
        return Objects.equals(expected, actual);
    }

    private static List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            if (item instanceof Map<?, ?> map) {
                Object ref = map.get("ref") == null ? map.get("id") : map.get("ref");
                if (ref != null) {
                    result.add(String.valueOf(ref));
                }
                continue;
            }
            result.add(String.valueOf(item));
        }
        return result;
    }

    /** 期望/实际的稳定描述（列表只给条数，避免把正文带进报告）。 */
    private static String describe(Object value) {
        if (value == null) {
            return "<缺失>";
        }
        if (value instanceof List<?> list) {
            return "[" + list.size() + " 项]";
        }
        if (value instanceof Map<?, ?> map) {
            return "{" + map.size() + " 字段}";
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            return trimmed.length() <= 64 ? trimmed : trimmed.substring(0, 64) + "…";
        }
        return String.valueOf(value);
    }
}
