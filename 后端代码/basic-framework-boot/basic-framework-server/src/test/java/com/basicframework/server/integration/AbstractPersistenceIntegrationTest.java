package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.server.BasicFrameworkServerApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 真实 MySQL/Redis 集成测试共享装配。
 *
 * <p>仅共享容器、Spring 测试上下文、动态连接属性与通用异常断言；场景 Bean 和断言留在各自测试类。
 */
@ActiveProfiles("test")
@SpringBootTest(
        classes = BasicFrameworkServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.task.scheduling.enabled=false",
            "basic-framework.security.credential-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "logging.level.com.basicframework.module.system.dal.mysql.session=INFO",
            "logging.level.com.basicframework.module.system.dal.mysql.user.AdminUserMapper=INFO"
        })
@Transactional
abstract class AbstractPersistenceIntegrationTest {

    protected static final String TEST_MOBILE = "13900000001";
    private static final String REDIS_PASSWORD = "integration-only";

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
                            "mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("basic_framework")
            .withUsername("root")
            .withPassword("integration-only");

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
                    "redis:7.4.11@sha256:71da9275c5f3fcb97d0fa0c8c5b36cc995327265420f17a04bfd544f458059f7"))
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
            .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.dynamic.datasource.master.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.dynamic.datasource.master.username", MYSQL::getUsername);
        registry.add("spring.datasource.dynamic.datasource.master.password", MYSQL::getPassword);
        registry.add("spring.datasource.dynamic.datasource.master.name", MYSQL::getDatabaseName);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    /** MySQL 容器的映射端口（连接器探测等需要以真实地址连接时使用）。 */
    protected static int mysqlMappedPort() {
        return MYSQL.getMappedPort(3306);
    }

    /** MySQL 容器的 root 密码（连接器探测等需要以真实凭据连接时使用）。 */
    protected static String mysqlRootPassword() {
        return MYSQL.getPassword();
    }

    protected void assertServiceException(Integer expectedCode, ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(expectedCode));
    }

    @FunctionalInterface
    protected interface ThrowingOperation {

        void run();
    }
}
