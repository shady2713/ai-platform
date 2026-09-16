package com.basicframework.framework.swagger.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SwaggerPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void missingRequiredMetadata_failsBinding() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("标题不能为空");
        });
    }

    @Test
    void requiredMetadataPresent_bindsSuccessfully() {
        contextRunner
                .withPropertyValues(
                        "basic-framework.swagger.title=API",
                        "basic-framework.swagger.description=API documentation",
                        "basic-framework.swagger.version=1.0.0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SwaggerProperties properties = context.getBean(SwaggerProperties.class);
                    assertThat(properties.getTitle()).isEqualTo("API");
                    assertThat(properties.getDescription()).isEqualTo("API documentation");
                    assertThat(properties.getVersion()).isEqualTo("1.0.0");
                });
    }

    @EnableConfigurationProperties(SwaggerProperties.class)
    static class PropertiesConfiguration {}
}
