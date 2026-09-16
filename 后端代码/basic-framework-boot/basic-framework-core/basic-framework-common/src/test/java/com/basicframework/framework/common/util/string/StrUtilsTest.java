package com.basicframework.framework.common.util.string;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.aspectj.lang.JoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class StrUtilsTest {

    @Test
    void maxLength_preservesShortValuesAndLimitsLongValues() {
        assertThat(StrUtils.maxLength(null, 5)).isNull();
        assertThat(StrUtils.maxLength("abcd", 5)).isEqualTo("abcd");
        assertThat(StrUtils.maxLength("abcdef", 5)).isEqualTo("ab...");
        assertThatIllegalArgumentException().isThrownBy(() -> StrUtils.maxLength("value", 3));
    }

    @Test
    void joinMethodArgs_preservesPositionsAndExcludesWebObjects() {
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[] {1, null, new MockHttpServletRequest(), "visible"});

        assertThat(StrUtils.joinMethodArgs(joinPoint)).isEqualTo("1,,,visible");

        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        assertThat(StrUtils.joinMethodArgs(joinPoint)).isEmpty();
    }
}
