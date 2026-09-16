package com.basicframework.framework.xss.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.enums.WebFilterOrderEnum;
import com.basicframework.framework.xss.core.clean.XssCleaner;
import com.basicframework.framework.xss.core.filter.XssFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.util.AntPathMatcher;

class BasicFrameworkXssAutoConfigurationTest {

    private final BasicFrameworkXssAutoConfiguration configuration = new BasicFrameworkXssAutoConfiguration();

    @Test
    void beans_shareCleanerAndApplyExpectedFilterOrder() throws Exception {
        XssProperties properties = new XssProperties();
        XssCleaner cleaner = configuration.xssCleaner();
        AntPathMatcher pathMatcher = new AntPathMatcher();
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();

        configuration.xssJacksonCustomizer(properties, pathMatcher, cleaner).customize(builder);
        ObjectMapper objectMapper = builder.build();
        FilterRegistrationBean<XssFilter> filter = configuration.xssFilter(properties, pathMatcher, cleaner);

        assertThat(objectMapper.readValue("\"<script>alert(1)</script>safe\"", String.class))
                .isEqualTo("safe");
        assertThat(filter.getOrder()).isEqualTo(WebFilterOrderEnum.XSS_FILTER);
        assertThat(filter.getFilter()).isNotNull();
    }
}
