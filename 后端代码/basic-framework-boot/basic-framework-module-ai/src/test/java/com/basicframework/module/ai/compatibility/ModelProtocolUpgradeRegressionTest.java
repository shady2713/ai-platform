package com.basicframework.module.ai.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.config.AiModelProperties;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.model.ModelEvent;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.ModelStream;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.ai.core.model.StructuredModelRequest;
import com.basicframework.framework.ai.core.model.StructuredModelResult;
import com.basicframework.framework.ai.provider.springai.SpringAiModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.publisher.Flux;

/**
 * AT-065 上游候选升级回归（模型侧协议，基线模式）。
 *
 * <p>卡面要求"以冻结候选夹具（无历史产物时即基线夹具）跑模型侧协议回归：文本/流式/结构化输出的
 * 请求-响应契约，断言升级前后行为一致"。做法分两层，两层都对准真实代码：
 * <ol>
 *   <li><b>线级</b>：冻结的 OpenAI 兼容响应 JSON 必须能被候选上游自身的线级模型
 *       （{@code OpenAiApi.ChatCompletion/ChatCompletionChunk}）按原字段名解析；字段改名/缺失在这里直接暴露；</li>
 *   <li><b>平台级</b>：解析结果送入**真实适配器** {@link SpringAiModelClient}，把平台契约对象规范化后
 *       与 {@link ProtocolBaselineGolden} 逐字比对。</li>
 * </ol>
 *
 * <p>厂商类型只出现在本测试包：生产代码的厂商边界（ArchUnit rule H，只导入 main classes）不受影响；
 * 兼容回归必须引用候选上游自己的线级契约，否则"升级是否破坏协议"无从判定。
 *
 * <p>负向对照（{@code candidate*IsDetected} 用例）：同样的比较逻辑必须能把被改坏的上游产物判为不一致（红），
 * 否则本回归包就是空断言。
 */
class ModelProtocolUpgradeRegressionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL_ID = "gpt-4o-mini";

    private static ModelEndpointSnapshot snapshot(ModelCapability... capabilities) {
        return new ModelEndpointSnapshot(
                901L,
                1,
                1,
                "openai_compatible",
                "https://candidate.example.com/v1",
                MODEL_ID,
                Set.of(capabilities),
                "sk-q08-compat");
    }

    private static SpringAiModelClient client(ChatModel model, ModelCapability... capabilities) {
        return new SpringAiModelClient(snapshot(capabilities), model, new AiModelProperties());
    }

    private static OpenAiApi.ChatCompletion parseCompletion(String json) throws Exception {
        return MAPPER.readValue(json, OpenAiApi.ChatCompletion.class);
    }

    private static OpenAiApi.ChatCompletionChunk parseChunk(String json) throws Exception {
        return MAPPER.readValue(json, OpenAiApi.ChatCompletionChunk.class);
    }

    /** 线级响应 → 平台响应：只经过厂商类型与真实适配器，不绕过映射路径。 */
    private static ModelResponse platformResponse(String wireJson) throws Exception {
        ChatModel model = fixedModel(toVendorResponse(parseCompletion(wireJson)), null);
        return client(model, ModelCapability.TEXT).generate(ModelRequest.of(MODEL_ID, "ping"));
    }

    private static ChatModel fixedModel(ChatResponse response, RuntimeException failure) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                if (failure != null) {
                    throw failure;
                }
                return response;
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                if (failure != null) {
                    return Flux.error(failure);
                }
                return Flux.just(response);
            }
        };
    }

    /** 捕获提示词并返回固定响应的桩模型：用于断言请求侧契约（提示词直通、不注册工具回调）。 */
    private static ChatModel capturingModel(List<Prompt> seen, ChatResponse response) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                seen.add(prompt);
                return response;
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                seen.add(prompt);
                return Flux.just(response);
            }
        };
    }

    private static ChatResponse toVendorResponse(OpenAiApi.ChatCompletion completion) {
        OpenAiApi.ChatCompletion.Choice choice = completion.choices().get(0);
        return new ChatResponse(
                List.of(new Generation(toVendorMessage(choice.message()), finishReason(choice.finishReason()))),
                metadata(completion.usage()));
    }

    private static ChatResponse toVendorChunk(OpenAiApi.ChatCompletionChunk chunk) {
        OpenAiApi.ChatCompletionChunk.ChunkChoice choice = chunk.choices().get(0);
        return new ChatResponse(
                List.of(new Generation(toVendorMessage(choice.delta()), finishReason(choice.finishReason()))),
                metadata(chunk.usage()));
    }

    private static AssistantMessage toVendorMessage(OpenAiApi.ChatCompletionMessage message) {
        if (message == null) {
            return new AssistantMessage("");
        }
        String content = message.content() == null ? "" : message.content();
        List<OpenAiApi.ChatCompletionMessage.ToolCall> wireCalls = message.toolCalls();
        if (wireCalls == null || wireCalls.isEmpty()) {
            return new AssistantMessage(content);
        }
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (OpenAiApi.ChatCompletionMessage.ToolCall call : wireCalls) {
            var function = call.function();
            calls.add(new AssistantMessage.ToolCall(
                    call.id(),
                    call.type() == null ? "function" : call.type(),
                    function == null ? null : function.name(),
                    function == null ? null : function.arguments()));
        }
        return AssistantMessage.builder().content(content).toolCalls(calls).build();
    }

    /** 线级 finish_reason 枚举 → 平台可见的稳定字符串（STOP → "stop"，与 OpenAI 协议取值一致）。 */
    private static ChatGenerationMetadata finishReason(OpenAiApi.ChatCompletionFinishReason reason) {
        return ChatGenerationMetadata.builder()
                .finishReason(reason == null ? null : reason.name().toLowerCase(Locale.ROOT))
                .build();
    }

    private static ChatResponseMetadata metadata(OpenAiApi.Usage usage) {
        if (usage == null) {
            return ChatResponseMetadata.builder().build();
        }
        return ChatResponseMetadata.builder()
                .usage(new DefaultUsage(usage.promptTokens(), usage.completionTokens()))
                .build();
    }

    private static List<ModelEvent> drain(ModelStream stream) {
        List<ModelEvent> events = new ArrayList<>();
        try (stream) {
            while (stream.hasNext()) {
                events.add(stream.next());
            }
        }
        return events;
    }

    private static ChatModel chunkModel(List<OpenAiApi.ChatCompletionChunk> chunks) {
        List<ChatResponse> responses = new ArrayList<>();
        for (OpenAiApi.ChatCompletionChunk chunk : chunks) {
            responses.add(toVendorChunk(chunk));
        }
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("本用例只走流式通道");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.fromIterable(responses);
            }
        };
    }

    private static List<ModelEvent> streamFrozenChunks(List<String> chunkJsons) throws Exception {
        List<OpenAiApi.ChatCompletionChunk> chunks = new ArrayList<>();
        for (String chunkJson : chunkJsons) {
            chunks.add(parseChunk(chunkJson));
        }
        return drain(client(chunkModel(chunks), ModelCapability.TEXT, ModelCapability.TEXT_STREAM).stream(
                ModelRequest.of(MODEL_ID, "ping")));
    }

    @Test
    void recordedUpstreamRequestShapeIsFrozenAndPromptPassesThroughVerbatim() throws Exception {
        JsonNode recorded = MAPPER.readTree(FrozenUpstreamWireFixtures.RECORDED_TEXT_REQUEST_JSON);
        assertThat(recorded.path("messages")).hasSize(1);
        assertThat(recorded.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(recorded.path("messages").get(0).path("content").asText()).isEqualTo("ping");
        assertThat(recorded.path("model").asText()).isEqualTo("probe-model");
        assertThat(recorded.path("stream").asBoolean()).isFalse();
        assertThat(recorded.path("temperature").asDouble()).isEqualTo(0.0);
        JsonNode recordedEmbedding = MAPPER.readTree(FrozenUpstreamWireFixtures.RECORDED_EMBEDDING_REQUEST_JSON);
        assertThat(recordedEmbedding.path("input").get(0).asText()).isEqualTo("hello embedding");

        List<Prompt> seen = new ArrayList<>();
        client(
                        capturingModel(
                                seen, toVendorResponse(parseCompletion(FrozenUpstreamWireFixtures.TEXT_RESPONSE_JSON))),
                        ModelCapability.TEXT)
                .generate(ModelRequest.of(MODEL_ID, "ping"));

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).getInstructions())
                .singleElement()
                .isInstanceOf(UserMessage.class)
                .extracting(instruction -> ((UserMessage) instruction).getText())
                .isEqualTo("ping");
        // 工具调用只作为数据：适配器不得注册厂商工具回调（否则模型会自主执行工具）
        assertThat(seen.get(0).getOptions()).isNull();
    }

    @Test
    void textContractIsUnchanged() throws Exception {
        ModelResponse response = platformResponse(FrozenUpstreamWireFixtures.TEXT_RESPONSE_JSON);

        assertThat(ProtocolBaselineGolden.snapshot(response))
                .as("文本请求-响应契约必须与冻结基线一致")
                .isEqualTo(ProtocolBaselineGolden.TEXT_SNAPSHOT);
        assertThat(response.modelId()).isEqualTo(MODEL_ID);
    }

    @Test
    void streamContractIsUnchanged() throws Exception {
        List<ModelEvent> events = streamFrozenChunks(FrozenUpstreamWireFixtures.STREAM_CHUNK_JSONS);

        assertThat(ProtocolBaselineGolden.snapshot(events))
                .as("流式事件序列（增量、工具调用聚合、终态用量）必须与冻结基线一致")
                .isEqualTo(ProtocolBaselineGolden.STREAM_SNAPSHOT);
    }

    @Test
    void structuredOutputContractIsUnchanged() throws Exception {
        List<Prompt> seen = new ArrayList<>();
        ChatModel fencedModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                seen.add(prompt);
                return new ChatResponse(
                        List.of(new Generation(
                                new AssistantMessage(FrozenUpstreamWireFixtures.STRUCTURED_OUTPUT_TEXT),
                                ChatGenerationMetadata.builder()
                                        .finishReason("stop")
                                        .build())),
                        ChatResponseMetadata.builder()
                                .usage(new DefaultUsage(7, 3))
                                .build());
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                throw new UnsupportedOperationException("结构化输出不走流式通道");
            }
        };

        StructuredModelResult result = client(fencedModel, ModelCapability.STRUCTURED_OUTPUT)
                .generateStructured(StructuredModelRequest.of(
                        MODEL_ID, "统计八月华东净销售额", FrozenUpstreamWireFixtures.STRUCTURED_SCHEMA_JSON));

        assertThat(ProtocolBaselineGolden.snapshot(result))
                .as("结构化输出契约（有界修复后的紧凑 JSON 与用量）必须与冻结基线一致")
                .isEqualTo(ProtocolBaselineGolden.STRUCTURED_SNAPSHOT);

        assertThat(seen).hasSize(1);
        String upstreamPrompt = ((UserMessage) seen.get(0).getInstructions().get(0)).getText();
        assertThat(upstreamPrompt).contains(FrozenUpstreamWireFixtures.STRUCTURED_SCHEMA_JSON);
        assertThat(upstreamPrompt).contains("统计八月华东净销售额");
    }

    @Test
    void structuredOutputArrayIsRejectedInsteadOfAccepted() {
        ChatModel arrayModel =
                fixedModel(new ChatResponse(List.of(new Generation(new AssistantMessage("[1,2]")))), null);

        assertThatThrownBy(() -> client(arrayModel, ModelCapability.STRUCTURED_OUTPUT)
                        .generateStructured(StructuredModelRequest.of(
                                MODEL_ID, "统计", FrozenUpstreamWireFixtures.STRUCTURED_SCHEMA_JSON)))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.INVALID_STRUCTURED_OUTPUT));
    }

    @Test
    void usageMissingIsUnknownNotFakeZero() throws Exception {
        ModelUsage usage = platformResponse(FrozenUpstreamWireFixtures.TEXT_RESPONSE_WITHOUT_USAGE_JSON)
                .usage();

        assertThat("prompt=" + usage.promptTokens() + "; completion=" + usage.completionTokens() + "; total="
                        + usage.totalTokens() + "; known=" + usage.isKnown() + "; estimated=" + usage.estimated())
                .as("上游缺失用量必须收敛为 UNKNOWN，不得写成假 0（AT-060）")
                .isEqualTo(ProtocolBaselineGolden.UNKNOWN_USAGE_SNAPSHOT);
    }

    @Test
    void upstreamFailureMappingIsStableAndDoesNotLeak() {
        ChatModel failing =
                fixedModel(null, new IllegalStateException(FrozenUpstreamWireFixtures.UPSTREAM_FAILURE_CANARY));

        assertThatThrownBy(() -> client(failing, ModelCapability.TEXT)
                        .generate(new ModelRequest(MODEL_ID, "ping", Duration.ofSeconds(2))))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> {
                    ModelException modelException = (ModelException) exception;
                    assertThat(modelException.getReason()).isEqualTo(ModelException.Reason.UPSTREAM_FAILED);
                    assertThat(modelException.getMessage())
                            .doesNotContain(FrozenUpstreamWireFixtures.UPSTREAM_FAILURE_CANARY)
                            .doesNotContain("sk-");
                });
    }

    /** 负向对照：候选把线级字段改名（content→text）时，回归包必须判为不一致。 */
    @Test
    void candidateWireContractChangeIsDetected() throws Exception {
        String mutated = FrozenUpstreamWireFixtures.TEXT_RESPONSE_JSON.replace("\"content\"", "\"text\"");
        OpenAiApi.ChatCompletion parsed = parseCompletion(mutated);

        assertThat(parsed.choices().get(0).message().content())
                .as("线级字段改名后候选自身的线级模型读不到 content")
                .isNull();

        String snapshot = ProtocolBaselineGolden.snapshot(platformResponse(mutated));
        assertThat(snapshot).as("被改坏的候选产物必须与基线不一致（否则回归包是空断言）").isNotEqualTo(ProtocolBaselineGolden.TEXT_SNAPSHOT);
        assertThat(snapshot).startsWith("text=;");
    }

    /** 负向对照：候选流式分片丢掉 delta 时，真实适配器产出的事件序列必须与基线不一致。 */
    @Test
    void candidateStreamChunkChangeIsDetected() throws Exception {
        List<String> mutatedChunks = new ArrayList<>(FrozenUpstreamWireFixtures.STREAM_CHUNK_JSONS);
        for (int index = 2; index <= 3; index++) {
            mutatedChunks.set(index, mutatedChunks.get(index).replace("\"delta\"", "\"payload\""));
        }
        assertThat(parseChunk(mutatedChunks.get(2)).choices().get(0).delta())
                .as("被改坏的候选分片读不到 delta")
                .isNull();

        String snapshot = ProtocolBaselineGolden.snapshot(streamFrozenChunks(mutatedChunks));

        assertThat(snapshot)
                .as("工具调用分片丢失后事件序列必须与基线不一致（差异点正是工具调用聚合）")
                .isNotEqualTo(ProtocolBaselineGolden.STREAM_SNAPSHOT)
                .contains("DELTA[pong]")
                .contains("DELTA[ from mock]")
                .doesNotContain("TOOL_CALL");
    }
}
