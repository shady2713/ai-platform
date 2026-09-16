package com.basicframework.framework.quartz.core.handler;

/**
 * 任务处理器。
 *
 * <p>Quartz 重试、人工触发和进程故障边界都可能造成重复调用。实现必须可重入、幂等，或使用业务唯一键/
 * 分布式互斥保护不可重复副作用；不得依赖调度器提供 exactly-once。
 *
 */
public interface JobHandler {

    /**
     * 执行任务
     *
     * @param param 参数；格式与版本由实现拥有
     * @return 结果
     * @throws Exception 异常
     */
    String execute(String param) throws Exception;
}
