package com.basicframework.module.ai.service.query.planner;

import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 查询规划器的时钟装配（D05）。
 *
 * <p>为什么显式给一个 Bean 而不是在代码里 `Instant.now()`：时间窗口的合法性判定
 * （倒序、过宽、超出可信当前时间）必须是**可复现**的——测试注入固定时钟即可复现任意时刻的结论，
 * 生产用 UTC 系统时钟。放在本包内，避免修改全局配置。
 */
@Configuration
public class AiQueryPlannerClockConfiguration {

    /** 查询规划使用的时钟（UTC，便于与带偏移的计划时间比较）。 */
    @Bean
    public Clock aiQueryPlannerClock() {
        return Clock.system(ZoneOffset.UTC);
    }
}
