package com.basicframework.framework.quartz.config;

import com.alibaba.ttl.TtlRunnable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步任务 Configuration
 */
@AutoConfiguration
@EnableAsync
@EnableConfigurationProperties(BasicFrameworkAsyncProperties.class)
public class BasicFrameworkAsyncAutoConfiguration {

    /**
     * 配置 Spring Boot 受管的 applicationTaskExecutor，供 @Async 与程序化提交共用。
     * 优雅关闭（server.shutdown: graceful）时等待在途任务完成再终止；
     * 队列满载后的拒绝策略由 {@link BasicFrameworkAsyncProperties#getRejectedPolicy()} 决定，默认背压。
     *
     * @param properties 线程池配置项
     * @return Spring Boot 线程池定制器
     */
    @Bean
    public ThreadPoolTaskExecutorCustomizer basicFrameworkThreadPoolTaskExecutorCustomizer(
            BasicFrameworkAsyncProperties properties) {
        return executor -> {
            executor.setCorePoolSize(properties.getCorePoolSize());
            executor.setMaxPoolSize(properties.getMaxPoolSize());
            executor.setQueueCapacity(properties.getQueueCapacity());
            executor.setKeepAliveSeconds(properties.getKeepAliveSeconds());
            executor.setThreadNamePrefix("async-");
            executor.setRejectedExecutionHandler(properties.getRejectedPolicy().toHandler());
            executor.setWaitForTasksToCompleteOnShutdown(true);
            executor.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());
        };
    }

    @Bean
    public static BeanPostProcessor threadPoolTaskExecutorBeanPostProcessor() {
        return new BeanPostProcessor() {

            @Override
            @SuppressWarnings("PatternVariableCanBeUsed")
            public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
                // 处理 ThreadPoolTaskExecutor
                if (bean instanceof ThreadPoolTaskExecutor) {
                    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) bean;
                    executor.setTaskDecorator(TtlRunnable::get);
                    return executor;
                }
                // 处理 SimpleAsyncTaskExecutor
                // 补充处理 SimpleAsyncTaskExecutor
                if (bean instanceof SimpleAsyncTaskExecutor) {
                    SimpleAsyncTaskExecutor executor = (SimpleAsyncTaskExecutor) bean;
                    executor.setTaskDecorator(TtlRunnable::get);
                    return executor;
                }
                return bean;
            }
        };
    }
}
