package com.basicframework.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationEnvironmentPostProcessorTest {

    private final ProductionConfigurationEnvironmentPostProcessor processor =
            new ProductionConfigurationEnvironmentPostProcessor();

    @Test
    void nonProduction_skipsProductionOnlyValidation() {
        assertThatCode(() -> processor.postProcessEnvironment(new MockEnvironment(), new SpringApplication()))
                .doesNotThrowAnyException();
    }

    @Test
    void processor_runsAfterRegularEnvironmentPostProcessors() {
        assertThat(processor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void production_rejectsInsecureRefreshCookie() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("basic-framework.security.refresh-cookie.secure", "false");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("basic-framework.security.refresh-cookie.secure 在生产环境必须启用");
    }

    @Test
    void production_rejectsMissingCredentialEncryptionKey() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("basic-framework.security.credential-encryption-key", "");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("basic-framework.security.credential-encryption-key 必须通过生产环境配置注入");
    }

    @Test
    void production_rejectsMissingCorsOrigin() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("basic-framework.web.cors-allowed-origins[0]", "");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("basic-framework.web.cors-allowed-origins[0] 必须配置为生产域名");
    }

    @Test
    void production_rejectsUnsafeSecondaryCorsOrigin() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("basic-framework.web.cors-allowed-origins[1]", "*");
        enableValidCredentialEncryption(environment);

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("basic-framework.web.cors-allowed-origins[1]");
    }

    @Test
    void production_acceptsMultipleExactHttpsCorsOrigins() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("basic-framework.web.cors-allowed-origins[1]", "https://ops.company.invalid");
        enableValidCredentialEncryption(environment);

        assertThatCode(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .doesNotThrowAnyException();
    }

    @Test
    void production_rejectsNonExactCorsOrigins() {
        for (String unsafeOrigin : List.of(
                "https://admin.example.com",
                "https://*.company.invalid",
                "http://admin.company.invalid",
                "https://admin.company.invalid/path",
                "https://admin.company.invalid?unexpected=true",
                "https://localhost",
                " https://admin.company.invalid",
                "https://[invalid")) {
            MockEnvironment environment = productionEnvironment();
            environment.setProperty("basic-framework.web.cors-allowed-origins[1]", unsafeOrigin);
            enableValidCredentialEncryption(environment);

            assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("basic-framework.web.cors-allowed-origins[1]");
        }
    }

    @Test
    void production_rejectsMissingFlywayCredentials() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("spring.flyway.password", "");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.flyway.password 必须通过生产环境配置注入");
    }

    @Test
    void production_rejectsSharedApplicationAndFlywayUser() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("spring.flyway.user", "framework_app");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.flyway.user 必须与应用数据源账号分离");
    }

    @Test
    void production_acceptsValidCredentialMasterKey() {
        MockEnvironment environment = productionEnvironment();
        enableValidCredentialEncryption(environment);

        assertThatCode(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .doesNotThrowAnyException();
    }

    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty(
                "spring.datasource.dynamic.datasource.master.url", "jdbc:mysql://db.internal/basic_framework");
        environment.setProperty("spring.datasource.dynamic.datasource.master.username", "framework_app");
        environment.setProperty("spring.datasource.dynamic.datasource.master.password", "strong-db-password");
        environment.setProperty("spring.flyway.url", "jdbc:mysql://db.internal/basic_framework");
        environment.setProperty("spring.flyway.user", "framework_migrator");
        environment.setProperty("spring.flyway.password", "strong-migration-password");
        environment.setProperty("spring.data.redis.host", "redis.internal");
        environment.setProperty("spring.data.redis.password", "strong-redis-password");
        environment.setProperty("basic-framework.web.cors-allowed-origins[0]", "https://admin.company.invalid");
        environment.setProperty("basic-framework.security.refresh-cookie.secure", "true");
        environment.setProperty(
                "basic-framework.security.credential-encryption-key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        return environment;
    }

    private static void enableValidCredentialEncryption(MockEnvironment environment) {}

    @Test
    void productionRejectsMalformedOrIncorrectLengthEncryptionKeys() {
        for (String key : List.of("not-base64!", "c2hvcnQ=")) {
            MockEnvironment environment = productionEnvironment();
            environment.setProperty("basic-framework.security.credential-encryption-key", key);
            assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Base64");
        }
    }
}
