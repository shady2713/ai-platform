package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * AT-063：空库安装与已发布基线升级的迁移链验证（真实 MySQL）。
 *
 * <p>两条路径必须收敛到同一结构：
 * <ul>
 *   <li><b>空库安装</b>：全量 Flyway 迁移链的结果与人工接管用的最新快照
 *       ({@code 数据库文件/basic_framework.sql}) 逐项一致（表属性、字段、索引、外键、检查约束）；</li>
 *   <li><b>已发布基线升级</b>：从框架已发布基线 V46（见
 *       {@code docs/ai-platform/01-framework-baseline.md}“数据迁移：当前最高 V46”）出发，
 *       在保留既有业务数据的前提下升级到最新迁移，结构与快照一致且 Flyway 校验通过。</li>
 * </ul>
 *
 * <p>断言只针对可观察状态（information_schema 与业务行），不依赖应用自述；不改动既有 IT 的断言。
 */
@Testcontainers
class ReleasedBaselineUpgradeIT {

    /** 已发布基线的最高迁移号：AI 平台迁移（V47+）之前框架最后发布的版本。 */
    private static final int PUBLISHED_BASELINE_VERSION = 46;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
                            "mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("basic_framework")
            .withUsername("root")
            .withPassword("integration-only");

    @Test
    void emptyDatabaseMigratedSchemaMatchesSnapshot() throws Exception {
        JdbcTemplate snapshot = new JdbcTemplate(snapshotDatabase("at063_empty_snapshot"));
        DataSource empty = newDatabase("at063_empty");
        JdbcTemplate migrated = new JdbcTemplate(empty);

        assertThat(flyway(empty).migrate().migrationsExecuted)
                .as("空库执行完整迁移链")
                .isEqualTo(MigrationTestSupport.migrationCount());
        assertVersionAtLatest(migrated);
        SchemaSnapshotSupport.assertSchemaMatches(snapshot, migrated);
        SchemaSnapshotSupport.assertQueryMatches(
                snapshot,
                migrated,
                "种子管理员",
                "SELECT id, username, password, dept_id, status, must_change_password, deleted, email, mobile FROM system_users ORDER BY id");
        SchemaSnapshotSupport.assertQueryMatches(
                snapshot,
                migrated,
                "初始化部门",
                "SELECT id, name, parent_id, leader_user_id, status, deleted FROM system_dept ORDER BY id");
    }

    @Test
    void publishedBaselineUpgradePreservesRowsAndMatchesSnapshot() throws Exception {
        DataSource upgraded = newDatabase("at063_baseline_upgrade");
        JdbcTemplate jdbc = new JdbcTemplate(upgraded);

        Flyway baseline = Flyway.configure()
                .dataSource(upgraded)
                .target(String.valueOf(PUBLISHED_BASELINE_VERSION))
                .load();
        assertThat(baseline.migrate().migrationsExecuted)
                .as("先装出已发布基线 V%d", PUBLISHED_BASELINE_VERSION)
                .isEqualTo(MigrationTestSupport.migrationCount()
                        - MigrationTestSupport.migrationCountAfter(PUBLISHED_BASELINE_VERSION));
        assertThat(currentVersion(jdbc)).isEqualTo(String.valueOf(PUBLISHED_BASELINE_VERSION));

        // 模拟已发布库中已有业务数据与已改密的账号：升级不得清空或覆盖
        jdbc.update(
                "UPDATE system_users SET password = ?, must_change_password = b'0' WHERE id = 1", "at063-independent");
        jdbc.update(
                """
                INSERT INTO system_dict_type
                    (id, name, type, status, remark, creator, create_time, updater, update_time, deleted)
                VALUES (9001, 'AT063 升级保留', 'at063_upgrade', 0, '升级演练标记', '1', NOW(), '1', NOW(), b'0')
                """);
        jdbc.update(
                """
                INSERT INTO system_dict_data
                    (id, sort, label, value, dict_type, status, color_type, css_class, remark,
                     creator, create_time, updater, update_time, deleted)
                VALUES (9001, 1, '保留', 'keep', 'at063_upgrade', 0, '', '', '升级演练标记',
                        '1', NOW(), '1', NOW(), b'0')
                """);

        Flyway upgrade = flyway(upgraded);
        assertThat(upgrade.migrate().migrationsExecuted)
                .as("从 V%d 升级到最新只执行增量迁移", PUBLISHED_BASELINE_VERSION)
                .isEqualTo(MigrationTestSupport.migrationCountAfter(PUBLISHED_BASELINE_VERSION));
        assertVersionAtLatest(jdbc);
        upgrade.validate();

        assertThat(jdbc.queryForObject("SELECT password FROM system_users WHERE id = 1", String.class))
                .as("升级不覆盖已独立改密的种子账号")
                .isEqualTo("at063-independent");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_dict_type WHERE id = 9001", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_dict_data WHERE id = 9001", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() "
                                + "AND table_name = 'system_users' AND index_name = 'uk_system_users_mobile_active'",
                        Integer.class))
                .as("V45 函数式唯一索引在升级后存在")
                .isGreaterThan(0);

        JdbcTemplate snapshot = new JdbcTemplate(snapshotDatabase("at063_upgrade_snapshot"));
        SchemaSnapshotSupport.assertSchemaMatches(snapshot, jdbc);
        SchemaSnapshotSupport.assertQueryMatches(
                snapshot,
                jdbc,
                "初始化部门",
                "SELECT id, name, parent_id, leader_user_id, status, deleted FROM system_dept ORDER BY id");
    }

    private static void assertVersionAtLatest(JdbcTemplate jdbc) throws Exception {
        assertThat(SchemaSnapshotSupport.snapshotVersion()).isEqualTo(MigrationTestSupport.latestVersion());
        assertThat(currentVersion(jdbc)).isEqualTo(String.valueOf(MigrationTestSupport.latestVersion()));
    }

    private static String currentVersion(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1",
                String.class);
    }

    private static DataSource snapshotDatabase(String name) throws Exception {
        DataSource source = newDatabase(name);
        try (var connection = source.getConnection()) {
            connection.createStatement().execute("SET FOREIGN_KEY_CHECKS=0");
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(snapshotFile()));
            connection.createStatement().execute("SET FOREIGN_KEY_CHECKS=1");
        }
        return source;
    }

    private static Path snapshotFile() {
        return SchemaSnapshotSupport.snapshotPath();
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
}
