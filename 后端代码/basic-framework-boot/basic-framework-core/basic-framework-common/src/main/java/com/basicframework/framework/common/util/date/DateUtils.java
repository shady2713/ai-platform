package com.basicframework.framework.common.util.date;

import cn.hutool.core.date.LocalDateTimeUtil;
import java.time.LocalDateTime;

/** 项目实际使用的日期时间公共契约。 */
public final class DateUtils {

    public static final String FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND = "yyyy-MM-dd HH:mm:ss";

    private DateUtils() {}

    public static boolean isExpired(LocalDateTime time) {
        return LocalDateTime.now().isAfter(time);
    }

    public static boolean isToday(LocalDateTime date) {
        return LocalDateTimeUtil.isSameDay(date, LocalDateTime.now());
    }
}
