package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 使用隔离的真实 MySQL schema 验证废弃能力及对应字典数据的迁移。 */
@Testcontainers
class RemovedCapabilityMigrationIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
                            "mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("basic_framework")
            .withUsername("root")
            .withPassword("integration-only");

    @Test
    void v32_removesGeneratorTablesMenusPermissionsAndDictionaries() {
        DataSource dataSource = createSchema("removed_codegen");
        assertThat(flyway(dataSource, MigrationVersion.fromVersion("31")).migrate().migrationsExecuted)
                .isEqualTo(30);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        assertThat(tableCount(jdbcTemplate, "infra_codegen_table")).isEqualTo(1);
        assertThat(tableCount(jdbcTemplate, "infra_codegen_column")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_role_menu WHERE role_id = ? AND menu_id = ?",
                        Integer.class,
                        1L,
                        115L))
                .isPositive();

        assertThat(flyway(dataSource, MigrationVersion.fromVersion("32")).migrate().migrationsExecuted)
                .isEqualTo(1);

        assertThat(tableCount(jdbcTemplate, "infra_codegen_table")).isZero();
        assertThat(tableCount(jdbcTemplate, "infra_codegen_column")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_menu WHERE id = 115 OR parent_id = 115 "
                                + "OR permission LIKE 'infra:codegen:%'",
                        Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_role_menu WHERE role_id = ? AND menu_id = ?",
                        Integer.class,
                        1L,
                        115L))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_dict_data WHERE dict_type LIKE 'infra_codegen_%'", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_dict_type WHERE type LIKE 'infra_codegen_%'", Integer.class))
                .isZero();
    }

    @Test
    void v43_prunesRemovedChannelAndLoginDictRows() {
        DataSource dataSource = createSchema("removed_channels");
        assertThat(flyway(dataSource, MigrationVersion.fromVersion("42")).migrate().migrationsExecuted)
                .isEqualTo(41);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        // V42 之前：腾讯云渠道、社交登录与短信登录字典行仍然存在
        assertThat(dictCount(jdbcTemplate, "system_sms_channel_code", "TENCENT"))
                .isEqualTo(1);
        assertThat(dictCount(jdbcTemplate, "system_login_type", "101")).isEqualTo(1);
        assertThat(dictCount(jdbcTemplate, "system_login_type", "103")).isEqualTo(1);

        assertThat(flyway(dataSource, MigrationVersion.fromVersion("43")).migrate().migrationsExecuted)
                .isEqualTo(1);

        // V43 终态：渠道仅剩阿里云；登录类型仅剩账号登录与两种登出
        assertThat(dictCount(jdbcTemplate, "system_sms_channel_code", "TENCENT"))
                .isZero();
        assertThat(dictCount(jdbcTemplate, "system_sms_channel_code", "ALIYUN")).isEqualTo(1);
        assertThat(dictCount(jdbcTemplate, "system_login_type", "101")).isZero();
        assertThat(dictCount(jdbcTemplate, "system_login_type", "103")).isZero();
        assertThat(dictCount(jdbcTemplate, "system_login_type", "100")).isEqualTo(1);
        assertThat(dictCount(jdbcTemplate, "system_login_type", "200")).isEqualTo(1);
        assertThat(dictCount(jdbcTemplate, "system_login_type", "202")).isEqualTo(1);
    }

    @Test
    void v46_prunesUnusedSeedsAndKeepsBootstrapAuthorization() {
        DataSource source = createSchema("minimal_seeds");
        flyway(source, MigrationVersion.fromVersion("45")).migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);

        flywayConfiguration(source).load().migrate();

        assertThat(jdbc.queryForList("SELECT id FROM system_dept ORDER BY id", Long.class))
                .containsExactly(100L);
        assertThat(jdbc.queryForObject("SELECT dept_id FROM system_users WHERE id = 1", Long.class))
                .isEqualTo(100L);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM system_users WHERE id = 1 AND status = 1 AND must_change_password = b'1' "
                                + "AND password = '!bootstrap-required'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM system_dict_data WHERE dict_type = 'infra_operate_type'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM system_dict_type WHERE type = 'infra_operate_type'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM system_user_role WHERE user_id = 1 AND role_id = 1", Integer.class))
                .isEqualTo(1);
        // 7 条框架内置任务 + AI 任务恢复 Job（V61）+ AI 保留期清理 Job（V64），handler 名与 bean 名一致
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM infra_job", Integer.class))
                .isEqualTo(9);
    }

    @Test
    void v46_preservesCustomizedDepartmentsAndDictionaryEntries() {
        DataSource source = createSchema("customized_seeds");
        flyway(source, MigrationVersion.fromVersion("45")).migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update("UPDATE system_dept SET name = '实际业务部门' WHERE id = 103");
        jdbc.update("UPDATE system_dict_data SET label = '自定义查询' WHERE id = 17");

        flywayConfiguration(source).load().migrate();

        assertThat(jdbc.queryForObject("SELECT dept_id FROM system_users WHERE id = 1", Long.class))
                .isEqualTo(103L);
        assertThat(jdbc.queryForObject("SELECT name FROM system_dept WHERE id = 103", String.class))
                .isEqualTo("实际业务部门");
        assertThat(jdbc.queryForObject("SELECT label FROM system_dict_data WHERE id = 17", String.class))
                .isEqualTo("自定义查询");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM system_dict_type WHERE type = 'infra_operate_type'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void v46_preservesDepartmentsOnceBootstrapAccountIsActivated() {
        DataSource source = createSchema("activated_seeds");
        flyway(source, MigrationVersion.fromVersion("45")).migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update("UPDATE system_users SET password = 'deployment-specific', status = 0 WHERE id = 1");

        flywayConfiguration(source).load().migrate();

        assertThat(jdbc.queryForList("SELECT id FROM system_dept ORDER BY id", Long.class))
                .containsExactly(100L, 101L, 103L);
        assertThat(jdbc.queryForObject("SELECT dept_id FROM system_users WHERE id = 1", Long.class))
                .isEqualTo(103L);
    }

    private static DataSource createSchema(String schema) {
        var administration = new JdbcTemplate(
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        administration.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        return new DriverManagerDataSource(
                MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + schema),
                MYSQL.getUsername(),
                MYSQL.getPassword());
    }

    private static int dictCount(JdbcTemplate jdbcTemplate, String dictType, String value) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM system_dict_data WHERE dict_type = ? AND value = ?",
                Integer.class,
                dictType,
                value);
    }

    private static int tableCount(JdbcTemplate jdbcTemplate, String tableName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName);
    }

    private static Flyway flyway(DataSource dataSource, MigrationVersion target) {
        return flywayConfiguration(dataSource).target(target).load();
    }

    private static FluentConfiguration flywayConfiguration(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }
}
