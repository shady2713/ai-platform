package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 快照与迁移链结构比对支持（AT-063）。
 *
 * <p>快照 `数据库文件/basic_framework.sql` 是人工部署用的完整最新结构；它与 Flyway 迁移链必须逐项一致，
 * 否则“导入快照”和“空库迁移”会产出两个不同的数据库。比对覆盖表属性、字段、索引、外键与检查约束，
 * 只排除 `flyway_schema_history`（两种装配方式的历史记录本来就不同，不属结构差异）。
 */
final class SchemaSnapshotSupport {

    private SchemaSnapshotSupport() {}

    /** 仓库根下的权威快照；从后端模块工作目录向上查找。 */
    static Path snapshotPath() {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path snapshot = directory.resolve("数据库文件/basic_framework.sql");
            if (Files.isRegularFile(snapshot)) {
                return snapshot;
            }
        }
        throw new IllegalStateException("Database snapshot not found");
    }

    /** 快照头部声明的 Flyway 版本；迁移链必须与它对齐。 */
    static int snapshotVersion() throws Exception {
        Matcher matcher = Pattern.compile(
                        "-- Snapshot note: aligned with the authoritative Flyway migration chain through V(\\d+)\\.")
                .matcher(Files.readString(snapshotPath()));
        assertThat(matcher.find()).as("快照必须声明已验证的 Flyway 版本").isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    static void assertSchemaMatches(JdbcTemplate snapshot, JdbcTemplate migrated) {
        Map<String, String> queries = Map.of(
                "表属性",
                        """
                        SELECT table_name, engine, table_collation
                        FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
                        ORDER BY table_name
                        """,
                "字段契约",
                        """
                        SELECT table_name, column_name, ordinal_position, column_type, is_nullable,
                               column_default, extra, character_set_name, collation_name, generation_expression
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
                        ORDER BY table_name, ordinal_position
                        """,
                "索引",
                        """
                        SELECT table_name, index_name, non_unique, seq_in_index, column_name,
                               collation, sub_part, index_type, is_visible, expression
                        FROM information_schema.statistics
                        WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
                        ORDER BY table_name, index_name, seq_in_index
                        """,
                "外键",
                        """
                        SELECT k.table_name, k.constraint_name, k.column_name, k.ordinal_position,
                               k.referenced_table_name, k.referenced_column_name, r.update_rule, r.delete_rule
                        FROM information_schema.key_column_usage k
                        JOIN information_schema.referential_constraints r
                          ON r.constraint_schema = k.constraint_schema AND r.constraint_name = k.constraint_name
                        WHERE k.constraint_schema = DATABASE()
                        ORDER BY k.table_name, k.constraint_name, k.ordinal_position
                        """,
                "检查约束",
                        """
                        SELECT t.table_name, t.constraint_name, t.enforced, c.check_clause
                        FROM information_schema.table_constraints t
                        JOIN information_schema.check_constraints c
                          ON c.constraint_schema = t.constraint_schema AND c.constraint_name = t.constraint_name
                        WHERE t.constraint_schema = DATABASE()
                        ORDER BY t.table_name, t.constraint_name
                        """);
        queries.forEach((label, query) -> assertQueryMatches(snapshot, migrated, label, query));
    }

    static void assertQueryMatches(JdbcTemplate snapshot, JdbcTemplate migrated, String label, String query) {
        assertThat(snapshot.queryForList(query)).as("快照与迁移链一致：%s", label).isEqualTo(migrated.queryForList(query));
    }
}
