package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 使用真实 MySQL 验证 V20 清理角色数据范围孤儿并约束 JSON 数组格式。 */
@Testcontainers
class RoleDataScopeDepartmentMigrationIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
                            "mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("basic_framework")
            .withUsername("root")
            .withPassword("integration-only");

    @Test
    void v20_repairsRoleDataScopeDepartmentsAndRejectsMalformedJson() {
        DataSource dataSource =
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        assertThat(flyway(dataSource, MigrationVersion.fromVersion("19")).migrate().migrationsExecuted)
                .isEqualTo(18);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        insertDepartment(jdbcTemplate, 9_077_001L, false);
        insertDepartment(jdbcTemplate, 9_077_002L, true);
        insertRole(jdbcTemplate, 9_077_001L, 2, "[9077001,9077002,9077003,9077001]");
        insertRole(jdbcTemplate, 9_077_002L, 2, "not-json");
        insertRole(jdbcTemplate, 9_077_003L, 2, "9077001");
        insertRole(jdbcTemplate, 9_077_004L, 1, "[9077001]");

        assertThat(flyway(dataSource, MigrationVersion.fromVersion("20")).migrate().migrationsExecuted)
                .isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT data_scope_dept_ids FROM system_role WHERE id = ?", String.class, 9_077_001L))
                .isEqualTo("[9077001]");
        assertThat(jdbcTemplate.queryForList(
                        "SELECT data_scope_dept_ids FROM system_role WHERE id IN (?, ?, ?) ORDER BY id",
                        String.class,
                        9_077_002L,
                        9_077_003L,
                        9_077_004L))
                .containsExactly("[]", "[]", "[]");
        assertThat(jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM information_schema.table_constraints
                        WHERE constraint_schema = DATABASE()
                          AND table_name = 'system_role'
                          AND constraint_name = 'chk_role_data_scope_dept_ids_json'
                          AND constraint_type = 'CHECK'
                        """,
                        Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE system_role SET data_scope_dept_ids = '{}' WHERE id = ?", 9_077_001L))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_role_data_scope_dept_ids_json");
    }

    private static void insertDepartment(JdbcTemplate jdbcTemplate, long id, boolean deleted) {
        jdbcTemplate.update(
                "INSERT INTO system_dept (id, name, parent_id, sort, status, deleted) VALUES (?, ?, 0, 1, 0, ?)",
                id,
                "migration-" + id,
                deleted);
    }

    private static void insertRole(JdbcTemplate jdbcTemplate, long id, int dataScope, String deptIds) {
        jdbcTemplate.update(
                """
                INSERT INTO system_role
                    (id, name, code, sort, data_scope, data_scope_dept_ids, status, type)
                VALUES (?, ?, ?, 1, ?, ?, 0, 2)
                """,
                id,
                "migration-" + id,
                "MIGRATION_" + id,
                dataScope,
                deptIds);
    }

    private static Flyway flyway(DataSource dataSource, MigrationVersion target) {
        return flywayConfiguration(dataSource).target(target).load();
    }

    private static FluentConfiguration flywayConfiguration(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }
}
