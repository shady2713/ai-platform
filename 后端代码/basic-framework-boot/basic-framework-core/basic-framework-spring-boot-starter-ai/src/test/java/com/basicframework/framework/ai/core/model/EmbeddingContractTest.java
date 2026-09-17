package com.basicframework.framework.ai.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * M04 嵌入契约与端口默认实现：批次输入、维度自洽与"未覆盖即拒绝"的默认行为。
 */
class EmbeddingContractTest {

    @Test
    void requestRejectsEmptyBatchOrBlankText() {
        assertThatThrownBy(() -> EmbeddingRequest.of("text-embedding-3-small", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddingRequest.of("text-embedding-3-small", List.of("ok", "  ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddingRequest.of("text-embedding-3-small", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestCarriesBatchAndTimeout() {
        EmbeddingRequest request =
                new EmbeddingRequest("text-embedding-3-small", List.of("a", "b"), Duration.ofSeconds(3));

        assertThat(request.batchSize()).isEqualTo(2);
        assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(EmbeddingRequest.of("text-embedding-3-small", List.of("a")).timeout())
                .isNull();
    }

    @Test
    void responseChecksDimensionConsistency() {
        EmbeddingResponse response = new EmbeddingResponse(
                List.of(new float[] {1f, 2f}, new float[] {3f, 4f}), ModelUsage.UNKNOWN, "text-embedding-3-small");

        assertThat(response.size()).isEqualTo(2);
        assertThat(response.dimensions()).isEqualTo(2);
        assertThat(response.vector(1)).containsExactly(3f, 4f);
        assertThat(response.usage()).isSameAs(ModelUsage.UNKNOWN);
    }

    @Test
    void responseRejectsInconsistentOrEmptyVectors() {
        assertThatThrownBy(() ->
                        new EmbeddingResponse(Arrays.asList(new float[] {1f, 2f}, new float[] {1f, 2f, 3f}), null, "m"))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.EMBEDDING_DIMENSION_MISMATCH));
        assertThatThrownBy(() -> new EmbeddingResponse(List.of(), null, "m"))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.UPSTREAM_FAILED));
        assertThatThrownBy(() -> new EmbeddingResponse(Arrays.asList(new float[] {1f}, null), null, "m"))
                .isInstanceOf(ModelException.class);
    }

    @Test
    void portDefaultsRejectUnsupportedEmbeddingAndProbing() {
        ModelPort textOnly = new ModelPort() {

            @Override
            public Set<ModelCapability> capabilities() {
                return Set.of(ModelCapability.TEXT);
            }

            @Override
            public ModelResponse generate(ModelRequest request) {
                return new ModelResponse("ok", ModelUsage.UNKNOWN, null, "gpt-4o-mini", null);
            }
        };

        assertThatThrownBy(() -> textOnly.embed(EmbeddingRequest.of("m", List.of("a"))))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> {
                    ModelException modelException = (ModelException) exception;
                    assertThat(modelException.getReason()).isEqualTo(ModelException.Reason.CAPABILITY_UNSUPPORTED);
                    assertThat(modelException.getMessage()).contains("EMBEDDING");
                });

        ModelProbeResult probe = textOnly.probe(ModelProbeKind.EMBEDDING);
        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
        assertThat(probe.detailCode()).isEqualTo(ModelProbeResult.CODE_ADAPTER_NOT_IMPLEMENTED);
        assertThat(probe.isSupported()).isFalse();
    }

    @Test
    void probeResultFactoriesCarryStableCodes() {
        ModelProbeResult supported = ModelProbeResult.supported(ModelProbeKind.EMBEDDING, 1536, 12L);
        assertThat(supported.isSupported()).isTrue();
        assertThat(supported.embeddingDimension()).isEqualTo(1536);
        assertThat(supported.latencyMillis()).isEqualTo(12L);
        assertThat(supported.detailCode()).isNull();

        ModelProbeResult unsupported =
                ModelProbeResult.unsupported(ModelProbeKind.TEXT, ModelProbeResult.CODE_CAPABILITY_NOT_DECLARED);
        assertThat(unsupported.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
        assertThat(unsupported.detailCode()).isEqualTo("CAPABILITY_NOT_DECLARED");

        ModelProbeResult failed = ModelProbeResult.failed(ModelProbeKind.TEXT, ModelException.Reason.TIMEOUT, 30L);
        assertThat(failed.status()).isEqualTo(ModelProbeResult.Status.FAILED);
        assertThat(failed.detailCode()).isEqualTo("TIMEOUT");
        assertThat(failed.latencyMillis()).isEqualTo(30L);
    }
}
