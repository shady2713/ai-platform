package com.basicframework.framework.ai.provider.springai;

import com.basicframework.framework.ai.config.AiModelProperties;
import com.basicframework.framework.ai.core.model.EmbeddingRequest;
import com.basicframework.framework.ai.core.model.EmbeddingResponse;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.model.ModelEvent;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.ai.core.model.ModelProbeKind;
import com.basicframework.framework.ai.core.model.ModelProbeResult;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.ModelStream;
import com.basicframework.framework.ai.core.model.ModelToolCall;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.ai.core.model.StructuredJsonOutput;
import com.basicframework.framework.ai.core.model.StructuredModelRequest;
import com.basicframework.framework.ai.core.model.StructuredModelResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

/**
 * Spring AI 受管客户端（M02 建立，M03 增加文本流与结构化输出，M04 增加批量嵌入与能力探测）：本包是全仓库唯一引用
 * Spring AI 类型的区域。
 *
 * <p>职责：把自有请求转成厂商调用、把厂商输出转回自有响应/事件、把失败收敛为稳定错误，
 * 并落实输出大小上限、有界重试、流式空闲超时与嵌入批次上限。凭据只存在于构造时的受控内存中；
 * 客户端关闭后拒绝新请求。工具调用只作为数据向外暴露，本类不执行任何工具。
 */
public class SpringAiModelClient implements ModelPort {

    /** 结构化输出的提示词外壳：把 Schema 作为输出契约交给模型，平台侧再校验。 */
    private static final String STRUCTURED_INSTRUCTION =
            """
            请只输出一个 JSON 对象，不要输出解释文字或 Markdown 代码块。
            输出必须满足以下 JSON Schema：
            """;

    /** 探测用最小提示词与 Schema：只验证能力可用，不承载业务语义。 */
    private static final String PROBE_PROMPT = "ping";

    private static final String PROBE_SCHEMA =
            "{\"type\":\"object\",\"required\":[\"ok\"],\"properties\":{\"ok\":{\"type\":\"boolean\"}}}";

    private static final String PROBE_TOOL_NAME = "platform_probe";

    private static final String PROBE_TOOL_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}";

    private static final String PROBE_TOOL_PROMPT = "请调用 platform_probe 工具，参数 value=ping。只在需要调用工具时返回工具调用，不要返回解释文字。";

    private final ModelEndpointSnapshot snapshot;

    private final ChatModel chatModel;

    /** 仅在端点声明 EMBEDDING 时装配；其余情况为 null，嵌入调用按能力缺失拒绝。 */
    private final EmbeddingModel embeddingModel;

    private final Set<ModelCapability> capabilities;

    private final AiModelProperties properties;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SpringAiModelClient(ModelEndpointSnapshot snapshot, ChatModel chatModel) {
        this(snapshot, chatModel, null, new AiModelProperties());
    }

    public SpringAiModelClient(ModelEndpointSnapshot snapshot, ChatModel chatModel, AiModelProperties properties) {
        this(snapshot, chatModel, null, properties);
    }

    public SpringAiModelClient(
            ModelEndpointSnapshot snapshot,
            ChatModel chatModel,
            EmbeddingModel embeddingModel,
            AiModelProperties properties) {
        this.snapshot = snapshot;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.capabilities = Set.copyOf(snapshot.capabilities());
        this.properties = properties;
    }

    @Override
    public Set<ModelCapability> capabilities() {
        return capabilities;
    }

    @Override
    public ModelResponse generate(ModelRequest request) {
        requireOpen();
        requireCapability(ModelCapability.TEXT);
        return withRetry(() -> callOnce(request));
    }

    @Override
    public ModelStream stream(ModelRequest request) {
        requireOpen();
        requireCapability(ModelCapability.TEXT_STREAM);
        String prompt = request.prompt() == null ? "" : request.prompt();
        try {
            Flux<ChatResponse> responses = chatModel.stream(new Prompt(new UserMessage(prompt)));
            return new SpringAiModelStream(responses, properties, snapshot.modelId(), request.timeout());
        } catch (Exception exception) {
            throw mapFailure(exception);
        }
    }

    @Override
    public StructuredModelResult generateStructured(StructuredModelRequest request) {
        requireOpen();
        requireCapability(ModelCapability.STRUCTURED_OUTPUT);
        StructuredJsonOutput.validateRequestSchema(request.jsonSchema());
        ModelRequest promptRequest =
                new ModelRequest(request.modelId(), buildStructuredPrompt(request), request.timeout());
        ModelResponse response = withRetry(() -> callOnce(promptRequest));
        JsonNode value = StructuredJsonOutput.parseObject(response.text(), properties.getMaxRepairSteps());
        return new StructuredModelResult(
                StructuredJsonOutput.compact(value),
                value,
                response.usage(),
                response.finishReason(),
                snapshot.modelId());
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        requireOpen();
        requireCapability(ModelCapability.EMBEDDING);
        if (embeddingModel == null) {
            throw new ModelException(
                    ModelException.Reason.CAPABILITY_UNSUPPORTED, "端点未装配嵌入模型：" + ModelCapability.EMBEDDING);
        }
        if (request.batchSize() > properties.getMaxEmbeddingBatch()) {
            throw new ModelException(
                    ModelException.Reason.BATCH_TOO_LARGE, "嵌入批次超过上限（" + properties.getMaxEmbeddingBatch() + " 条）");
        }
        return withRetry(() -> embedOnce(request));
    }

    private EmbeddingResponse embedOnce(EmbeddingRequest request) {
        try {
            org.springframework.ai.embedding.EmbeddingResponse vendorResponse =
                    embeddingModel.embedForResponse(request.texts());
            return toEmbeddingResponse(vendorResponse, request.batchSize());
        } catch (Exception exception) {
            throw mapFailure(exception);
        }
    }

    private EmbeddingResponse toEmbeddingResponse(
            org.springframework.ai.embedding.EmbeddingResponse vendorResponse, int expectedSize) {
        List<org.springframework.ai.embedding.Embedding> results =
                vendorResponse == null ? null : vendorResponse.getResults();
        if (results == null || results.size() != expectedSize) {
            throw new ModelException(ModelException.Reason.UPSTREAM_FAILED, "嵌入响应条数与请求不一致");
        }
        List<org.springframework.ai.embedding.Embedding> ordered = new ArrayList<>(results);
        ordered.sort(Comparator.comparing(embedding -> embedding.getIndex() == null ? 0 : embedding.getIndex()));
        List<float[]> vectors = new ArrayList<>(ordered.size());
        for (var embedding : ordered) {
            vectors.add(embedding.getOutput());
        }
        ModelUsage usage = vendorResponse.getMetadata() == null
                ? ModelUsage.UNKNOWN
                : toUsage(vendorResponse.getMetadata().getUsage());
        return new EmbeddingResponse(vectors, usage, snapshot.modelId());
    }

    @Override
    public ModelProbeResult probe(ModelProbeKind kind) {
        if (closed.get()) {
            return ModelProbeResult.failed(kind, ModelException.Reason.AI_DISABLED, 0L);
        }
        long startedAt = System.nanoTime();
        try {
            return switch (kind) {
                case CONNECTIVITY -> probeConnectivity(startedAt);
                case TEXT -> probeText(startedAt);
                case TEXT_STREAM -> probeTextStream(startedAt);
                case STRUCTURED_OUTPUT -> probeStructuredOutput(startedAt);
                case TOOL_CALLING -> probeToolCalling(startedAt);
                case EMBEDDING -> probeEmbedding(startedAt);
            };
        } catch (ModelException exception) {
            if (exception.getReason() == ModelException.Reason.CAPABILITY_UNSUPPORTED) {
                return ModelProbeResult.unsupported(kind, ModelProbeResult.CODE_CAPABILITY_NOT_DECLARED);
            }
            return ModelProbeResult.failed(kind, exception.getReason(), elapsedMillis(startedAt));
        } catch (RuntimeException exception) {
            return ModelProbeResult.failed(kind, ModelException.Reason.UPSTREAM_FAILED, elapsedMillis(startedAt));
        }
    }

    private ModelProbeResult probeConnectivity(long startedAt) {
        callOnce(ModelRequest.of(snapshot.modelId(), PROBE_PROMPT));
        return ModelProbeResult.supported(ModelProbeKind.CONNECTIVITY, null, elapsedMillis(startedAt));
    }

    private ModelProbeResult probeText(long startedAt) {
        ModelResponse response = callOnce(ModelRequest.of(snapshot.modelId(), PROBE_PROMPT));
        if (response.text() == null || response.text().isBlank()) {
            return ModelProbeResult.unsupported(ModelProbeKind.TEXT, ModelProbeResult.CODE_NO_TEXT_RETURNED);
        }
        return ModelProbeResult.supported(ModelProbeKind.TEXT, null, elapsedMillis(startedAt));
    }

    /** 流式探测：至少收到一个文本增量并正常结束；失败按稳定原因记录，不静默降级。 */
    private ModelProbeResult probeTextStream(long startedAt) {
        int deltas = 0;
        try (ModelStream stream = stream(ModelRequest.of(snapshot.modelId(), PROBE_PROMPT))) {
            while (stream.hasNext()) {
                ModelEvent event = stream.next();
                if (event.type() == ModelEvent.Type.DELTA) {
                    deltas++;
                }
            }
        }
        if (deltas == 0) {
            return ModelProbeResult.unsupported(ModelProbeKind.TEXT_STREAM, ModelProbeResult.CODE_NO_TEXT_RETURNED);
        }
        return ModelProbeResult.supported(ModelProbeKind.TEXT_STREAM, null, elapsedMillis(startedAt));
    }

    private ModelProbeResult probeStructuredOutput(long startedAt) {
        generateStructured(new StructuredModelRequest(snapshot.modelId(), PROBE_PROMPT, PROBE_SCHEMA, null));
        return ModelProbeResult.supported(ModelProbeKind.STRUCTURED_OUTPUT, null, elapsedMillis(startedAt));
    }

    /**
     * 工具调用探测：注册一个**禁止执行**的占位工具并关闭厂商内部工具执行循环，
     * 只检查模型是否返回工具调用请求；一旦占位工具被执行即判定失败，杜绝自动执行。
     */
    private ModelProbeResult probeToolCalling(long startedAt) {
        requireCapability(ModelCapability.TOOL_CALLING);
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolCallback probeTool = new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(PROBE_TOOL_NAME)
                        .description("平台能力探测占位工具，禁止执行")
                        .inputSchema(PROBE_TOOL_SCHEMA)
                        .build();
            }

            @Override
            public String call(String toolInput) {
                executed.set(true);
                throw new IllegalStateException("探测占位工具不允许被执行");
            }
        };
        ChatResponse response;
        try {
            ChatOptions options = OpenAiChatOptions.builder()
                    .toolCallbacks(List.of(probeTool))
                    .internalToolExecutionEnabled(false)
                    .build();
            response = chatModel.call(new Prompt(new UserMessage(PROBE_TOOL_PROMPT), options));
        } catch (Exception exception) {
            throw mapFailure(exception);
        }
        if (executed.get()) {
            throw new ModelException(ModelException.Reason.UPSTREAM_FAILED, "探测占位工具被自动执行");
        }
        if (response == null || !response.hasToolCalls()) {
            return ModelProbeResult.unsupported(
                    ModelProbeKind.TOOL_CALLING, ModelProbeResult.CODE_TOOL_CALL_NOT_RETURNED);
        }
        return ModelProbeResult.supported(ModelProbeKind.TOOL_CALLING, null, elapsedMillis(startedAt));
    }

    private ModelProbeResult probeEmbedding(long startedAt) {
        EmbeddingResponse response = embed(new EmbeddingRequest(snapshot.modelId(), List.of(PROBE_PROMPT), null));
        return ModelProbeResult.supported(ModelProbeKind.EMBEDDING, response.dimensions(), elapsedMillis(startedAt));
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private ModelResponse callOnce(ModelRequest request) {
        String prompt = request.prompt() == null ? "" : request.prompt();
        try {
            ChatResponse response = chatModel.call(new Prompt(new UserMessage(prompt)));
            return toModelResponse(response);
        } catch (Exception exception) {
            throw mapFailure(exception);
        }
    }

    /**
     * 有界重试：只对可重试原因（超时、限流、上游失败）重发，次数由
     * {@link AiModelProperties#getMaxAttempts()} 限定；能力缺失、输入非法、上游明确拒绝与
     * 输出越界都不重试。
     */
    private <T> T withRetry(Supplier<T> call) {
        int attempts = Math.max(1, properties.getMaxAttempts());
        ModelException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return call.get();
            } catch (ModelException exception) {
                if (!isRetryable(exception.getReason()) || attempt == attempts) {
                    throw exception;
                }
                lastFailure = exception;
                sleepBeforeRetry();
            }
        }
        throw lastFailure == null ? new ModelException(ModelException.Reason.UPSTREAM_FAILED, "模型调用失败") : lastFailure;
    }

    private void sleepBeforeRetry() {
        Duration backoff = properties.getRetryBackoff();
        if (backoff == null || backoff.isZero() || backoff.isNegative()) {
            return;
        }
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelException(ModelException.Reason.TIMEOUT, "重试等待被中断", exception);
        }
    }

    /** 可重试原因白名单：其余原因重发没有意义，只会放大上游压力。 */
    static boolean isRetryable(ModelException.Reason reason) {
        return reason == ModelException.Reason.TIMEOUT
                || reason == ModelException.Reason.RATE_LIMITED
                || reason == ModelException.Reason.UPSTREAM_FAILED;
    }

    private ModelResponse toModelResponse(ChatResponse response) {
        String text = "";
        String finishReason = null;
        List<ModelToolCall> toolCalls = List.of();
        if (response != null && response.getResult() != null) {
            Generation generation = response.getResult();
            AssistantMessage output = generation.getOutput();
            if (output != null && output.getText() != null) {
                text = output.getText();
            }
            if (output != null) {
                toolCalls = toToolCalls(output.getToolCalls());
            }
            if (generation.getMetadata() != null && generation.getMetadata().getFinishReason() != null) {
                finishReason = generation.getMetadata().getFinishReason();
            }
        }
        return new ModelResponse(text, toUsage(response), toolCalls, snapshot.modelId(), finishReason);
    }

    /**
     * 厂商用量映射：Spring AI 用 {@link EmptyUsage} 表示"上游没有提供用量"，
     * 而它的 getter 会把缺失计数报成 0。这里必须还原为 {@link ModelUsage#UNKNOWN}，
     * 否则平台会把"没给"显示成假的 0（AT-060 明确禁止）。
     */
    static ModelUsage toUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return ModelUsage.UNKNOWN;
        }
        return toUsage(response.getMetadata().getUsage());
    }

    /** 嵌入响应与聊天响应共用同一套用量语义（见 {@link #toUsage(ChatResponse)}）。 */
    static ModelUsage toUsage(Usage vendorUsage) {
        if (vendorUsage == null || vendorUsage instanceof EmptyUsage) {
            return ModelUsage.UNKNOWN;
        }
        return ModelUsage.of(vendorUsage.getPromptTokens(), vendorUsage.getCompletionTokens());
    }

    private static List<ModelToolCall> toToolCalls(List<AssistantMessage.ToolCall> vendorCalls) {
        if (vendorCalls == null || vendorCalls.isEmpty()) {
            return List.of();
        }
        List<ModelToolCall> calls = new ArrayList<>(vendorCalls.size());
        for (AssistantMessage.ToolCall call : vendorCalls) {
            calls.add(new ModelToolCall(call.id(), call.name(), call.arguments()));
        }
        return calls;
    }

    private static String buildStructuredPrompt(StructuredModelRequest request) {
        String prompt = request.prompt() == null ? "" : request.prompt();
        return STRUCTURED_INSTRUCTION + request.jsonSchema() + "\n\n输入：\n" + prompt;
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new ModelException(ModelException.Reason.UPSTREAM_FAILED, "模型客户端已关闭");
        }
    }

    /** 能力缺失必须在调用前失败，并在消息中指明缺失的能力。 */
    private void requireCapability(ModelCapability capability) {
        if (!capabilities.contains(capability)) {
            throw new ModelException(ModelException.Reason.CAPABILITY_UNSUPPORTED, "端点不支持所需能力：" + capability);
        }
    }

    /** 失败映射：只暴露稳定原因，不回传厂商原始报文。 */
    static ModelException mapFailure(Exception exception) {
        if (exception instanceof ModelException modelException) {
            return modelException;
        }
        String name = exception.getClass().getName().toLowerCase(Locale.ROOT);
        if (name.contains("timeout")) {
            return new ModelException(ModelException.Reason.TIMEOUT, "模型调用超时", exception);
        }
        if (name.contains("nontransient")) {
            return new ModelException(ModelException.Reason.UPSTREAM_REJECTED, "上游拒绝了模型调用", exception);
        }
        if (name.contains("transient")) {
            return new ModelException(ModelException.Reason.RATE_LIMITED, "上游限流或短暂不可用", exception);
        }
        return new ModelException(ModelException.Reason.UPSTREAM_FAILED, "模型调用失败", exception);
    }

    /** 关闭客户端：释放厂商资源并拒绝后续请求。 */
    public void close() {
        closed.set(true);
    }

    public ModelEndpointSnapshot snapshot() {
        return snapshot;
    }
}
