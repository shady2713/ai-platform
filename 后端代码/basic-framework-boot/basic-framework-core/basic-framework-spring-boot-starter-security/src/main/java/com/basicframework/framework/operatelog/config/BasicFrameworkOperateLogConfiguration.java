package com.basicframework.framework.operatelog.config;

import com.basicframework.framework.operatelog.core.service.LogRecordServiceImpl;
import com.basicframework.module.system.api.logger.OperateLogCommonApi;
import com.mzt.logapi.service.ILogRecordService;
import com.mzt.logapi.starter.annotation.EnableLogRecord;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 操作日志配置类
 *
 */
@EnableLogRecord(tenant = "") // 第三方库必填参数，框架未使用多租户
@AutoConfiguration
public class BasicFrameworkOperateLogConfiguration {

    @Bean
    @Primary
    public ILogRecordService iLogRecordServiceImpl(OperateLogCommonApi operateLogApi) {
        return new LogRecordServiceImpl(operateLogApi);
    }
}
