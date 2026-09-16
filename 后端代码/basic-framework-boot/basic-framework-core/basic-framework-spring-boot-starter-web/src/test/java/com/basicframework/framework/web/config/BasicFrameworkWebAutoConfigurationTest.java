package com.basicframework.framework.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basicframework.framework.common.enums.WebFilterOrderEnum;
import com.basicframework.framework.common.util.servlet.ClientIpResolver;
import com.basicframework.framework.web.core.filter.CacheRequestBodyFilter;
import com.basicframework.framework.web.core.handler.GlobalExceptionHandler;
import com.basicframework.framework.web.core.handler.TransportExceptionHandler;
import com.basicframework.module.infra.api.logger.ApiErrorLogCommonApi;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class BasicFrameworkWebAutoConfigurationTest {

    private final BasicFrameworkWebAutoConfiguration configuration = new BasicFrameworkWebAutoConfiguration();

    @Test
    void webMvcRegistrations_buildsOrderedControllerPrefixPredicates() {
        WebProperties properties = new WebProperties();
        properties.setAdminApi(new WebProperties.Api("/management", getClass().getPackageName()));
        properties.setAppApi(new WebProperties.Api("/client", "com.example.client"));

        RequestMappingHandlerMapping mapping =
                configuration.webMvcRegistrations(properties).getRequestMappingHandlerMapping();
        Map<String, Predicate<Class<?>>> pathPrefixes = mapping.getPathPrefixes();

        assertThat(pathPrefixes).containsOnlyKeys("/management", "/client");
        assertThat(pathPrefixes.get("/management").test(AdminController.class)).isTrue();
        assertThat(pathPrefixes.get("/management").test(PlainType.class)).isFalse();
        assertThat(pathPrefixes.get("/client").test(AdminController.class)).isFalse();
    }

    @Test
    void webMvcRegistrations_ignoresMissingApiConfiguration() {
        WebProperties properties = new WebProperties();
        properties.setAdminApi(null);
        properties.setAppApi(new WebProperties.Api("", "com.example.client"));

        RequestMappingHandlerMapping mapping =
                configuration.webMvcRegistrations(properties).getRequestMappingHandlerMapping();

        assertThat(mapping.getPathPrefixes()).isEmpty();
    }

    @Test
    void filterBeans_applyConfiguredLimitsAndFrameworkOrdering() {
        WebProperties properties = new WebProperties();
        properties.setCorsAllowedOrigins(java.util.List.of("https://admin.example.com"));
        properties.setRequestBodyCacheMaxBytes(2048);

        FilterRegistrationBean<CorsFilter> cors = configuration.corsFilterBean(properties);
        FilterRegistrationBean<CacheRequestBodyFilter> bodyCache = configuration.requestBodyCacheFilter(properties);

        assertThat(cors.getOrder()).isEqualTo(WebFilterOrderEnum.CORS_FILTER);
        assertThat(cors.getFilter()).isNotNull();
        assertThat(bodyCache.getOrder()).isEqualTo(WebFilterOrderEnum.REQUEST_BODY_CACHE_FILTER);
        assertThat(bodyCache.getFilter()).isNotNull();
    }

    @Test
    void infrastructureBeans_areConstructedFromExplicitDependencies() {
        ApiErrorLogCommonApi errorLogApi = mock(ApiErrorLogCommonApi.class);

        assertThat(configuration.globalExceptionHandler(
                        "test-app", errorLogApi, configuration.transportExceptionHandler()))
                .isInstanceOf(GlobalExceptionHandler.class);
        assertThat(configuration.globalResponseBodyHandler()).isNotNull();
        assertThat(configuration.webFrameworkUtils(new WebProperties())).isNotNull();
        assertThat(configuration.restTemplate(new RestTemplateBuilder())).isNotNull();
    }

    @Test
    void clientIpResolver_seedsTrustedProxiesFromProperties() {
        WebProperties properties = new WebProperties();
        properties.setTrustedProxies(java.util.List.of("127.0.0.1"));

        assertThat(configuration.clientIpResolver(properties)).isNotNull();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void productionAutoConfigurationTranslatesTransportFailuresWithoutManualAdviceRegistration() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DispatcherServletAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class,
                        ValidationAutoConfiguration.class,
                        RestTemplateAutoConfiguration.class,
                        BasicFrameworkWebAutoConfiguration.class))
                .withUserConfiguration(TransportController.class)
                .withBean(ApiErrorLogCommonApi.class, () -> mock(ApiErrorLogCommonApi.class))
                .withPropertyValues("spring.application.name=web-contract-test")
                .run(context -> {
                    assertThat(context).hasSingleBean(TransportExceptionHandler.class);
                    assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                    var mvc = MockMvcBuilders.webAppContextSetup(context).build();
                    mvc.perform(get("/transport/value"))
                            .andExpect(status().isBadRequest())
                            .andExpect(jsonPath("$.code").value(400));
                    mvc.perform(get("/transport/value").param("id", "invalid"))
                            .andExpect(status().isBadRequest())
                            .andExpect(jsonPath("$.code").value(400));
                    mvc.perform(get("/transport/missing/contact@example.com/private-token-value"))
                            .andExpect(status().isNotFound())
                            .andExpect(jsonPath("$.code").value(404))
                            .andExpect(jsonPath("$.msg").value("请求未找到"));
                    mvc.perform(post("/transport/value"))
                            .andExpect(status().isMethodNotAllowed())
                            .andExpect(jsonPath("$.code").value(405));
                    mvc.perform(post("/transport/conflict"))
                            .andExpect(status().isConflict())
                            .andExpect(jsonPath("$.code").value(409))
                            .andExpect(jsonPath("$.msg").value("数据已存在，请检查后重试"));
                });
    }

    @RestController
    static class TransportController {
        @GetMapping("/transport/value")
        public Long value(@RequestParam("id") Long id) {
            return id;
        }

        @org.springframework.web.bind.annotation.PostMapping("/transport/conflict")
        public void conflict() {
            throw new DuplicateKeyException("INSERT private-contact-value uk_private_index");
        }
    }

    @RestController
    private static final class AdminController {}

    private static final class PlainType {}
}
