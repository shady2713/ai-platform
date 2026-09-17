package com.basicframework.framework.ai.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * M03 契约夹具：把真实模型输出的几种典型外形固定为资源文件，修复边界由夹具钉住。
 *
 * <p>夹具与 {@code StructuredJsonOutput} 的修复步骤一一对应：合法、围栏+说明文字、
 * 末尾逗号、不可修复；新增修复类型必须先加夹具再加实现。
 */
class StructuredOutputFixturesTest {

    private static final int DEFAULT_STEPS = StructuredJsonOutput.MAX_SUPPORTED_REPAIR_STEPS;

    private static String fixture(String name) throws IOException {
        try (InputStream in = StructuredOutputFixturesTest.class.getResourceAsStream("/ai/fixtures/" + name)) {
            assertThat(in).as("夹具必须随模块发布：%s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesPlainJsonFixture() throws IOException {
        JsonNodeAssertions.assertAnswerIsOk(
                StructuredJsonOutput.parseObject(fixture("structured-output-valid.json"), DEFAULT_STEPS));
    }

    @Test
    void repairsFencedFixtureWithSurroundingProse() throws IOException {
        JsonNodeAssertions.assertAnswerIsOk(
                StructuredJsonOutput.parseObject(fixture("structured-output-fenced-with-prose.txt"), DEFAULT_STEPS));
    }

    @Test
    void repairsTrailingCommaFixture() throws IOException {
        JsonNodeAssertions.assertAnswerIsOk(
                StructuredJsonOutput.parseObject(fixture("structured-output-trailing-comma.txt"), DEFAULT_STEPS));
    }

    @Test
    void failsOnMalformedFixture() throws IOException {
        String malformed = fixture("structured-output-malformed.txt");

        assertThatThrownBy(() -> StructuredJsonOutput.parseObject(malformed, DEFAULT_STEPS))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.INVALID_STRUCTURED_OUTPUT));
    }

    /** 夹具断言集中一处：所有夹具表达同一个对象。 */
    private static final class JsonNodeAssertions {

        private static void assertAnswerIsOk(com.fasterxml.jackson.databind.JsonNode node) {
            assertThat(node.get("answer").asText()).isEqualTo("ok");
            assertThat(node.get("citations").isArray()).isTrue();
        }
    }
}
