package com.basicframework.module.infra.framework.file.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册文件外部存储清理策略。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FileDeletionProperties.class)
public class FileDeletionConfiguration {}
