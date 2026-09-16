package com.basicframework.module.infra.framework.retention.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 infra 运行日志保留策略。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InfraDataRetentionProperties.class)
public class InfraDataRetentionConfiguration {}
