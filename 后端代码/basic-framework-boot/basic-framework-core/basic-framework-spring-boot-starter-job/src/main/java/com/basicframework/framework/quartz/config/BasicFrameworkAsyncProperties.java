package com.basicframework.framework.quartz.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 异步任务线程池配置项
 *
 */
@ConfigurationProperties("basic-framework.async")
@Validated
@Data
public class BasicFrameworkAsyncProperties {

    /**
     * {@link #corePoolSize} 默认值
     */
    private static final Integer CORE_POOL_SIZE_DEFAULT = 8;

    /**
     * {@link #maxPoolSize} 默认值
     */
    private static final Integer MAX_POOL_SIZE_DEFAULT = 32;

    /**
     * {@link #queueCapacity} 默认值
     */
    private static final Integer QUEUE_CAPACITY_DEFAULT = 100;

    /**
     * {@link #keepAliveSeconds} 默认值
     */
    private static final Integer KEEP_ALIVE_SECONDS_DEFAULT = 60;

    /**
     * {@link #awaitTerminationSeconds} 默认值，与 spring.lifecycle.timeout-per-shutdown-phase 对齐
     */
    private static final Integer AWAIT_TERMINATION_SECONDS_DEFAULT = 30;

    /**
     * 核心线程数
     */
    @NotNull
    @Min(value = 1, message = "basic-framework.async.core-pool-size 必须大于 0")
    private Integer corePoolSize = CORE_POOL_SIZE_DEFAULT;

    /**
     * 最大线程数
     */
    @NotNull
    @Min(value = 1, message = "basic-framework.async.max-pool-size 必须大于 0")
    private Integer maxPoolSize = MAX_POOL_SIZE_DEFAULT;

    /**
     * 队列容量
     */
    @NotNull
    @Min(value = 0, message = "basic-framework.async.queue-capacity 不得小于 0")
    private Integer queueCapacity = QUEUE_CAPACITY_DEFAULT;

    /**
     * 空闲线程存活秒数
     */
    @NotNull
    @Min(value = 0, message = "basic-framework.async.keep-alive-seconds 不得小于 0")
    private Integer keepAliveSeconds = KEEP_ALIVE_SECONDS_DEFAULT;

    /**
     * 优雅关闭时等待任务完成的最长秒数
     */
    @NotNull
    @Min(value = 0, message = "basic-framework.async.await-termination-seconds 不得小于 0")
    private Integer awaitTerminationSeconds = AWAIT_TERMINATION_SECONDS_DEFAULT;

    /**
     * 队列与线程均满时的拒绝策略
     *
     * 默认 {@link RejectedPolicy#CALLER_RUNS}：回退到提交线程同步执行，用背压换取任务不丢失；
     * 此前的 AbortPolicy 会让发送类接口在高峰期直接 500。如需快速失败可显式配置为 ABORT。
     */
    @NotNull
    private RejectedPolicy rejectedPolicy = RejectedPolicy.CALLER_RUNS;

    /** 核心线程数不得超过最大线程数，避免执行器初始化阶段才以缺少配置上下文的方式失败。 */
    @AssertTrue(message = "basic-framework.async.core-pool-size 不得大于 max-pool-size")
    public boolean isPoolSizeRangeValid() {
        return corePoolSize != null && maxPoolSize != null && corePoolSize <= maxPoolSize;
    }

    /**
     * 拒绝策略到 {@link RejectedExecutionHandler} 的映射；非法取值在配置绑定阶段直接启动失败。
     */
    public enum RejectedPolicy {

        /** 提交线程同步执行，形成背压 */
        CALLER_RUNS {
            @Override
            RejectedExecutionHandler toHandler() {
                return new ThreadPoolExecutor.CallerRunsPolicy();
            }
        },
        /** 抛出 RejectedExecutionException，快速失败 */
        ABORT {
            @Override
            RejectedExecutionHandler toHandler() {
                return new ThreadPoolExecutor.AbortPolicy();
            }
        },
        /** 静默丢弃新任务 */
        DISCARD {
            @Override
            RejectedExecutionHandler toHandler() {
                return new ThreadPoolExecutor.DiscardPolicy();
            }
        },
        /** 丢弃队列中最旧的任务后重试入队 */
        DISCARD_OLDEST {
            @Override
            RejectedExecutionHandler toHandler() {
                return new ThreadPoolExecutor.DiscardOldestPolicy();
            }
        };

        abstract RejectedExecutionHandler toHandler();
    }
}
