package com.basicframework.module.ai.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import org.junit.jupiter.api.Test;

/** S04 上下文预算：默认值、估算口径与非法值拒绝。 */
class AiContextBudgetTest {

    @Test
    void defaultsMatchPlatformMeteringEstimate() {
        AiContextBudget budget = AiContextBudget.defaults();

        assertThat(budget.getMaxMessages()).isEqualTo(AiContextBudget.DEFAULT_MAX_MESSAGES);
        assertThat(budget.getMaxTokens()).isEqualTo(AiContextBudget.DEFAULT_MAX_TOKENS);
        assertThat(budget.getCharsPerToken()).isEqualTo(AiContextBudget.DEFAULT_CHARS_PER_TOKEN);
        assertThat(AiContextBudget.of(null, null, null).getMaxTokens())
                .as("缺省字段使用平台默认值")
                .isEqualTo(AiContextBudget.DEFAULT_MAX_TOKENS);
    }

    @Test
    void estimatesTokensByCeiling() {
        AiContextBudget budget = AiContextBudget.of(10, 100, 4);

        assertThat(budget.estimateTokens(null)).isZero();
        assertThat(budget.estimateTokens("")).isZero();
        assertThat(budget.estimateTokens("abcd")).isEqualTo(1);
        assertThat(budget.estimateTokens("abcde")).as("向上取整，不低估").isEqualTo(2);
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> AiContextBudget.of(0, 100, 4))
                .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode()));
        assertThatThrownBy(() -> AiContextBudget.of(10, -1, 4))
                .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode()));
        assertThatThrownBy(() -> AiContextBudget.of(10, 100, 0))
                .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode()));
    }
}
