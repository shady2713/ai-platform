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

/** 使用真实 MySQL 验证 V17 会解除用户指向已删除或不存在部门的逻辑孤儿引用。 */
@Testcontainers
class DepartmentReferenceMigrationIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
                            "mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("basic_framework")
            .withUsername("root")
            .withPassword("integration-only");

    @Test
    void v17_clearsOrphanUserDepartmentReference() {
        DataSource dataSource =
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        assertThat(flyway(dataSource, MigrationVersion.fromVersion("16")).migrate().migrationsExecuted)
                .isEqualTo(15);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        assertThat(jdbcTemplate.update("UPDATE system_users SET dept_id = 999999 WHERE id = 1"))
                .isEqualTo(1);
        assertThat(flyway(dataSource, MigrationVersion.fromVersion("17")).migrate().migrationsExecuted)
                .isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("SELECT dept_id FROM system_users WHERE id = 1", Long.class))
                .isNull();
    }

    private static Flyway flyway(DataSource dataSource) {
        return flywayConfiguration(dataSource).load();
    }

    private static Flyway flyway(DataSource dataSource, MigrationVersion target) {
        return flywayConfiguration(dataSource).target(target).load();
    }

    private static FluentConfiguration flywayConfiguration(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }
}
