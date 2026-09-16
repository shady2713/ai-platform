package com.basicframework.module.infra.framework.file.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.basicframework.module.infra.dal.mysql.file.FileContentMapper;
import com.basicframework.module.infra.framework.file.core.client.FileClientFactoryImpl;
import com.basicframework.module.infra.framework.file.core.utils.FileArchiveValidator;
import org.junit.jupiter.api.Test;

/**
 * {@link BasicFrameworkFileAutoConfiguration} 单元测试
 *
 */
class BasicFrameworkFileAutoConfigurationTest {

    private final BasicFrameworkFileAutoConfiguration configuration = new BasicFrameworkFileAutoConfiguration();

    @Test
    void fileClientFactory_buildsConcreteFactoryFromContentMapper() {
        assertThat(configuration.fileClientFactory(mock(FileContentMapper.class)))
                .isInstanceOf(FileClientFactoryImpl.class);
    }

    @Test
    void fileArchiveValidator_buildsFromSecurityProperties() {
        FileArchiveSecurityProperties properties = new FileArchiveSecurityProperties();

        assertThat(configuration.fileArchiveValidator(properties)).isInstanceOf(FileArchiveValidator.class);
    }
}
