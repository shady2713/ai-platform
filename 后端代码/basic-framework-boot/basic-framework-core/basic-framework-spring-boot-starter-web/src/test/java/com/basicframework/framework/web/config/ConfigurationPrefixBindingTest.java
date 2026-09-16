package com.basicframework.framework.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ConfigurationPrefixBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues("basic-framework.web.admin-api.prefix=/admin-api");

    @Test
    void bindsKebabCaseConfigurationPrefixes() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WebProperties.class).getAdminApi().getPrefix())
                    .isEqualTo("/admin-api");
        });
    }

    @Test
    void requestBodyCacheLimitRejectsUnsafeConfiguration() {
        contextRunner
                .withPropertyValues("basic-framework.web.request-body-cache-max-bytes=10485761")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsMalformedNestedApiPrefix() {
        contextRunner
                .withPropertyValues("basic-framework.web.admin-api.prefix=/admin-api/")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebProperties.class)
    static class TestConfiguration {}
}
