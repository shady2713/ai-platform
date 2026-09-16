package com.basicframework.framework.quartz.config;

import com.basicframework.framework.quartz.core.scheduler.SchedulerManager;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Scheduler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务 Configuration
 */
@AutoConfiguration
@EnableScheduling // 开启 Spring 自带的定时任务
@Slf4j
public class BasicFrameworkQuartzAutoConfiguration {

    @Bean
    public SchedulerManager schedulerManager(Optional<Scheduler> scheduler) {
        if (!scheduler.isPresent()) {
            log.info("[定时任务 - 已禁用][Quartz Scheduler 未配置]");
            return new SchedulerManager(null);
        }
        return new SchedulerManager(scheduler.get());
    }
}
