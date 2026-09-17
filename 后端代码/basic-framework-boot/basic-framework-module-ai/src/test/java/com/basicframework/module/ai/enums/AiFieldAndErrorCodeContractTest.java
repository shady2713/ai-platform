package com.basicframework.module.ai.enums;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.exception.ErrorCode;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * AI 错误码与字段规则契约：区间前缀、编号唯一性、双端正则一致性
 * （与 {@code docs/contracts/field-catalog.yaml} 的登记值对齐）。
 */
class AiFieldAndErrorCodeContractTest {

    @Test
    void everyAiErrorCodeStaysInsideTheAiRange() throws IllegalAccessException {
        Set<Integer> codes = new HashSet<>();
        for (Field field : AiErrorCodeConstants.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != ErrorCode.class) {
                continue;
            }
            ErrorCode code = (ErrorCode) field.get(null);
            assertThat(String.valueOf(code.getCode())).startsWith(String.valueOf(AiErrorCodeRanges.AI_PREFIX));
            // 唯一性：同一编号不允许登记两次
            assertThat(codes.add(code.getCode()))
                    .as("错误码 %s 重复", code.getCode())
                    .isTrue();
        }
        assertThat(codes).hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    void domainRangesStayDistinct() {
        Set<Integer> domains = Set.of(
                AiErrorCodeRanges.DOMAIN_COMMON,
                AiErrorCodeRanges.DOMAIN_MODEL,
                AiErrorCodeRanges.DOMAIN_IDENTITY,
                AiErrorCodeRanges.DOMAIN_RUN,
                AiErrorCodeRanges.DOMAIN_KNOWLEDGE,
                AiErrorCodeRanges.DOMAIN_CONNECTOR,
                AiErrorCodeRanges.DOMAIN_REPORT);

        assertThat(domains).hasSize(7);
        assertThat(domains).allSatisfy(domain -> assertThat(String.valueOf(domain))
                .startsWith(String.valueOf(AiErrorCodeRanges.AI_PREFIX)));
    }

    @Test
    void fieldPatternsMatchTheFrozenCatalogContract() {
        // 与 docs/contracts/field-catalog.yaml 登记值逐字一致；两侧改动能被漂移门禁识别
        assertThat(AiFieldRules.PATTERN_SERVICE_KEY.pattern()).isEqualTo("^svc_[A-Za-z0-9_-]{3,35}$");
        assertThat(AiFieldRules.PATTERN_CONVERSATION_KEY.pattern()).isEqualTo("^conv_[A-Za-z0-9_-]{3,35}$");
        assertThat(AiFieldRules.PATTERN_RUN_KEY.pattern()).isEqualTo("^run_[A-Za-z0-9_-]{3,35}$");
        assertThat(AiFieldRules.PATTERN_SERVICE_KEY.matcher("svc_demo1").matches())
                .isTrue();
        assertThat(AiFieldRules.PATTERN_RUN_KEY.matcher("run_abc").matches()).isTrue();
    }

    @Test
    void fieldValidatorsEnforceLengthsAndPrefixes() {
        assertThat(AiFieldRules.isValidServiceKey("svc_demo1")).isTrue();
        assertThat(AiFieldRules.isValidServiceKey("demo1")).isFalse();
        assertThat(AiFieldRules.isValidServiceKey(null)).isFalse();
        assertThat(AiFieldRules.isValidServiceKey("svc_" + "x".repeat(36))).isFalse();

        assertThat(AiFieldRules.isValidConversationKey("conv_abc")).isTrue();
        assertThat(AiFieldRules.isValidConversationKey("run_abc")).isFalse();

        assertThat(AiFieldRules.isValidRunKey("run_abc")).isTrue();
        assertThat(AiFieldRules.isValidRunKey("run")).isFalse();

        assertThat(AiFieldRules.isValidIdempotencyKey("0123456789abcdef")).isTrue();
        assertThat(AiFieldRules.isValidIdempotencyKey("short")).isFalse();
        assertThat(AiFieldRules.isValidIdempotencyKey(null)).isFalse();
        assertThat(AiFieldRules.isValidIdempotencyKey("x".repeat(129))).isFalse();

        assertThat(AiFieldRules.isValidMessage("你好")).isTrue();
        assertThat(AiFieldRules.isValidMessage("   ")).isFalse();
        assertThat(AiFieldRules.isValidMessage("x".repeat(16_001))).isFalse();
    }
}
