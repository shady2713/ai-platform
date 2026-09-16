package com.basicframework.framework.datasource.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link BasicFrameworkDataSourceAutoConfiguration} 单元测试
 *
 */
class BasicFrameworkDataSourceAutoConfigurationTest {

    @Test
    void configuration_classIsInstantiable() {
        assertThat(new BasicFrameworkDataSourceAutoConfiguration()).isNotNull();
    }
}
