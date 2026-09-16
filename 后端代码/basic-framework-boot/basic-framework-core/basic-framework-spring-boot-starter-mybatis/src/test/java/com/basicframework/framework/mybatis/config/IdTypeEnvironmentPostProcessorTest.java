package com.basicframework.framework.mybatis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.IdType;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class IdTypeEnvironmentPostProcessorTest {

    private static final String ID_TYPE_KEY = "mybatis-plus.global-config.db-config.id-type";
    private static final String PRIMARY_KEY = "spring.datasource.dynamic.primary";
    private static final String PRIMARY_URL_KEY = "spring.datasource.dynamic.datasource.primary.url";
    private static final String QUARTZ_DRIVER_KEY = "spring.quartz.properties.org.quartz.jobStore.driverDelegateClass";

    private final IdTypeEnvironmentPostProcessor processor = new IdTypeEnvironmentPostProcessor();
    private final String originalQuartzDriver = System.getProperty(QUARTZ_DRIVER_KEY);

    @BeforeEach
    void clearSystemProperty() {
        System.clearProperty(QUARTZ_DRIVER_KEY);
    }

    @AfterEach
    void restoreSystemProperty() {
        if (originalQuartzDriver == null) {
            System.clearProperty(QUARTZ_DRIVER_KEY);
        } else {
            System.setProperty(QUARTZ_DRIVER_KEY, originalQuartzDriver);
        }
    }

    @Test
    void postProcessEnvironment_selectsAutoForMysqlAndInputForPostgres() {
        MockEnvironment mysql = databaseEnvironment("jdbc:mysql://localhost:3306/framework");
        processor.postProcessEnvironment(mysql, new SpringApplication(Object.class));
        assertThat(processor.getIdType(mysql)).isEqualTo(IdType.AUTO);

        MockEnvironment postgres = databaseEnvironment("jdbc:postgresql://localhost:5432/framework");
        processor.postProcessEnvironment(postgres, new SpringApplication(Object.class));
        assertThat(processor.getIdType(postgres)).isEqualTo(IdType.INPUT);
        assertThat(System.getProperty(QUARTZ_DRIVER_KEY)).isEqualTo("org.quartz.impl.jdbcjobstore.PostgreSQLDelegate");
    }

    @Test
    void postProcessEnvironment_preservesExplicitIdTypeAndIgnoresMissingDatasource() {
        MockEnvironment explicit = databaseEnvironment("jdbc:mysql://localhost:3306/framework")
                .withProperty(ID_TYPE_KEY, IdType.ASSIGN_ID.name());
        processor.postProcessEnvironment(explicit, new SpringApplication(Object.class));
        assertThat(processor.getIdType(explicit)).isEqualTo(IdType.ASSIGN_ID);

        MockEnvironment missingDatasource = new MockEnvironment();
        processor.postProcessEnvironment(missingDatasource, new SpringApplication(Object.class));
        assertThat(missingDatasource.getProperty(ID_TYPE_KEY)).isNull();

        MockEnvironment missingUrl = new MockEnvironment().withProperty(PRIMARY_KEY, "primary");
        assertThat(IdTypeEnvironmentPostProcessor.getDbType(missingUrl)).isNull();
    }

    @Test
    void getIdType_rejectsUnknownConfiguration() {
        MockEnvironment environment = new MockEnvironment().withProperty(ID_TYPE_KEY, "UNKNOWN");

        assertThatThrownBy(() -> processor.getIdType(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("无法解析 MyBatis Plus id-type 配置值: UNKNOWN")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("quartzDrivers")
    void setJobStoreDriverIfPresent_mapsSupportedDatabases(DbType dbType, String expectedDriver) {
        MockEnvironment environment = new MockEnvironment();

        processor.setJobStoreDriverIfPresent(environment, dbType);

        assertThat(System.getProperty(QUARTZ_DRIVER_KEY)).isEqualTo(expectedDriver);
    }

    @Test
    void setJobStoreDriverIfPresent_preservesExplicitDriverAndIgnoresUnsupportedDatabase() {
        MockEnvironment explicit = new MockEnvironment().withProperty(QUARTZ_DRIVER_KEY, "custom.Driver");
        processor.setJobStoreDriverIfPresent(explicit, DbType.ORACLE);
        assertThat(System.getProperty(QUARTZ_DRIVER_KEY)).isNull();

        processor.setJobStoreDriverIfPresent(new MockEnvironment(), DbType.MYSQL);
        assertThat(System.getProperty(QUARTZ_DRIVER_KEY)).isNull();
    }

    private static MockEnvironment databaseEnvironment(String url) {
        return new MockEnvironment().withProperty(PRIMARY_KEY, "primary").withProperty(PRIMARY_URL_KEY, url);
    }

    private static Stream<Arguments> quartzDrivers() {
        return Stream.of(
                Arguments.of(DbType.POSTGRE_SQL, "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate"),
                Arguments.of(DbType.ORACLE, "org.quartz.impl.jdbcjobstore.oracle.OracleDelegate"),
                Arguments.of(DbType.ORACLE_12C, "org.quartz.impl.jdbcjobstore.oracle.OracleDelegate"),
                Arguments.of(DbType.SQL_SERVER, "org.quartz.impl.jdbcjobstore.MSSQLDelegate"),
                Arguments.of(DbType.SQL_SERVER2005, "org.quartz.impl.jdbcjobstore.MSSQLDelegate"),
                Arguments.of(DbType.DM, "org.quartz.impl.jdbcjobstore.StdJDBCDelegate"),
                Arguments.of(DbType.KINGBASE_ES, "org.quartz.impl.jdbcjobstore.StdJDBCDelegate"));
    }
}
