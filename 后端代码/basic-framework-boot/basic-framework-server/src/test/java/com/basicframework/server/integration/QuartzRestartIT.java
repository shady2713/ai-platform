package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.quartz.core.scheduler.SchedulerManager;
import java.util.Date;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.TriggerKey;
import org.quartz.impl.jdbcjobstore.StdJDBCDelegate;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 使用真实 MySQL 验证 Quartz 调度记录跨进程式重启持久化，且不会在重启时重复注册。 */
@Testcontainers
class QuartzRestartIT {

    private static final String SCHEDULER_NAME = "restartIntegrationScheduler";
    private static final String HANDLER_NAME = "restartProbeJob";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
                            "mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("basic_framework")
            .withUsername("root")
            .withPassword("integration-only");

    @Test
    void persistedJob_survivesSchedulerRestartWithoutDuplicateRegistration() throws Exception {
        DataSource dataSource = dataSource();
        assertThat(Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .load()
                        .migrate()
                        .migrationsExecuted)
                .isEqualTo(MigrationTestSupport.migrationCount());

        Date originalNextFireTime;
        SchedulerFactoryBean firstFactory = schedulerFactory(dataSource);
        try {
            Scheduler firstScheduler = firstFactory.getScheduler();
            new SchedulerManager(firstScheduler).addJob(9001L, HANDLER_NAME, "restart-probe", "0 0 0 1 1 ? 2099", 0, 0);
            originalNextFireTime =
                    firstScheduler.getTrigger(new TriggerKey(HANDLER_NAME)).getNextFireTime();
        } finally {
            firstFactory.destroy();
        }

        SchedulerFactoryBean secondFactory = schedulerFactory(dataSource);
        try {
            Scheduler secondScheduler = secondFactory.getScheduler();
            assertThat(secondScheduler.checkExists(new JobKey(HANDLER_NAME))).isTrue();
            assertThat(secondScheduler.getJobKeys(GroupMatcher.anyJobGroup()))
                    .containsExactly(new JobKey(HANDLER_NAME));

            CronTrigger persistedTrigger = (CronTrigger) secondScheduler.getTrigger(new TriggerKey(HANDLER_NAME));
            assertThat(persistedTrigger.getNextFireTime()).isEqualTo(originalNextFireTime);
            assertThat(persistedTrigger.getMisfireInstruction()).isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);

            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM QRTZ_JOB_DETAILS", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM QRTZ_TRIGGERS", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT REQUESTS_RECOVERY FROM QRTZ_JOB_DETAILS WHERE JOB_NAME = ?",
                            String.class,
                            HANDLER_NAME))
                    .isEqualTo("0");
        } finally {
            secondFactory.destroy();
        }
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static SchedulerFactoryBean schedulerFactory(DataSource dataSource) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("org.quartz.scheduler.instanceId", "AUTO");
        properties.setProperty("org.quartz.jobStore.driverDelegateClass", StdJDBCDelegate.class.getName());
        properties.setProperty("org.quartz.jobStore.isClustered", "true");
        properties.setProperty("org.quartz.jobStore.clusterCheckinInterval", "1000");
        properties.setProperty("org.quartz.threadPool.threadCount", "1");

        SchedulerFactoryBean factory = new SchedulerFactoryBean();
        factory.setSchedulerName(SCHEDULER_NAME);
        factory.setDataSource(dataSource);
        factory.setQuartzProperties(properties);
        factory.setWaitForJobsToCompleteOnShutdown(true);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }
}
