package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 验证凭据封存、废弃表删除，以及快照显式基线的升级路径。 */
@Testcontainers
class AuthenticationMigrationIT {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
                            "mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("basic_framework")
            .withUsername("root")
            .withPassword("integration-only");

    @Test
    void upgradeRemovesFactorsAndDisablesSharedSeed() {
        DataSource source = newDatabase("auth_upgrade");
        Flyway.configure().dataSource(source).target("40").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        assertThat(flyway(source).migrate().migrationsExecuted).isEqualTo(MigrationTestSupport.migrationCountAfter(40));
        assertThat(jdbc.queryForObject("SELECT password FROM system_users WHERE id = 1", String.class))
                .isEqualTo("!bootstrap-required");
        assertThat(jdbc.queryForObject("SELECT status FROM system_users WHERE id = 1", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                                + "AND table_name IN ('system_user_mfa_factor', 'system_user_mfa_recovery_code')",
                        Integer.class))
                .isZero();
    }

    @Test
    void upgradePreservesIndependentlyChangedSeedCredential() {
        DataSource source = newDatabase("auth_custom");
        Flyway.configure().dataSource(source).target("40").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update("UPDATE system_users SET password = ?, status = 0 WHERE id = 1", "independent-credential");
        flyway(source).migrate();
        assertThat(jdbc.queryForObject("SELECT password FROM system_users WHERE id = 1", String.class))
                .isEqualTo("independent-credential");
        assertThat(jdbc.queryForObject("SELECT status FROM system_users WHERE id = 1", Integer.class))
                .isZero();
    }

    @Test
    void duplicateActiveContactsStopUpgradeWithoutChoosingAnOwner() {
        DataSource source = newDatabase("contact_duplicate_upgrade");
        Flyway.configure().dataSource(source).target("44").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update(
                "INSERT INTO system_users (username, nickname, mobile) VALUES ('first-owner', '重复联系方式测试', '13900000009'), ('second-owner', '重复联系方式测试', '13900000009')");
        assertThatThrownBy(() -> flyway(source).migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("Duplicate entry");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM system_users WHERE mobile = '13900000009' AND deleted = b'0'",
                        Integer.class))
                .isEqualTo(2);
    }

    @Test
    void latestSnapshotCanBeExplicitlyBaselinedWithoutReplayingOlderMigrations() throws Exception {
        DataSource source = newDatabase("auth_snapshot");
        try (Connection connection = source.getConnection()) {
            connection.createStatement().execute("SET FOREIGN_KEY_CHECKS=0");
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(snapshotPath()));
            connection.createStatement().execute("SET FOREIGN_KEY_CHECKS=1");
        }
        int snapshotVersion = snapshotVersion();
        assertThat(snapshotVersion).isEqualTo(MigrationTestSupport.latestVersion());
        Flyway baseline = Flyway.configure()
                .dataSource(source)
                .baselineVersion(String.valueOf(snapshotVersion))
                .load();
        baseline.baseline();
        assertThat(baseline.migrate().migrationsExecuted).isZero();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT status FROM system_users WHERE id = 1", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM system_users WHERE id = 1", Boolean.class))
                .isTrue();
        DataSource migratedSource = newDatabase("snapshot_reference");
        flyway(migratedSource).migrate();
        JdbcTemplate migrated = new JdbcTemplate(migratedSource);
        assertSchemaMatches(jdbc, migrated);
        assertQueryMatches(
                jdbc,
                migrated,
                "种子管理员",
                "SELECT id, username, password, dept_id, status, must_change_password, deleted, email, mobile FROM system_users ORDER BY id");
        assertQueryMatches(
                jdbc,
                migrated,
                "初始化部门",
                "SELECT id, name, parent_id, leader_user_id, status, deleted FROM system_dept ORDER BY id");
        assertQueryMatches(
                jdbc, migrated, "字典类型", "SELECT id, name, type, status, deleted FROM system_dict_type ORDER BY id");
        assertQueryMatches(
                jdbc,
                migrated,
                "字典值",
                "SELECT id, dict_type, label, value, status, deleted FROM system_dict_data ORDER BY id");
        assertQueryMatches(
                jdbc,
                migrated,
                "登录与短信渠道字典",
                "SELECT dict_type, value, status FROM system_dict_data "
                        + "WHERE dict_type IN ('system_login_type', 'system_sms_channel_code') ORDER BY dict_type, value");
    }

    private static void assertSchemaMatches(JdbcTemplate snapshot, JdbcTemplate migrated) {
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

    private static void assertQueryMatches(JdbcTemplate snapshot, JdbcTemplate migrated, String label, String query) {
        assertThat(snapshot.queryForList(query)).as("快照与迁移链一致：%s", label).isEqualTo(migrated.queryForList(query));
    }

    private static int snapshotVersion() throws Exception {
        Matcher matcher = Pattern.compile(
                        "-- Snapshot note: aligned with the authoritative Flyway migration chain through V(\\d+)\\.")
                .matcher(Files.readString(snapshotPath()));
        assertThat(matcher.find()).as("快照必须声明已验证的 Flyway 版本").isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static DataSource newDatabase(String name) {
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.execute("CREATE DATABASE " + name);
        return new DriverManagerDataSource(
                MYSQL.getJdbcUrl().replace("/basic_framework", "/" + name), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static Flyway flyway(DataSource source) {
        return Flyway.configure().dataSource(source).load();
    }

    private static Path snapshotPath() {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path snapshot = directory.resolve("数据库文件/basic_framework.sql");
            if (Files.isRegularFile(snapshot)) return snapshot;
        }
        throw new IllegalStateException("Database snapshot not found");
    }
}
