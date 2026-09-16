package com.basicframework.framework.apilog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.basicframework.framework.apilog.core.filter.ApiAccessLogFilter;
import com.basicframework.framework.apilog.core.interceptor.ApiAccessLogInterceptor;
import com.basicframework.framework.common.enums.WebFilterOrderEnum;
import com.basicframework.framework.web.config.WebProperties;
import com.basicframework.module.infra.api.logger.ApiAccessLogCommonApi;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

class BasicFrameworkApiLogAutoConfigurationTest {

    private final BasicFrameworkApiLogAutoConfiguration configuration = new BasicFrameworkApiLogAutoConfiguration();

    @Test
    void apiAccessLogFilter_usesFrameworkOrderAndExplicitDependencies() {
        FilterRegistrationBean<ApiAccessLogFilter> bean =
                configuration.apiAccessLogFilter(new WebProperties(), "test-app", mock(ApiAccessLogCommonApi.class));

        assertThat(bean.getOrder()).isEqualTo(WebFilterOrderEnum.API_ACCESS_LOG_FILTER);
        assertThat(bean.getFilter()).isNotNull();
    }

    @Test
    void addInterceptors_registersAccessLogInterceptor() {
        ExposedInterceptorRegistry registry = new ExposedInterceptorRegistry();

        configuration.addInterceptors(registry);

        assertThat(registry.interceptors()).singleElement().isInstanceOf(ApiAccessLogInterceptor.class);
    }

    private static final class ExposedInterceptorRegistry extends InterceptorRegistry {

        List<Object> interceptors() {
            return getInterceptors();
        }
    }
}
