package com.basicframework.module.system.framework.retention.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link SystemDataRetentionConfiguration} 单元测试
 *
 */
class SystemDataRetentionConfigurationTest {

    @Test
    void configuration_classIsInstantiable() {
        assertThat(new SystemDataRetentionConfiguration()).isNotNull();
    }
}
