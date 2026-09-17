package com.basicframework.framework.ai.provider.springai;

import com.basicframework.framework.ai.config.AiModelProperties;
import com.basicframework.framework.ai.core.model.ModelEvent;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelStream;
import com.basicframework.framework.ai.core.model.ModelToolCall;
import com.basicframework.framework.ai.core.model.ModelUsage;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Spring AI 文本流适配（M03）：把厂商增量响应转成平台自有事件。
 *
 * <p>实现要点：
 * <ul>
 *   <li>上游订阅在构造时建立，事件进入**有界队列**：消费慢时形成背压，不无限占用内存；</li>
 *   <li>{@link #hasNext()} 等待超过空闲超时即判定 TIMEOUT 并关闭流（释放连接）；</li>
 *   <li>文本增量即时下发；工具调用参数在厂商侧是分片传输的，适配层按调用标识聚合，
 *       在流结束时一次性给出完整调用（只作为数据，永不自动执行）；</li>
 *   <li>输出字符数超过上限即中断订阅并给出 {@code OUTPUT_LIMIT_EXCEEDED}；</li>
 *   <li>{@link #close()} 取消订阅、丢弃未消费事件；重复调用幂等。</li>
 * </ul>
 */
final class SpringAiModelStream implements ModelStream {

    private static final Object COMPLETED = new Object();

    private final BlockingQueue<Object> queue;

    private final Disposable subscription;

    private final Duration idleTimeout;

    private final int maxOutputChars;

    private final String modelId;

    private final AtomicBoolean closed = new AtomicBoolean();

    private final AtomicInteger emittedChars = new AtomicInteger();

    private final AtomicReference<ModelException> failure = new AtomicReference<>();

    /** 工具调用按调用标识聚合：厂商以分片增量传输参数。 */
    private final Map<String, ToolCallBuffer> toolCalls = new LinkedHashMap<>();

    /** 结束原因与用量只在流结束时读取，跨线程可见性用 volatile 保证。 */
    private volatile ModelUsage completionUsage;

    private volatile String completionFinishReason;

    private ModelEvent pending;

    private boolean finished;

    SpringAiModelStream(
            Flux<ChatResponse> responses, AiModelProperties properties, String modelId, Duration requestTimeout) {
        this.modelId = modelId;
        this.maxOutputChars = properties.getMaxOutputChars();
        this.idleTimeout = resolveIdleTimeout(properties.getStreamIdleTimeout(), requestTimeout);
        this.queue = new ArrayBlockingQueue<>(properties.getStreamQueueCapacity());
        this.subscription = responses
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(this::onNext, this::onError, this::onComplete);
    }

    private static Duration resolveIdleTimeout(Duration configured, Duration requestTimeout) {
        if (requestTimeout == null) {
            return configured;
        }
        return requestTimeout.compareTo(configured) < 0 ? requestTimeout : configured;
    }

    @Override
    public boolean hasNext() {
        if (pending != null) {
            return true;
        }
        if (finished) {
            return false;
        }
        Object item = poll();
        if (item == COMPLETED) {
            ModelException error = failure.get();
            if (error != null) {
                close();
                throw error;
            }
            finished = true;
            pending = ModelEvent.completed(usageOrUnknown(), completionFinishReason);
            return true;
        }
        if (item == null) {
            throw idleTimeoutException();
        }
        pending = (ModelEvent) item;
        return true;
    }

    @Override
    public ModelEvent next() {
        ModelEvent event = pending;
        if (event == null) {
            throw new IllegalStateException("必须先在 hasNext() 返回 true 后调用 next()");
        }
        pending = null;
        return event;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            subscription.dispose();
        }
        queue.clear();
        pending = null;
        finished = true;
    }

    @Override
    public Duration idleTimeout() {
        return idleTimeout;
    }

    private Object poll() {
        try {
            return queue.poll(idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            ModelException interrupted = new ModelException(ModelException.Reason.TIMEOUT, "等待模型流式输出被中断", exception);
            close();
            throw interrupted;
        }
    }

    private ModelException idleTimeoutException() {
        ModelException exception =
                new ModelException(ModelException.Reason.TIMEOUT, "模型流式输出空闲超时（" + idleTimeout.toSeconds() + "s）");
        close();
        return exception;
    }

    private void onNext(ChatResponse response) {
        if (closed.get()) {
            return;
        }
        try {
            ModelUsage measured = SpringAiModelClient.toUsage(response);
            if (measured.isKnown()) {
                completionUsage = measured;
            }
            Generation generation = response == null ? null : response.getResult();
            AssistantMessage message = generation == null ? null : generation.getOutput();
            if (generation != null && generation.getMetadata() != null) {
                completionFinishReason = generation.getMetadata().getFinishReason();
            }
            if (message == null) {
                return;
            }
            collectToolCalls(message);
            String text = message.getText();
            if (text != null && !text.isEmpty()) {
                offer(ModelEvent.delta(text));
            }
        } catch (RuntimeException exception) {
            fail(SpringAiModelClient.mapFailure(exception));
        }
    }

    private void collectToolCalls(AssistantMessage message) {
        List<AssistantMessage.ToolCall> calls = message.getToolCalls();
        if (calls == null || calls.isEmpty()) {
            return;
        }
        for (AssistantMessage.ToolCall call : calls) {
            String key = call.id() == null ? String.valueOf(call.name()) : call.id();
            ToolCallBuffer buffer = toolCalls.computeIfAbsent(key, ignored -> new ToolCallBuffer(call.name()));
            buffer.append(call.arguments());
        }
    }

    private void onError(Throwable throwable) {
        if (closed.get()) {
            return;
        }
        fail(SpringAiModelClient.mapFailure(asException(throwable)));
    }

    private void onComplete() {
        if (closed.get()) {
            return;
        }
        emitAggregatedToolCalls();
        offer(COMPLETED);
    }

    private void emitAggregatedToolCalls() {
        for (Map.Entry<String, ToolCallBuffer> entry : toolCalls.entrySet()) {
            offer(ModelEvent.toolCall(entry.getValue().toToolCall(entry.getKey())));
        }
        toolCalls.clear();
    }

    private void offer(ModelEvent event) {
        if (closed.get() || failure.get() != null) {
            return;
        }
        if (event.type() == ModelEvent.Type.DELTA
                && emittedChars.addAndGet(event.text().length()) > maxOutputChars) {
            fail(new ModelException(
                    ModelException.Reason.OUTPUT_LIMIT_EXCEEDED, "模型输出超过大小上限（" + maxOutputChars + " 字符）"));
            return;
        }
        put(event);
    }

    private void offer(Object marker) {
        put(marker);
    }

    private void put(Object item) {
        try {
            queue.put(item);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            close();
        }
    }

    private void fail(ModelException exception) {
        if (failure.compareAndSet(null, exception)) {
            subscription.dispose();
            queue.clear();
            put(COMPLETED);
        }
    }

    private ModelUsage usageOrUnknown() {
        return completionUsage == null ? ModelUsage.UNKNOWN : completionUsage;
    }

    private static Exception asException(Throwable throwable) {
        if (throwable instanceof Exception exception) {
            return exception;
        }
        return new IllegalStateException(throwable.getMessage(), throwable);
    }

    /** 分片参数的聚合缓冲。 */
    private static final class ToolCallBuffer {

        private final String name;

        private final StringBuilder arguments = new StringBuilder();

        private ToolCallBuffer(String name) {
            this.name = name;
        }

        private void append(String fragment) {
            if (fragment != null) {
                arguments.append(fragment);
            }
        }

        private ModelToolCall toToolCall(String id) {
            return new ModelToolCall(id, name, arguments.isEmpty() ? "{}" : arguments.toString());
        }
    }
}
