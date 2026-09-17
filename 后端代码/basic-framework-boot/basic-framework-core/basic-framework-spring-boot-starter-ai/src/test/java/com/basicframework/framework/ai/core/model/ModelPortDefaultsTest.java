package com.basicframework.framework.ai.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * M03 端口默认实现：未覆盖流的提供方必须给出"能力缺失"的明确错误，而不是静默降级。
 */
class ModelPortDefaultsTest {

    private static final ModelPort TEXT_ONLY = new ModelPort() {

        @Override
        public Set<ModelCapability> capabilities() {
            return Set.of(ModelCapability.TEXT);
        }

        @Override
        public ModelResponse generate(ModelRequest request) {
            return new ModelResponse("ok", ModelUsage.UNKNOWN, null, "gpt-4o-mini", null);
        }
    };

    @Test
    void defaultStreamRejectsWithMissingCapability() {
        assertThatThrownBy(() -> TEXT_ONLY.stream(ModelRequest.of("gpt-4o-mini", "hi")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> {
                    ModelException modelException = (ModelException) exception;
                    assertThat(modelException.getReason()).isEqualTo(ModelException.Reason.CAPABILITY_UNSUPPORTED);
                    assertThat(modelException.getMessage()).contains("TEXT_STREAM");
                });
    }

    @Test
    void defaultStructuredOutputRejectsWithMissingCapability() {
        assertThatThrownBy(() -> TEXT_ONLY.generateStructured(
                        StructuredModelRequest.of("gpt-4o-mini", "hi", "{\"type\":\"object\"}")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> {
                    ModelException modelException = (ModelException) exception;
                    assertThat(modelException.getReason()).isEqualTo(ModelException.Reason.CAPABILITY_UNSUPPORTED);
                    assertThat(modelException.getMessage()).contains("STRUCTURED_OUTPUT");
                });
    }

    @Test
    void streamContractDefaultsToNoConfiguredIdleTimeout() {
        ModelStream stream = new ModelStream() {

            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public ModelEvent next() {
                throw new IllegalStateException("没有事件");
            }

            @Override
            public void close() {
                // 无资源可释放
            }
        };

        assertThat(stream.hasNext()).isFalse();
        assertThat(stream.idleTimeout()).as("默认不声明空闲超时，由实现自行决定").isNull();
    }

    @Test
    void oneShotCallStillWorksWithoutOptionalCapabilities() {
        ModelResponse response = TEXT_ONLY.generate(ModelRequest.of("gpt-4o-mini", "hi"));

        assertThat(response.text()).isEqualTo("ok");
        assertThat(TEXT_ONLY.capabilities()).containsExactly(ModelCapability.TEXT);
    }
}
