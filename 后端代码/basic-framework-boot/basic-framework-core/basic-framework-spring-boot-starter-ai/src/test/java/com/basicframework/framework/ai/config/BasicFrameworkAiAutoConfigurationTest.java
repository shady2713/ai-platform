package com.basicframework.framework.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelPort;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * AI 接缝启动期校验契约：默认关闭不注册 Bean；启用后任一装配不一致都必须让上下文启动失败。
 */
class BasicFrameworkAiAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(ValidationAutoConfiguration.class, BasicFrameworkAiAutoConfiguration.class));

    /** 测试桩：ModelPort 已不是函数式接口（capabilities + generate），统一用匿名实现。 */
    private static ModelPort port(Set<ModelCapability> capabilities) {
        return new ModelPort() {
            @Override
            public Set<ModelCapability> capabilities() {
                return capabilities;
            }

            @Override
            public com.basicframework.framework.ai.core.model.ModelResponse generate(
                    com.basicframework.framework.ai.core.model.ModelRequest request) {
                throw new UnsupportedOperationException("装配校验用例不调用模型");
            }
        };
    }

    @Test
    void shouldStayInactiveWhenDisabled() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AiProperties.class);
            assertThat(context).doesNotHaveBean(AiProviderValidator.class);
        });
    }

    @Test
    void shouldRejectEnabledWithoutDeclaredCapabilities() {
        runner.withPropertyValues("basic-framework.ai.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("声明至少一项能力");
        });
    }

    @Test
    void shouldRejectEnabledWithoutProvider() {
        runner.withPropertyValues("basic-framework.ai.enabled=true", "basic-framework.ai.capabilities=TEXT")
                .run(context ->
                        assertThat(context).hasFailed().getFailure().hasMessageContaining("未装配任何 ModelPort 实现"));
    }

    @Test
    void shouldRejectProviderWithoutCapabilities() {
        runner.withPropertyValues("basic-framework.ai.enabled=true", "basic-framework.ai.capabilities=TEXT")
                .withBean("emptyPort", ModelPort.class, () -> port(EnumSet.noneOf(ModelCapability.class)))
                .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining("未声明任何模型能力"));
    }

    @Test
    void shouldRejectProviderMissingDeclaredCapability() {
        runner.withPropertyValues("basic-framework.ai.enabled=true", "basic-framework.ai.capabilities=TEXT,EMBEDDING")
                .withBean("textOnlyPort", ModelPort.class, () -> port(EnumSet.of(ModelCapability.TEXT)))
                .run(context ->
                        assertThat(context).hasFailed().getFailure().hasMessageContaining("缺少已声明的模型能力：EMBEDDING"));
    }

    @Test
    void shouldRejectMultipleProviders() {
        runner.withPropertyValues("basic-framework.ai.enabled=true", "basic-framework.ai.capabilities=TEXT")
                .withBean("firstPort", ModelPort.class, () -> port(EnumSet.of(ModelCapability.TEXT)))
                .withBean(
                        "secondPort",
                        ModelPort.class,
                        () -> port(EnumSet.of(ModelCapability.TEXT, ModelCapability.EMBEDDING)))
                .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining("要求唯一 ModelPort 实现"));
    }

    @Test
    void shouldAcceptProviderCoveringDeclaredCapabilities() {
        Set<ModelCapability> covered = EnumSet.of(ModelCapability.TEXT, ModelCapability.EMBEDDING);
        runner.withPropertyValues("basic-framework.ai.enabled=true", "basic-framework.ai.capabilities=EMBEDDING")
                .withBean("fullPort", ModelPort.class, () -> port(covered))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiProviderValidator.class);
                    assertThat(context.getBean(AiProperties.class).getCapabilities())
                            .containsExactly(ModelCapability.EMBEDDING);
                });
    }
}
