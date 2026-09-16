package com.basicframework.framework.dict.config;

import com.basicframework.framework.dict.core.DictFrameworkUtils;
import com.basicframework.module.system.api.dict.DictDataCommonApi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(DictCacheProperties.class)
public class BasicFrameworkDictAutoConfiguration {

    @Bean
    @SuppressWarnings("InstantiationOfUtilityClass")
    public DictFrameworkUtils dictUtils(DictDataCommonApi dictDataApi, DictCacheProperties properties) {
        DictFrameworkUtils.init(dictDataApi, properties.getCacheRefreshAfterWrite());
        return new DictFrameworkUtils();
    }
}
