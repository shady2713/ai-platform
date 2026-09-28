package com.basicframework.module.ai.service.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** X06 写绑定声明：写工具必须声明必填字符串幂等键与登记核对查询（读工具不得声明写绑定）。 */
class AiToolWriteBindingTest {

    private static final String INPUT_SCHEMA =
            """
            {"payment_no": {"type": "string", "required": true},
             "amount": {"type": "number", "required": true}}
            """;

    private static String outputSchema(String writeJson) {
        return "{\"columns\":[],\"write\":" + writeJson + "}";
    }

    private static final String VALID_WRITE =
            "{\"idempotencyParam\":\"payment_no\",\"reconcileOperation\":\"getPayment\"}";

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void writeVersionRequiresMandatoryStringIdempotencyParameter() {
        AiToolWriteBinding binding =
                AiToolWriteBinding.parse(outputSchema(VALID_WRITE), "WRITE", "createPayment", INPUT_SCHEMA);
        assertThat(binding.idempotencyParam()).isEqualTo("payment_no");
        assertThat(binding.reconcileOperation()).isEqualTo("getPayment");
        assertThat(binding.reconcileParam()).as("核对参数缺省与幂等键同名").isEqualTo("payment_no");
        assertThat(binding.canonicalJson())
                .isEqualTo("{\"idempotencyParam\":\"payment_no\",\"reconcileOperation\":\"getPayment\","
                        + "\"reconcileParam\":\"payment_no\"}");

        // 幂等键必须是输入 schema 声明的必填 string 参数
        assertThatThrownBy(() -> AiToolWriteBinding.parse(
                        outputSchema("{\"idempotencyParam\":\"amount\",\"reconcileOperation\":\"getPayment\"}"),
                        "WRITE",
                        "createPayment",
                        INPUT_SCHEMA))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_INVALID));
        assertThatThrownBy(() -> AiToolWriteBinding.parse(
                        outputSchema("{\"idempotencyParam\":\"missing\",\"reconcileOperation\":\"getPayment\"}"),
                        "WRITE",
                        "createPayment",
                        INPUT_SCHEMA))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_INVALID));
        // 声明缺项 / 未知键 / 非对象 / 缺少 write 段
        for (String invalidWrite : java.util.List.of(
                "{\"idempotencyParam\":\"payment_no\"}",
                "{\"idempotencyParam\":\"payment_no\",\"reconcileOperation\":\"getPayment\",\"extra\":1}",
                "\"getPayment\"",
                "null")) {
            assertThatThrownBy(() -> AiToolWriteBinding.parse(
                            outputSchema(invalidWrite), "WRITE", "createPayment", INPUT_SCHEMA))
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_INVALID));
        }
    }

    @Test
    void reconcileOperationMustDifferFromWriteOperationAndReadVersionCannotDeclareWriteBinding() {
        assertThatThrownBy(
                        () -> AiToolWriteBinding.parse(outputSchema(VALID_WRITE), "WRITE", "getPayment", INPUT_SCHEMA))
                .as("写操作不能当自己的核对证据")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_INVALID));

        assertThatThrownBy(() -> AiToolWriteBinding.parse(outputSchema(VALID_WRITE), "READ", "getOrders", INPUT_SCHEMA))
                .as("读工具声明写绑定：语义矛盾")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_INVALID));

        assertThat(AiToolWriteBinding.parse("{\"columns\":[]}", "READ", "getOrders", INPUT_SCHEMA))
                .as("读工具不声明写绑定是合法形态")
                .isNull();
    }

    @Test
    void idempotencyKeyComesFromFrozenArgumentsAndIsBounded() {
        AiToolWriteBinding binding =
                AiToolWriteBinding.parse(outputSchema(VALID_WRITE), "WRITE", "createPayment", INPUT_SCHEMA);

        assertThat(binding.idempotencyKeyOf(Map.of("payment_no", " P-1 ", "amount", 10)))
                .isEqualTo("P-1");
        assertThat(binding.matches("payment_no", "getPayment")).isTrue();
        assertThat(binding.matches("payment_no", "getOtherPayment")).isFalse();
        assertThat(binding.matches("other_key", "getPayment")).isFalse();
        assertThat(binding.declaredParameterNames()).containsExactlyInAnyOrder("payment_no");

        for (Map<String, Object> invalid :
                java.util.List.<Map<String, Object>>of(Map.of("amount", 10), Map.of("payment_no", " "))) {
            assertThatThrownBy(() -> binding.idempotencyKeyOf(invalid))
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_INVALID));
        }
        assertThatThrownBy(() -> binding.idempotencyKeyOf(Map.of("payment_no", "x".repeat(129))))
                .as("幂等键超长：拒绝而不是截断（截断会撞键）")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_INVALID));
    }
}
