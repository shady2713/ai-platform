package com.basicframework.module.system.framework.retention.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 system 数据保留策略。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SystemDataRetentionProperties.class)
public class SystemDataRetentionConfiguration {}
