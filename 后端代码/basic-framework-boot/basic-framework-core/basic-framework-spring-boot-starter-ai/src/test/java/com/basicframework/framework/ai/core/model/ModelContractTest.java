package com.basicframework.framework.ai.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * M02 模型契约不变量：缓存键由三个版本身份组成、快照 toString 必须脱敏（凭据不得泄漏到日志/trace）。
 */
class ModelContractTest {

    private static ModelEndpointSnapshot snapshot(String apiKey) {
        return new ModelEndpointSnapshot(
                9L,
                2,
                3,
                "openai_compatible",
                "https://api.example.com/v1",
                "gpt-4o-mini",
                Set.of(ModelCapability.TEXT),
                apiKey);
    }

    @Test
    void cacheKeyIsComposedOfVersionIdentitiesOnly() {
        ModelEndpointSnapshot snapshot = snapshot("sk-must-not-appear");
        ModelEndpointKey key = snapshot.key();

        assertThat(key.endpointId()).isEqualTo(9L);
        assertThat(key.configRevision()).isEqualTo(2);
        assertThat(key.credentialRevision()).isEqualTo(3);
        // 键的字符串形式不得包含凭据
        assertThat(key.toString()).doesNotContain("sk-must-not-appear");
        // 版本变化即得到不同的键（轮换/改配置后不会复用旧客户端）
        assertThat(new ModelEndpointSnapshot(
                                9L,
                                2,
                                4,
                                "openai_compatible",
                                "https://api.example.com/v1",
                                "gpt-4o-mini",
                                Set.of(ModelCapability.TEXT),
                                "sk-must-not-appear")
                        .key())
                .isNotEqualTo(key);
        assertThat(new ModelEndpointSnapshot(
                                9L,
                                3,
                                3,
                                "openai_compatible",
                                "https://api.example.com/v1",
                                "gpt-4o-mini",
                                Set.of(ModelCapability.TEXT),
                                "sk-must-not-appear")
                        .key())
                .isNotEqualTo(key);
    }

    @Test
    void snapshotToStringRedactsCredential() {
        ModelEndpointSnapshot snapshot = snapshot("sk-super-secret");

        String text = snapshot.toString();
        assertThat(text).doesNotContain("sk-super-secret");
        assertThat(text).contains("apiKey=***");
        assertThat(text).contains("endpointId=9");
        assertThat(snapshot.credentialConfigured()).isTrue();
        assertThat(snapshot(" ").credentialConfigured()).isFalse();
        assertThat(snapshot(null).credentialConfigured()).isFalse();
    }

    @Test
    void requestAndResponseCarryPlatformSemanticsOnly() {
        ModelRequest request = ModelRequest.of("gpt-4o-mini", "ping");
        assertThat(request.timeout()).isNull();
        assertThat(new ModelRequest("gpt-4o-mini", "ping", Duration.ofSeconds(5)).timeout())
                .isEqualTo(Duration.ofSeconds(5));

        ModelResponse response = new ModelResponse("pong", ModelUsage.of(3, 1), null, "gpt-4o-mini", "stop");
        assertThat(response.text()).isEqualTo("pong");
        assertThat(response.usage().promptTokens()).isEqualTo(3);
        assertThat(response.usage().completionTokens()).isEqualTo(1);
        assertThat(response.modelId()).isEqualTo("gpt-4o-mini");
        assertThat(response.finishReason()).isEqualTo("stop");
    }

    @Test
    void modelExceptionExposesStableReason() {
        ModelException exception = new ModelException(ModelException.Reason.TIMEOUT, "模型调用超时");
        assertThat(exception.getReason()).isEqualTo(ModelException.Reason.TIMEOUT);
        assertThat(exception).hasMessage("模型调用超时");

        ModelException withCause =
                new ModelException(ModelException.Reason.UPSTREAM_FAILED, "上游失败", new IllegalStateException("boom"));
        assertThat(withCause.getReason()).isEqualTo(ModelException.Reason.UPSTREAM_FAILED);
        assertThat(withCause.getCause()).hasMessage("boom");
    }
}
