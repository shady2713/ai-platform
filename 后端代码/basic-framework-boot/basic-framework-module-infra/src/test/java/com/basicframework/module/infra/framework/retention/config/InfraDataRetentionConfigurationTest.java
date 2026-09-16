package com.basicframework.module.infra.framework.retention.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link InfraDataRetentionConfiguration} 单元测试
 *
 */
class InfraDataRetentionConfigurationTest {

    @Test
    void configuration_classIsInstantiable() {
        assertThat(new InfraDataRetentionConfiguration()).isNotNull();
    }
}
