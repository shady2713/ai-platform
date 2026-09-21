package com.basicframework.module.ai.domain.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** D08 工具输入 schema：声明参数面、伪造参数拒绝、类型归一与政策解析。 */
class AiToolInputSchemaTest {

    private static final String SCHEMA =
            """
            {"region": {"type": "string", "required": true},
             "limit": {"type": "number", "required": false},
             "include_refunded": {"type": "boolean", "required": false}}
            """;

    private static void assertInvalid(Throwable throwable) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_TOOL_ARGUMENT_INVALID.getCode());
    }

    @Test
    void declaresParameterSurfaceAndRequiredNames() {
        AiToolInputSchema schema = AiToolInputSchema.parse(SCHEMA);

        assertThat(schema.names()).containsExactlyInAnyOrder("region", "limit", "include_refunded");
        assertThat(schema.requiredNames()).containsExactly("region");
        assertThat(schema.parameter("region").type()).isEqualTo("string");
        assertThat(schema.canonicalJson()).contains("\"region\":{\"type\":\"string\",\"required\":true}");
    }

    @Test
    void validatesArgumentsAndNormalizesTypes() {
        AiToolInputSchema schema = AiToolInputSchema.parse(SCHEMA);

        Map<String, Object> validated =
                schema.validateArguments(Map.of("region", "EAST", "limit", "10", "include_refunded", "true"));

        assertThat(validated).containsEntry("region", "EAST").containsEntry("include_refunded", true);
        assertThat((BigDecimal) validated.get("limit")).isEqualByComparingTo(new BigDecimal("10"));
        // 可选参数缺省时不出现（执行层按未传处理）
        assertThat(schema.validateArguments(Map.of("region", "EAST"))).containsOnlyKeys("region");
    }

    @Test
    void rejectsForgedArgumentsMissingRequiredAndTypeMismatch() {
        AiToolInputSchema schema = AiToolInputSchema.parse(SCHEMA);

        // 伪造参数（未声明）
        assertThatThrownBy(() -> schema.validateArguments(Map.of("region", "EAST", "admin", "true")))
                .as("伪造参数必须被拒绝，而不是忽略")
                .satisfies(AiToolInputSchemaTest::assertInvalid);
        // 必填缺失
        assertThatThrownBy(() -> schema.validateArguments(Map.of("limit", 1)))
                .satisfies(AiToolInputSchemaTest::assertInvalid);
        // 类型不符
        assertThatThrownBy(() -> schema.validateArguments(Map.of("region", 5)))
                .satisfies(AiToolInputSchemaTest::assertInvalid);
        assertThatThrownBy(() -> schema.validateArguments(Map.of("region", "EAST", "limit", "abc")))
                .satisfies(AiToolInputSchemaTest::assertInvalid);
        assertThatThrownBy(() -> schema.validateArguments(Map.of("region", "EAST", "include_refunded", "maybe")))
                .satisfies(AiToolInputSchemaTest::assertInvalid);
        assertThatThrownBy(() -> schema.validateArguments(null)).satisfies(AiToolInputSchemaTest::assertInvalid);
    }

    @Test
    void rejectsMalformedSchemas() {
        for (String schema : List.of(
                "",
                "not-json",
                "{}",
                "[1,2]",
                "{\"region\": \"string\"}",
                "{\"Region\": {\"type\": \"string\"}}",
                "{\"region\": {\"type\": \"sql\"}}",
                "{\"region\": {\"type\": \"string\", \"script\": \"x\"}}",
                "{\"region\": {\"required\": true}}")) {
            assertThatThrownBy(() -> AiToolInputSchema.parse(schema))
                    .as("非法 schema 必须被拒绝：%s", schema)
                    .satisfies(AiToolInputSchemaTest::assertInvalid);
        }
        assertThatThrownBy(() -> AiToolInputSchema.parse(null)).satisfies(AiToolInputSchemaTest::assertInvalid);
    }

    @Test
    void policyDefaultsToDenyAndToolTypeDefaultsToWrite() {
        assertThat(AiToolPolicy.parse(null)).isEqualTo(AiToolPolicy.DENY);
        assertThat(AiToolPolicy.parse("  ")).isEqualTo(AiToolPolicy.DENY);
        assertThat(AiToolPolicy.parse("auto")).isEqualTo(AiToolPolicy.AUTO);
        assertThat(AiToolPolicy.parse("CONFIRM")).isEqualTo(AiToolPolicy.CONFIRM);
        assertThat(AiToolPolicy.parse("ALLOW")).as("未知政策按最严格处理").isEqualTo(AiToolPolicy.DENY);
        assertThat(AiToolPolicy.names()).containsExactly("AUTO", "CONFIRM", "DENY");

        assertThat(AiToolPolicy.ToolType.parse("READ")).isEqualTo(AiToolPolicy.ToolType.READ);
        assertThat(AiToolPolicy.ToolType.parse(null)).isEqualTo(AiToolPolicy.ToolType.WRITE);
        assertThat(AiToolPolicy.ToolType.parse("EXEC")).as("未知类型按写处理（更严格）").isEqualTo(AiToolPolicy.ToolType.WRITE);
        assertThatCode(() -> AiToolInputSchema.parse(SCHEMA)).doesNotThrowAnyException();
        assertThat(AiToolInputSchema.MAX_PARAMETERS).isEqualTo(32);
        assertThat(new ErrorCode(1, "x")).isNotNull();
    }
}
