package com.basicframework.framework.quartz.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class BasicFrameworkAsyncAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TaskExecutionAutoConfiguration.class,
                    TaskSchedulingAutoConfiguration.class,
                    BasicFrameworkAsyncAutoConfiguration.class));

    @Test
    void customizerConfiguresSpringBootExecutorWithoutBeanNameConflict() {
        contextRunner
                .withUserConfiguration(CompetingTaskExecutorConfiguration.class)
                .withPropertyValues(
                        "basic-framework.async.core-pool-size=3",
                        "basic-framework.async.max-pool-size=7",
                        "basic-framework.async.queue-capacity=19",
                        "basic-framework.async.keep-alive-seconds=45")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ThreadPoolTaskExecutor.class);
                    ThreadPoolTaskExecutor executor =
                            context.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);
                    assertThat(executor.getCorePoolSize()).isEqualTo(3);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(7);
                    assertThat(executor.getQueueCapacity()).isEqualTo(19);
                    assertThat(executor.getKeepAliveSeconds()).isEqualTo(45);
                    assertThat(executor.getThreadNamePrefix()).isEqualTo("async-");
                    // 默认拒绝策略为 CallerRunsPolicy 背压，避免队列满载时发送类接口直接 500
                    assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                            .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
                    assertThat(context.getBean(AsyncConfigurer.class).getAsyncExecutor())
                            .isSameAs(executor);
                });
    }

    @Test
    void customizerAppliesConfiguredRejectedPolicy() {
        contextRunner
                .withPropertyValues("basic-framework.async.rejected-policy=ABORT")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ThreadPoolTaskExecutor executor =
                            context.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);
                    assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                            .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
                });
    }

    @Test
    void invalidThreadPoolLimits_failDuringConfigurationBinding() {
        contextRunner
                .withPropertyValues("basic-framework.async.core-pool-size=8", "basic-framework.async.max-pool-size=7")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("不得大于 max-pool-size");
                });
    }

    @Test
    void negativeThreadPoolValues_failDuringConfigurationBinding() {
        contextRunner
                .withPropertyValues("basic-framework.async.queue-capacity=-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("不得小于 0");
                });
    }

    @Test
    void rejectedPolicyMapping_coversAllValues() {
        assertThat(BasicFrameworkAsyncProperties.RejectedPolicy.CALLER_RUNS.toHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        assertThat(BasicFrameworkAsyncProperties.RejectedPolicy.ABORT.toHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        assertThat(BasicFrameworkAsyncProperties.RejectedPolicy.DISCARD.toHandler())
                .isInstanceOf(ThreadPoolExecutor.DiscardPolicy.class);
        assertThat(BasicFrameworkAsyncProperties.RejectedPolicy.DISCARD_OLDEST.toHandler())
                .isInstanceOf(ThreadPoolExecutor.DiscardOldestPolicy.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class CompetingTaskExecutorConfiguration {}
}
