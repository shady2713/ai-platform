package com.basicframework.module.ai.domain.semantic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 主数据映射事实的判定算法（Y02）：有效期、冲突与版本指纹。
 *
 * <p>把这些规则集中在一个纯函数类里，是因为它们同时被三条路径使用，任何一条走偏都会造成
 * "同一份映射在不同路径下结论不同"：
 * <ul>
 *   <li><b>登记与发布校验</b>（{@link #windowLineConflicts}、{@link #windowObjectConflicts}）：
 *       按**时间窗**检查——两条映射只要有效期重叠就是冲突，不能等到它们"今天恰好都生效"才拦；</li>
 *   <li><b>判定路径</b>（{@link #instantLineConflicts}、{@link #instantObjectConflicts}）：
 *       按**判定时刻**检查——有效期不覆盖该时刻的行不参与，冲突则阻断；</li>
 *   <li><b>版本可核验</b>（{@link #fingerprint}）：版本发布时冻结的指纹在读取时重算比对。</li>
 * </ul>
 *
 * <p>三条不可让步的规则：
 * <ol>
 *   <li><b>半开有效期</b>：{@code [validFrom, validTo)}；{@code validTo == null} 表示长期有效。
 *       边界时刻属于"生效起点"而非"失效终点"，相邻两段窗口因此不会重叠出双份事实；</li>
 *   <li><b>冲突必须可见</b>：一对多（同对象同系统同实体类型多条）与多对一（同源键属于多个对象）
 *       都返回冲突，**不排序取第一个**；</li>
 *   <li><b>指纹只看判定事实</b>：对象、系统、实体类型、源键、匹配方式与有效期参与哈希，展示名不参与——
 *       改展示名不该让历史版本"指纹不符"，改源键/有效期必须让旧指纹失效。</li>
 * </ol>
 */
public final class AiMasterMappingFacts {

    private AiMasterMappingFacts() {}

    /** 有效期是否覆盖判定时刻（半开区间；{@code validTo} 为空表示长期有效）。 */
    public static boolean inForce(LocalDateTime validFrom, LocalDateTime validTo, LocalDateTime asOf) {
        if (validFrom == null || asOf == null) {
            return false;
        }
        if (asOf.isBefore(validFrom)) {
            return false;
        }
        return validTo == null || asOf.isBefore(validTo);
    }

    /** 过滤出判定时刻生效的行（保持入参顺序）。 */
    public static List<AiMasterMappingLine> inForceLines(List<AiMasterMappingLine> lines, LocalDateTime asOf) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        return lines.stream().filter(line -> line.inForceAt(asOf)).toList();
    }

    /** 两条映射的有效期是否重叠（任一端为长期有效即视为在该方向上无界）。 */
    public static boolean overlaps(AiMasterMappingLine left, AiMasterMappingLine right) {
        boolean leftBeforeRightEnd = right.validTo() == null || left.validFrom().isBefore(right.validTo());
        boolean rightBeforeLeftEnd = left.validTo() == null || right.validFrom().isBefore(left.validTo());
        return leftBeforeRightEnd && rightBeforeLeftEnd;
    }

    /**
     * 版本内容指纹：对判定事实按稳定顺序哈希。
     *
     * <p>排序键用 {@link AiMasterMappingLine#canonicalText()}，因此"登记顺序不同、内容相同"的两个
     * 版本指纹一致；展示名与增删时间不参与。
     */
    public static String fingerprint(List<AiMasterMappingLine> lines) {
        String joined = (lines == null ? List.<AiMasterMappingLine>of() : lines)
                .stream().map(AiMasterMappingLine::canonicalText).sorted().collect(Collectors.joining("\n"));
        return sha256(joined);
    }

    /** 判定时刻的一对多冲突：同一（对象, 系统, 实体类型）下生效行超过一条。 */
    public static Map<String, List<AiMasterMappingLine>> instantLineConflicts(
            List<AiMasterMappingLine> lines, LocalDateTime asOf) {
        return conflictGroups(
                inForceLines(lines, asOf), AiMasterMappingLine::objectSystemIdentity, group -> group.size() > 1);
    }

    /** 判定时刻的多对一冲突：同一（系统, 实体类型, 源键）生效行属于多个对象。 */
    public static Map<String, List<AiMasterMappingLine>> instantObjectConflicts(
            List<AiMasterMappingLine> lines, LocalDateTime asOf) {
        return conflictGroups(
                inForceLines(lines, asOf),
                AiMasterMappingLine::sourceIdentity,
                AiMasterMappingFacts::spansMultipleObjects);
    }

    /** 发布前的一对多检查：同一（对象, 系统, 实体类型）下任意两条有效期重叠即冲突。 */
    public static Map<String, List<AiMasterMappingLine>> windowLineConflicts(List<AiMasterMappingLine> lines) {
        return conflictGroups(lines, AiMasterMappingLine::objectSystemIdentity, AiMasterMappingFacts::hasOverlap);
    }

    /** 发布前的多对一检查：同一源键在有效期重叠的前提下被登记到多个对象即冲突。 */
    public static Map<String, List<AiMasterMappingLine>> windowObjectConflicts(List<AiMasterMappingLine> lines) {
        return conflictGroups(
                lines, AiMasterMappingLine::sourceIdentity, group -> hasOverlap(group) && spansMultipleObjects(group));
    }

    /** 冲突分组的稳定描述（错误码上下文与页面展示；键只含系统/实体类型/源键或对象/系统/实体类型）。 */
    public static String describeConflicts(Map<String, List<AiMasterMappingLine>> conflicts) {
        return conflicts.keySet().stream().sorted().collect(Collectors.joining(","));
    }

    /** 文本的 SHA-256 十六进制摘要（目录指纹等稳定摘要共用同一实现，避免各处各写一份）。 */
    public static String digest(String value) {
        return sha256(value == null ? "" : value);
    }

    private static Map<String, List<AiMasterMappingLine>> conflictGroups(
            List<AiMasterMappingLine> candidates,
            Function<AiMasterMappingLine, String> keyFunction,
            Predicate<List<AiMasterMappingLine>> conflictPredicate) {
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        Map<String, List<AiMasterMappingLine>> grouped = new LinkedHashMap<>();
        for (AiMasterMappingLine line : candidates) {
            grouped.computeIfAbsent(keyFunction.apply(line), key -> new ArrayList<>())
                    .add(line);
        }
        Map<String, List<AiMasterMappingLine>> conflicts = new LinkedHashMap<>();
        grouped.forEach((key, group) -> {
            if (conflictPredicate.test(group)) {
                conflicts.put(key, List.copyOf(group));
            }
        });
        return conflicts;
    }

    private static boolean spansMultipleObjects(List<AiMasterMappingLine> group) {
        return group.stream()
                        .map(AiMasterMappingLine::masterObjectId)
                        .distinct()
                        .count()
                > 1;
    }

    private static boolean hasOverlap(List<AiMasterMappingLine> group) {
        for (int left = 0; left < group.size(); left++) {
            for (int right = left + 1; right < group.size(); right++) {
                if (overlaps(group.get(left), group.get(right))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String sha256(String value) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
