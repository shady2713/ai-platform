package com.basicframework.module.system.framework.web.config;

import com.basicframework.framework.swagger.config.BasicFrameworkSwaggerAutoConfiguration;
import com.basicframework.framework.web.config.WebProperties;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * system 模块的 web 组件的 Configuration
 *
 */
@Configuration(proxyBeanMethods = false)
public class SystemWebConfiguration {

    /**
     * system 模块的 API 分组
     */
    @Bean
    public GroupedOpenApi systemGroupedOpenApi(WebProperties webProperties) {
        return BasicFrameworkSwaggerAutoConfiguration.buildGroupedOpenApi("system", webProperties);
    }
}
