package com.basicframework.module.infra.framework.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.web.config.WebProperties;
import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;

/**
 * {@link InfraWebConfiguration} 单元测试
 *
 */
class InfraWebConfigurationTest {

    private final InfraWebConfiguration configuration = new InfraWebConfiguration();

    @Test
    void infraGroupedOpenApi_buildsGroupedApiFromWebProperties() {
        WebProperties webProperties = new WebProperties();

        GroupedOpenApi groupedOpenApi = configuration.infraGroupedOpenApi(webProperties);

        assertThat(groupedOpenApi.getGroup()).isEqualTo("infra");
    }
}
