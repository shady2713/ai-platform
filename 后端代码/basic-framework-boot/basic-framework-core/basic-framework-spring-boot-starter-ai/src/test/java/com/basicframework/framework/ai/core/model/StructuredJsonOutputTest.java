package com.basicframework.framework.ai.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * M03 结构化输出解析：三种确定性修复各覆盖一次，"修复后仍不可用"必须失败而不是猜内容。
 */
class StructuredJsonOutputTest {

    private static final int DEFAULT_STEPS = StructuredJsonOutput.MAX_SUPPORTED_REPAIR_STEPS;

    @Test
    void parsesPlainObjectWithoutRepair() {
        JsonNode node = StructuredJsonOutput.parseObject("{\"a\":1}", 0);

        assertThat(node.get("a").asInt()).isEqualTo(1);
    }

    @Test
    void stripsMarkdownFence() {
        JsonNode node = StructuredJsonOutput.parseObject("```json\n{\"a\":1}\n```", DEFAULT_STEPS);

        assertThat(node.get("a").asInt()).isEqualTo(1);
    }

    @Test
    void extractsObjectFromSurroundingProse() {
        JsonNode node = StructuredJsonOutput.parseObject("好的，结果是：{\"a\":1}，请查收。", DEFAULT_STEPS);

        assertThat(node.get("a").asInt()).isEqualTo(1);
    }

    @Test
    void removesTrailingComma() {
        JsonNode node = StructuredJsonOutput.parseObject("{\"a\":1,}", DEFAULT_STEPS);

        assertThat(node.get("a").asInt()).isEqualTo(1);
    }

    @Test
    void honorsRepairBudget() {
        assertThatThrownBy(() -> StructuredJsonOutput.parseObject("```json\n{\"a\":1}\n```", 0))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.INVALID_STRUCTURED_OUTPUT));
    }

    @Test
    void failsOnUnrecoverableOutputWithoutGuessing() {
        assertThatThrownBy(() -> StructuredJsonOutput.parseObject("完全不是 JSON", DEFAULT_STEPS))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.INVALID_STRUCTURED_OUTPUT));
        assertThatThrownBy(() -> StructuredJsonOutput.parseObject("{\"a\": ", DEFAULT_STEPS))
                .isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> StructuredJsonOutput.parseObject("   ", DEFAULT_STEPS))
                .isInstanceOf(ModelException.class);
    }

    @Test
    void rejectsNonObjectJson() {
        assertThatThrownBy(() -> StructuredJsonOutput.parseObject("[1,2,3]", DEFAULT_STEPS))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.INVALID_STRUCTURED_OUTPUT));
        assertThatThrownBy(() -> StructuredJsonOutput.parseObject("\"text\"", DEFAULT_STEPS))
                .isInstanceOf(ModelException.class);
    }

    @Test
    void validatesRequestSchemaShape() {
        StructuredJsonOutput.validateRequestSchema("{\"type\":\"object\"}");

        assertThatThrownBy(() -> StructuredJsonOutput.validateRequestSchema(null))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.INVALID_STRUCTURED_INPUT));
        assertThatThrownBy(() -> StructuredJsonOutput.validateRequestSchema("[1]"))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.INVALID_STRUCTURED_INPUT));
        assertThatThrownBy(() -> StructuredJsonOutput.validateRequestSchema("{不是 JSON}"))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.INVALID_STRUCTURED_INPUT));
    }

    @Test
    void compactsJsonForStableStorage() {
        JsonNode node = StructuredJsonOutput.parseObject("{ \"b\" : 2,\n \"a\" : 1 }", DEFAULT_STEPS);

        assertThat(StructuredJsonOutput.compact(node)).isEqualTo("{\"b\":2,\"a\":1}");
    }
}
