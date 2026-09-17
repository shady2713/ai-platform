package com.basicframework.framework.ai.core.model;

import java.time.Duration;

/**
 * 模型输出流（M03 冻结）：逐事件读取，{@link #close()} 必须释放上游连接。
 *
 * <p>使用约定：
 * <ul>
 *   <li>消费方在 try-with-resources 中使用；提前退出（取消、超限、异常）必须 close，
 *       否则上游订阅与连接会泄漏；</li>
 *   <li>{@link #hasNext()} 阻塞等待下一个事件，等待超过空闲超时抛
 *       {@link ModelException}（原因 {@code TIMEOUT}）并关闭流；上游失败同样抛出
 *       {@link ModelException}，不把厂商报文放进消息；</li>
 *   <li>返回 false 表示流正常结束；终态事件（{@code COMPLETED}）之后一定返回 false；</li>
 *   <li>{@link #close()} 幂等：取消上游订阅、丢弃未被消费的事件、释放连接。</li>
 * </ul>
 */
public interface ModelStream extends AutoCloseable {

    /** 是否还有事件；阻塞直到有事件、结束、超时或上游失败。 */
    boolean hasNext();

    /** 下一个事件；仅在 {@link #hasNext()} 返回 true 后调用。 */
    ModelEvent next();

    /** 关闭流：取消上游订阅并释放连接；重复调用幂等。 */
    @Override
    void close();

    /** 事件之间的最大等待时长（空闲超时）；返回空表示使用实现默认值。 */
    default Duration idleTimeout() {
        return null;
    }
}
