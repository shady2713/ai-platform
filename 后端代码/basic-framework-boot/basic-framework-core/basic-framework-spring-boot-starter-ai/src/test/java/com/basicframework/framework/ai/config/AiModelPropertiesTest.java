package com.basicframework.framework.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * M03 模型护栏配置：默认值保守、可按前缀覆盖、越界值在启动期失败（fail-closed）。
 */
class AiModelPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertiesConfiguration.class));

    @Test
    void bindsConservativeDefaults() {
        contextRunner.run(context -> {
            AiModelProperties properties = context.getBean(AiModelProperties.class);
            assertThat(properties.getMaxAttempts()).isEqualTo(3);
            assertThat(properties.getRetryBackoff()).isEqualTo(Duration.ofMillis(200));
            assertThat(properties.getMaxOutputChars()).isEqualTo(262_144);
            assertThat(properties.getStreamIdleTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getStreamQueueCapacity()).isEqualTo(64);
            assertThat(properties.getMaxRepairSteps()).isEqualTo(3);
        });
    }

    @Test
    void bindsOverrides() {
        contextRunner
                .withPropertyValues(
                        "basic-framework.ai.model.max-attempts=1",
                        "basic-framework.ai.model.retry-backoff=10ms",
                        "basic-framework.ai.model.max-output-chars=4096",
                        "basic-framework.ai.model.stream-idle-timeout=5s",
                        "basic-framework.ai.model.stream-queue-capacity=8",
                        "basic-framework.ai.model.max-repair-steps=1")
                .run(context -> {
                    AiModelProperties properties = context.getBean(AiModelProperties.class);
                    assertThat(properties.getMaxAttempts()).isEqualTo(1);
                    assertThat(properties.getRetryBackoff()).isEqualTo(Duration.ofMillis(10));
                    assertThat(properties.getMaxOutputChars()).isEqualTo(4096);
                    assertThat(properties.getStreamIdleTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.getStreamQueueCapacity()).isEqualTo(8);
                    assertThat(properties.getMaxRepairSteps()).isEqualTo(1);
                });
    }

    @Test
    void rejectsOutOfRangeGuardrailsAtStartup() {
        contextRunner
                .withPropertyValues("basic-framework.ai.model.max-attempts=9")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("basic-framework.ai.model.max-output-chars=16")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("basic-framework.ai.model.max-repair-steps=9")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("basic-framework.ai.model.stream-queue-capacity=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiModelProperties.class)
    static class PropertiesConfiguration {}
}
