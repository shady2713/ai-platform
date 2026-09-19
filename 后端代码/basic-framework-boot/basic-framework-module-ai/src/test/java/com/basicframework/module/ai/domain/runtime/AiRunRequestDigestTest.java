package com.basicframework.module.ai.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** O02 受理请求摘要：只由业务内容决定，附件与上下文顺序无关，排除 traceId/token。 */
class AiRunRequestDigestTest {

    @Test
    void sameBusinessContentProducesSameDigest() {
        String first =
                AiRunRequestDigest.compute(9L, 31L, "帮我查订单", List.of("file-a", "file-b"), "{\"page\":\"order\"}");
        String second =
                AiRunRequestDigest.compute(9L, 31L, "帮我查订单", List.of("file-a", "file-b"), "{\"page\":\"order\"}");

        assertThat(first).hasSize(64).isEqualTo(second);
    }

    @Test
    void attachmentsAreOrderInsensitive() {
        String ordered = AiRunRequestDigest.compute(9L, 31L, "消息", List.of("file-a", "file-b"), "{}");
        String shuffled = AiRunRequestDigest.compute(9L, 31L, "消息", List.of("file-b", "file-a"), "{}");

        assertThat(shuffled).as("同一组附件无论提交顺序都是同一请求").isEqualTo(ordered);
        assertThat(AiRunRequestDigest.compute(9L, 31L, "消息", List.of("file-a"), "{}"))
                .as("附件集合变化即视为不同请求")
                .isNotEqualTo(ordered);
    }

    @Test
    void anyBusinessFieldChangeChangesTheDigest() {
        String base = AiRunRequestDigest.compute(9L, 31L, "消息", List.of("file-a"), "{\"page\":\"order\"}");

        assertThat(AiRunRequestDigest.compute(10L, 31L, "消息", List.of("file-a"), "{\"page\":\"order\"}"))
                .isNotEqualTo(base);
        assertThat(AiRunRequestDigest.compute(9L, 32L, "消息", List.of("file-a"), "{\"page\":\"order\"}"))
                .isNotEqualTo(base);
        assertThat(AiRunRequestDigest.compute(9L, 31L, "换个消息", List.of("file-a"), "{\"page\":\"order\"}"))
                .isNotEqualTo(base);
        assertThat(AiRunRequestDigest.compute(9L, 31L, "消息", List.of("file-a"), "{\"page\":\"customer\"}"))
                .isNotEqualTo(base);
        assertThat(AiRunRequestDigest.compute(9L, null, "消息", List.of("file-a"), "{\"page\":\"order\"}"))
                .as("无会话的一次性运行与有会话的运行不同")
                .isNotEqualTo(base);
    }

    @Test
    void fieldBoundariesCannotBeAmbiguous() {
        // 长度前缀分隔：拼接歧义（"ab"+"" 与 "a"+"b"）不会产生同一摘要
        String left = AiRunRequestDigest.compute(9L, 31L, "ab", List.of(), "");
        String right = AiRunRequestDigest.compute(9L, 31L, "a", List.of(), "b");

        assertThat(left).isNotEqualTo(right);
    }

    @Test
    void emptyInputsAreAcceptedAndStable() {
        assertThat(AiRunRequestDigest.compute(null, null, null, null, null))
                .hasSize(64)
                .isEqualTo(AiRunRequestDigest.compute(null, null, null, List.of(), null));
    }
}
