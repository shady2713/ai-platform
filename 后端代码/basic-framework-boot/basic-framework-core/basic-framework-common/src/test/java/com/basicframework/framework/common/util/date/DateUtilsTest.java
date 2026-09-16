package com.basicframework.framework.common.util.date;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class DateUtilsTest {

    @Test
    void expirationAndDayChecks_handlePastFutureAndDayBoundaries() {
        LocalDateTime now = LocalDateTime.now();

        assertThat(DateUtils.isExpired(now.minusMinutes(1))).isTrue();
        assertThat(DateUtils.isExpired(now.plusMinutes(1))).isFalse();
        assertThat(DateUtils.isToday(now)).isTrue();
        assertThat(DateUtils.isToday(now.minusDays(1))).isFalse();
    }
}
