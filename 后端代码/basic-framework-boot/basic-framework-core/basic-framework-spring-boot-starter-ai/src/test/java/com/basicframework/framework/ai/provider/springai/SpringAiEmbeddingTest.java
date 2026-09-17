package com.basicframework.framework.ai.provider.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.config.AiModelProperties;
import com.basicframework.framework.ai.core.model.EmbeddingRequest;
import com.basicframework.framework.ai.core.model.EmbeddingResponse;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.model.ModelException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.retry.TransientAiException;

/**
 * M04 批量嵌入：请求顺序保持、批次上限、响应条数与维度校验、用量语义与有界重试。
 *
 * <p>对应用户可见行为：维度改变必须拒绝（本层保证单次响应维度自洽，业务侧再与既有索引维度比对）、
 * 批次长度异常必须失败。
 */
class SpringAiEmbeddingTest {

    private static ModelEndpointSnapshot snapshot(ModelCapability... capabilities) {
        return new ModelEndpointSnapshot(
                1L,
                1,
                1,
                "openai_compatible",
                "https://api.example.com/v1",
                "text-embedding-3-small",
                Set.of(capabilities),
                "sk-test");
    }

    private static ChatModel unusedChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** 桩嵌入模型：按调用序号返回固定向量，可注入失败。 */
    private static EmbeddingModel embeddingModel(List<Embedding> results, DefaultUsage usage, int dimensions) {
        return new EmbeddingModel() {

            @Override
            public org.springframework.ai.embedding.EmbeddingResponse call(
                    org.springframework.ai.embedding.EmbeddingRequest request) {
                return new org.springframework.ai.embedding.EmbeddingResponse(
                        results, new EmbeddingResponseMetadata("text-embedding-3-small", usage));
            }

            @Override
            public float[] embed(Document document) {
                return new float[dimensions];
            }
        };
    }

    private static SpringAiModelClient client(EmbeddingModel embeddingModel, AiModelProperties properties) {
        return new SpringAiModelClient(
                snapshot(ModelCapability.TEXT, ModelCapability.EMBEDDING),
                unusedChatModel(),
                embeddingModel,
                properties);
    }

    @Test
    void embedsBatchKeepingRequestOrder() {
        // 厂商按 index 乱序返回时，平台输出必须回到请求顺序
        EmbeddingModel model = embeddingModel(
                List.of(new Embedding(new float[] {3f, 4f}, 1), new Embedding(new float[] {1f, 2f}, 0)),
                new DefaultUsage(10, 0),
                2);

        EmbeddingResponse response = client(model, new AiModelProperties())
                .embed(EmbeddingRequest.of("text-embedding-3-small", List.of("第一段", "第二段")));

        assertThat(response.size()).isEqualTo(2);
        assertThat(response.dimensions()).isEqualTo(2);
        assertThat(response.vector(0)).containsExactly(1f, 2f);
        assertThat(response.vector(1)).containsExactly(3f, 4f);
        assertThat(response.usage().promptTokens()).isEqualTo(10);
        assertThat(response.modelId()).isEqualTo("text-embedding-3-small");
    }

    @Test
    void marksUsageUnknownWhenVendorProvidesNone() {
        EmbeddingModel model = embeddingModel(List.of(new Embedding(new float[] {1f, 2f}, 0)), null, 2);

        EmbeddingResponse response = client(model, new AiModelProperties())
                .embed(EmbeddingRequest.of("text-embedding-3-small", List.of("一段")));

        assertThat(response.usage().isKnown()).as("上游没给用量时必须是 UNKNOWN，不是假 0").isFalse();
        assertThat(response.usage().promptTokens()).isNull();
    }

    @Test
    void rejectsBatchOverConfiguredLimitWithoutCallingUpstream() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingModel model = new EmbeddingModel() {

            @Override
            public org.springframework.ai.embedding.EmbeddingResponse call(
                    org.springframework.ai.embedding.EmbeddingRequest request) {
                calls.incrementAndGet();
                return new org.springframework.ai.embedding.EmbeddingResponse(List.of());
            }

            @Override
            public float[] embed(Document document) {
                return new float[2];
            }
        };
        AiModelProperties properties = new AiModelProperties();
        properties.setMaxEmbeddingBatch(1);

        assertThatThrownBy(() -> client(model, properties)
                        .embed(EmbeddingRequest.of("text-embedding-3-small", List.of("a", "b"))))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.BATCH_TOO_LARGE));
        assertThat(calls.get()).as("批次超限属输入错误，不重发也不占用上游配额").isZero();
    }

    @Test
    void rejectsResponseSizeMismatch() {
        EmbeddingModel model = embeddingModel(List.of(new Embedding(new float[] {1f, 2f}, 0)), null, 2);

        assertThatThrownBy(() -> client(model, new AiModelProperties())
                        .embed(EmbeddingRequest.of("text-embedding-3-small", List.of("a", "b"))))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.UPSTREAM_FAILED));
    }

    @Test
    void rejectsInconsistentVectorDimensions() {
        EmbeddingModel model = embeddingModel(
                List.of(new Embedding(new float[] {1f, 2f}, 0), new Embedding(new float[] {1f, 2f, 3f}, 1)), null, 2);

        assertThatThrownBy(() -> client(model, new AiModelProperties())
                        .embed(EmbeddingRequest.of("text-embedding-3-small", List.of("a", "b"))))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.EMBEDDING_DIMENSION_MISMATCH));
    }

    @Test
    void retriesTransientEmbeddingFailuresWithinBudget() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingModel model = new EmbeddingModel() {

            @Override
            public org.springframework.ai.embedding.EmbeddingResponse call(
                    org.springframework.ai.embedding.EmbeddingRequest request) {
                if (calls.incrementAndGet() == 1) {
                    throw new TransientAiException("429 Too Many Requests");
                }
                return new org.springframework.ai.embedding.EmbeddingResponse(
                        List.of(new Embedding(new float[] {1f, 2f}, 0)), null);
            }

            @Override
            public float[] embed(Document document) {
                return new float[2];
            }
        };
        AiModelProperties properties = new AiModelProperties();
        properties.setRetryBackoff(java.time.Duration.ofMillis(10));

        EmbeddingResponse response =
                client(model, properties).embed(EmbeddingRequest.of("text-embedding-3-small", List.of("a")));

        assertThat(response.dimensions()).isEqualTo(2);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void rejectsEmbeddingWhenEndpointLacksCapabilityOrModel() {
        SpringAiModelClient embedsNothing = new SpringAiModelClient(
                snapshot(ModelCapability.TEXT),
                unusedChatModel(),
                embeddingModel(List.of(), null, 2),
                new AiModelProperties());

        assertThatThrownBy(() -> embedsNothing.embed(EmbeddingRequest.of("m", List.of("a"))))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> {
                    ModelException modelException = (ModelException) exception;
                    assertThat(modelException.getReason()).isEqualTo(ModelException.Reason.CAPABILITY_UNSUPPORTED);
                    assertThat(modelException.getMessage()).contains("EMBEDDING");
                });

        SpringAiModelClient withoutModel = new SpringAiModelClient(
                snapshot(ModelCapability.TEXT, ModelCapability.EMBEDDING), unusedChatModel(), new AiModelProperties());

        assertThatThrownBy(() -> withoutModel.embed(EmbeddingRequest.of("m", List.of("a"))))
                .isInstanceOf(ModelException.class)
                .satisfies(exception ->
                        assertThat(((ModelException) exception).getMessage()).contains("未装配嵌入模型"));
    }

    @Test
    void rejectsEmbeddingAfterClose() {
        SpringAiModelClient closed =
                client(embeddingModel(List.of(new Embedding(new float[] {1f}, 0)), null, 1), new AiModelProperties());
        closed.close();

        assertThatThrownBy(() -> closed.embed(EmbeddingRequest.of("m", List.of("a"))))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.UPSTREAM_FAILED));
    }
}
