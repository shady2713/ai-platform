package com.basicframework.module.infra.framework.file.config;

import com.basicframework.module.infra.dal.mysql.file.FileContentMapper;
import com.basicframework.module.infra.framework.file.core.client.FileClientFactory;
import com.basicframework.module.infra.framework.file.core.client.FileClientFactoryImpl;
import com.basicframework.module.infra.framework.file.core.utils.FileArchiveValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文件配置类
 *
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({FileArchiveSecurityProperties.class, FilePresignedUploadProperties.class})
public class BasicFrameworkFileAutoConfiguration {

    @Bean
    public FileClientFactory fileClientFactory(FileContentMapper fileContentMapper) {
        return new FileClientFactoryImpl(fileContentMapper);
    }

    @Bean
    public FileArchiveValidator fileArchiveValidator(FileArchiveSecurityProperties properties) {
        return new FileArchiveValidator(properties);
    }
}
