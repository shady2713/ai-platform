package com.basicframework.framework.quartz.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Quartz Cron 工具单元测试
 *
 * 覆盖 cron 表达式合法性校验与下 N 次触发时间计算（严格递增、数量、
 * 非法表达式、无更多有效时间、边界参数）。
 */
class CronUtilsTest {

    @ParameterizedTest
    @ValueSource(strings = {"0 0 0 * * ?", "0 0/5 9-17 * * ?", "0 15 10 ? * 6L", "*/5 * * * * ?"})
    void isValid_acceptsStandardExpressions(String cron) {
        assertThat(CronUtils.isValid(cron)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"foo", "0 0", "", "60 0 * * * ?"})
    void isValid_rejectsMalformedExpressions(String cron) {
        assertThat(CronUtils.isValid(cron)).isFalse();
    }

    @Test
    void isValid_throwsOnNull() {
        // Quartz CronExpression.isValidExpression(null) 抛 IllegalArgumentException，钉住现状
        assertThatThrownBy(() -> CronUtils.isValid(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getNextTimes_returnsStrictlyIncreasingCount() {
        List<LocalDateTime> times = CronUtils.getNextTimes("0 0/5 * * * ?", 3);
        assertThat(times).hasSize(3);
        LocalDateTime previous = times.get(0);
        assertThat(previous).isAfter(LocalDateTime.now().minusMinutes(6));
        for (int i = 1; i < times.size(); i++) {
            assertThat(times.get(i)).isAfter(previous);
            previous = times.get(i);
        }
    }

    @Test
    void getNextTimes_zeroCountReturnsEmpty() {
        assertThat(CronUtils.getNextTimes("0 0 * * * ?", 0)).isEmpty();
    }

    @Test
    void getNextTimes_invalidCronThrowsIllegalArgument() {
        assertThatThrownBy(() -> CronUtils.getNextTimes("not-a-cron", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CRON 表达式格式无效")
                .hasCauseInstanceOf(java.text.ParseException.class);
    }

    @Test
    void getNextTimes_noFurtherValidTimeReturnsEmpty() {
        // 2 月 30 日不存在，后续无有效触发时间 → 返回空列表
        assertThat(CronUtils.getNextTimes("0 0 0 30 2 ?", 5)).isEmpty();
    }
}
