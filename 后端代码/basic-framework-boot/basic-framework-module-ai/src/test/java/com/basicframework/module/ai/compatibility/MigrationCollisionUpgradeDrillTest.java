package com.basicframework.module.ai.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.compatibility.MigrationHistoryValidator.Migration;
import com.basicframework.module.ai.compatibility.MigrationHistoryValidator.Rule;
import com.basicframework.module.ai.compatibility.MigrationHistoryValidator.Violation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * AT-072 基础框架升级的迁移碰撞演练（保留已执行历史，测试阻断错误覆盖）。
 *
 * <p>演练用的"已执行历史"**不是**测试内随手编的常量，而是从真实仓库的
 * {@code basic-framework-server/src/main/resources/db/migration} 读取当前全部迁移文件
 * （版本号 + 内容摘要）：这就是发布时已经执行过的历史快照。候选集合在内存里构造
 * （模拟框架升级带进来的新迁移/被改写的旧文件），**不写任何历史 SQL**。
 *
 * <p>演练要证明四件事：
 * <ol>
 *   <li>真实迁移集本身合法（版本唯一且严格递增）；</li>
 *   <li>框架升级带来的**编号碰撞**（同号不同文件）被阻断；</li>
 *   <li>**重写旧 SQL** 被阻断（内容摘要变化）；</li>
 *   <li>**追加新迁移**可以放行，且已执行历史不被改动（只增不改）。</li>
 * </ol>
 *
 * <p>校验规则与 Flyway validate 的语义一致（见 {@link MigrationHistoryValidator}）；生产侧同样的
 * 阻断由 {@code spring.flyway.validate-on-migrate: true}（server {@code application.yaml}）在启动时执行。
 */
class MigrationCollisionUpgradeDrillTest {

    private static final Pattern MIGRATION_FILE = Pattern.compile("^V(\\d+(?:\\.\\d+)?)__([A-Za-z0-9_]+)\\.sql$");

    /** 从真实迁移目录读取"已执行历史"（版本号 + SHA-256 内容摘要）。 */
    private static List<Migration> appliedHistoryFromRepository() {
        Path directory = CompatibilityRepositorySupport.migrationDirectory();
        try (Stream<Path> files = Files.list(directory)) {
            List<Migration> migrations = new ArrayList<>();
            for (Path file : files.toList()) {
                String filename = file.getFileName().toString();
                Matcher matcher = MIGRATION_FILE.matcher(filename);
                if (!matcher.matches()) {
                    continue;
                }
                migrations.add(new Migration(
                        matcher.group(1),
                        matcher.group(2),
                        filename,
                        CompatibilityRepositorySupport.sha256(CompatibilityRepositorySupport.readBytes(file))));
            }
            migrations.sort(
                    Comparator.comparing(migration -> MigrationHistoryValidator.versionValue(migration.version())));
            return List.copyOf(migrations);
        } catch (IOException exception) {
            throw new UncheckedIOException("读取 Flyway 迁移目录失败：" + directory, exception);
        }
    }

    private static Migration appendedProbe(int versionOffset) {
        return new Migration(
                String.valueOf(1000 + versionOffset),
                "ai_compat_probe",
                "V" + (1000 + versionOffset) + "__ai_compat_probe.sql",
                CompatibilityRepositorySupport.sha256("-- q08 演练：仅用于候选集合的新迁移\n"));
    }

    @Test
    void realAppliedHistoryIsUniqueAndMonotonic() {
        List<Migration> applied = appliedHistoryFromRepository();

        assertThat(applied).as("迁移目录必须能找到真实迁移").hasSizeGreaterThanOrEqualTo(80);
        assertThat(applied.stream().map(Migration::version).distinct().count())
                .as("真实迁移集不允许版本号重复")
                .isEqualTo(applied.size());
        for (int index = 1; index < applied.size(); index++) {
            BigDecimal previous = MigrationHistoryValidator.versionValue(
                    applied.get(index - 1).version());
            BigDecimal current =
                    MigrationHistoryValidator.versionValue(applied.get(index).version());
            assertThat(current)
                    .as("迁移版本必须严格递增：" + applied.get(index - 1).filename() + " → "
                            + applied.get(index).filename())
                    .isGreaterThan(previous);
        }
        assertThat(MigrationHistoryValidator.validate(applied, applied))
                .as("未做任何变更的发布候选必须通过校验")
                .isEmpty();
    }

    @Test
    void frameworkUpgradeWithCollidingNewMigrationIsBlocked() {
        List<Migration> applied = appliedHistoryFromRepository();
        Migration executed = applied.get(applied.size() - 1);
        // 基础框架升级分支带来了一个与已执行迁移同号的"新"迁移
        Migration colliding = new Migration(
                executed.version(),
                "framework_hotfix",
                "V" + executed.version() + "__framework_hotfix.sql",
                CompatibilityRepositorySupport.sha256("-- 框架升级分支的同号迁移（不同内容）\n"));

        List<Violation> violations = MigrationHistoryValidator.validate(applied, append(applied, colliding));

        assertThat(violations).as("同号不同文件的迁移必须被阻断，而不是靠文件名顺序碰运气").anySatisfy(violation -> assertThat(
                        violation.is(Rule.DUPLICATE_VERSION))
                .isTrue());
        assertThat(violations).as("阻断信息必须同时点名两个冲突文件与版本号").anySatisfy(violation -> assertThat(violation.detail())
                .contains(executed.filename())
                .contains(colliding.filename())
                .contains("版本 " + executed.version()));
    }

    @Test
    void rewritingExecutedSqlIsBlocked() {
        List<Migration> applied = appliedHistoryFromRepository();
        List<Migration> rewritten = new ArrayList<>(applied);
        Migration first = applied.get(0);
        String originalContent = CompatibilityRepositorySupport.readString(
                CompatibilityRepositorySupport.migrationDirectory().resolve(first.filename()));
        // 内存中改一个已执行迁移的内容（不落盘、不改仓库历史 SQL）
        String tampered = originalContent + "\n-- q08 演练：被改写的已执行迁移\n";
        rewritten.set(
                0,
                new Migration(
                        first.version(),
                        first.description(),
                        first.filename(),
                        CompatibilityRepositorySupport.sha256(tampered)));

        List<Violation> violations = MigrationHistoryValidator.validate(applied, rewritten);

        assertThat(violations).as("已执行 SQL 的内容改写必须被阻断").anySatisfy(violation -> {
            assertThat(violation.is(Rule.CHECKSUM_MISMATCH)).isTrue();
            assertThat(violation.detail()).contains(first.filename()).contains("不允许改写");
        });
        assertThat(violations).hasSize(1);
    }

    @Test
    void outOfOrderNewMigrationIsBlocked() {
        List<Migration> applied = appliedHistoryFromRepository();
        Migration renumbered = new Migration(
                "79.1",
                "upstream_hotfix",
                "V79_1__upstream_hotfix.sql",
                CompatibilityRepositorySupport.sha256("-- 上游分叉的低位号新迁移\n"));

        List<Violation> violations = MigrationHistoryValidator.validate(applied, append(applied, renumbered));

        assertThat(violations)
                .as("版本号低于已执行水位的新迁移必须被阻断（必须重编号为水位之后）")
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.is(Rule.OUT_OF_ORDER)).isTrue();
                    assertThat(violation.detail())
                            .contains(renumbered.filename())
                            .contains("水位");
                });
    }

    @Test
    void removingExecutedMigrationIsBlocked() {
        List<Migration> applied = appliedHistoryFromRepository();
        List<Migration> withoutOne = new ArrayList<>(applied);
        Migration removed = withoutOne.remove(0);

        List<Violation> violations = MigrationHistoryValidator.validate(applied, withoutOne);

        assertThat(violations).as("删除已执行迁移必须被阻断").singleElement().satisfies(violation -> assertThat(
                        violation.is(Rule.MISSING_APPLIED))
                .isTrue());
        assertThat(violations.get(0).detail()).contains(removed.filename());
    }

    @Test
    void appendingNewMigrationKeepsExecutedHistoryIntact() {
        List<Migration> applied = appliedHistoryFromRepository();
        List<Migration> before = List.copyOf(applied);
        BigDecimal watermark = applied.stream()
                .map(migration -> MigrationHistoryValidator.versionValue(migration.version()))
                .max(Comparator.naturalOrder())
                .orElseThrow();
        Migration newMigration = appendedProbe(1);

        List<Violation> violations = MigrationHistoryValidator.validate(applied, append(applied, newMigration));

        assertThat(violations).as("追加水位之后的新迁移应放行").isEmpty();
        assertThat(MigrationHistoryValidator.versionValue(newMigration.version()))
                .as("新增迁移版本必须大于已执行水位")
                .isGreaterThan(watermark);
        assertThat(applied).as("演练过程中已执行历史（版本号 + 内容摘要）必须保持原样").isEqualTo(before);
        MigrationHistoryValidator.validate(applied, append(applied, newMigration));
        assertThat(applied).isEqualTo(before);
    }

    private static List<Migration> append(List<Migration> applied, Migration extra) {
        List<Migration> candidate = new ArrayList<>(applied);
        candidate.add(extra);
        return candidate;
    }
}
