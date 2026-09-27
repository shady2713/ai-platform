package com.basicframework.module.ai.compatibility;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 迁移历史校验器（AT-072 测试内实现）。
 *
 * <p>为什么在测试内实现：本切片只允许改测试目录，不能改 {@code db/migration/**}，也不能改生产装配；
 * 而模块单元测试的 classpath 上没有 Flyway（框架只在 server 模块装配 Flyway）。因此这里按
 * **Flyway validate 的规则语义**实现同一组判定，作为升级演练的可执行证明：
 *
 * <ul>
 *   <li>{@code DUPLICATE_VERSION}：候选里同一版本号出现两次（基础框架升级与自有迁移编号碰撞）；</li>
 *   <li>{@code CHECKSUM_MISMATCH}：已执行迁移的内容被重写（Flyway 在
 *       {@code validate-on-migrate: true} 下会让应用启动失败，见 server 的 {@code application.yaml}）；</li>
 *   <li>{@code OUT_OF_ORDER}：新增迁移的版本号低于已执行水位（上游分支编号分叉，Flyway 默认拒绝）；</li>
 *   <li>{@code MISSING_APPLIED}：候选里少了已执行迁移（历史被删除或替换）。</li>
 * </ul>
 *
 * <p>校验器只读输入，绝不改写已执行历史：新迁移只能"版本号大于水位 + 内容新"地追加。
 */
final class MigrationHistoryValidator {

    enum Rule {
        DUPLICATE_VERSION,
        CHECKSUM_MISMATCH,
        OUT_OF_ORDER,
        MISSING_APPLIED
    }

    /** 一个迁移文件的可比较描述；{@code checksum} 是内容摘要（演练用 SHA-256，语义同 Flyway 的内容校验和）。 */
    record Migration(String version, String description, String filename, String checksum) {}

    record Violation(Rule rule, String detail) {
        boolean is(Rule expected) {
            return rule == expected;
        }
    }

    private MigrationHistoryValidator() {}

    static List<Violation> validate(List<Migration> applied, List<Migration> candidate) {
        List<Violation> violations = new ArrayList<>();
        Map<String, List<Migration>> candidateByVersion = new LinkedHashMap<>();
        for (Migration migration : candidate) {
            candidateByVersion
                    .computeIfAbsent(migration.version(), ignored -> new ArrayList<>())
                    .add(migration);
        }
        candidateByVersion.forEach((version, migrations) -> {
            if (migrations.size() > 1) {
                violations.add(new Violation(
                        Rule.DUPLICATE_VERSION,
                        "版本 " + version + " 在候选中重复："
                                + migrations.stream().map(Migration::filename).toList()));
            }
        });

        Map<String, Migration> appliedByVersion = new LinkedHashMap<>();
        for (Migration migration : applied) {
            appliedByVersion.put(migration.version(), migration);
        }
        for (Migration executed : applied) {
            List<Migration> inCandidate = candidateByVersion.get(executed.version());
            if (inCandidate == null || inCandidate.isEmpty()) {
                violations.add(
                        new Violation(Rule.MISSING_APPLIED, "已执行迁移 " + executed.filename() + " 在候选中缺失（历史被删除或替换）"));
                continue;
            }
            // 已执行版本在候选中的第一个同版本文件必须是同一份内容
            Migration resolved = inCandidate.get(0);
            if (!executed.checksum().equals(resolved.checksum())) {
                violations.add(new Violation(
                        Rule.CHECKSUM_MISMATCH,
                        "已执行迁移 " + executed.filename() + " 的内容被重写（内容摘要 " + executed.checksum() + " → "
                                + resolved.checksum() + "），已执行 SQL 不允许改写"));
            }
        }

        BigDecimal watermark = applied.stream()
                .map(migration -> versionValue(migration.version()))
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (watermark != null) {
            for (Migration migration : candidate) {
                if (appliedByVersion.containsKey(migration.version())) {
                    continue;
                }
                if (versionValue(migration.version()).compareTo(watermark) < 0) {
                    violations.add(new Violation(
                            Rule.OUT_OF_ORDER,
                            "新增迁移 " + migration.filename() + " 的版本号低于已执行水位 " + watermark.toPlainString()
                                    + "，新增迁移只能追加在水位之后"));
                }
            }
        }
        return violations;
    }

    /** 版本号比较值；非数字版本号直接失败，避免演练悄悄跳过。 */
    static BigDecimal versionValue(String version) {
        try {
            return new BigDecimal(version);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("迁移版本号必须是数字：" + version, exception);
        }
    }
}
