package com.basicframework.module.system.framework.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.web.config.WebProperties;
import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;

/**
 * {@link SystemWebConfiguration} 单元测试
 *
 */
class SystemWebConfigurationTest {

    private final SystemWebConfiguration configuration = new SystemWebConfiguration();

    @Test
    void systemGroupedOpenApi_buildsGroupedApiFromWebProperties() {
        WebProperties webProperties = new WebProperties();

        GroupedOpenApi groupedOpenApi = configuration.systemGroupedOpenApi(webProperties);

        assertThat(groupedOpenApi.getGroup()).isEqualTo("system");
    }
}
