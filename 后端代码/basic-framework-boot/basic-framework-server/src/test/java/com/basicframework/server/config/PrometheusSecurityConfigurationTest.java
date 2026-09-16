package com.basicframework.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basicframework.module.system.service.metrics.SecuritySignalMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.servlet.ServletManagementContextAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PrometheusSecurityConfigurationTest {

    private static final String TEST_TOKEN = "ab".repeat(32);
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SecurityAutoConfiguration.class,
                    WebMvcAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,
                    ServletManagementContextAutoConfiguration.class,
                    ValidationAutoConfiguration.class,
                    MetricsAutoConfiguration.class,
                    PrometheusMetricsExportAutoConfiguration.class,
                    ManagementContextAutoConfiguration.class,
                    EndpointAutoConfiguration.class,
                    WebEndpointAutoConfiguration.class))
            .withUserConfiguration(PrometheusSecurityConfiguration.class, BusinessSecurity.class)
            .withPropertyValues(
                    "management.endpoints.web.exposure.include=prometheus",
                    "management.endpoints.web.base-path=/observe");

    @Test
    void enabledExporter_requiresIndependentTokenAndKeepsBusinessRequestsDenied() {
        runner.withPropertyValues(
                        "basic-framework.monitoring.prometheus.enabled=true",
                        "basic-framework.monitoring.prometheus.token=" + TEST_TOKEN)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SecuritySignalMetrics metrics = new SecuritySignalMetrics(context.getBean(MeterRegistry.class));
                    metrics.recordRefreshReplay();
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                            .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                            .build();
                    mvc.perform(get("/observe/prometheus")).andExpect(status().isUnauthorized());
                    mvc.perform(get("/observe/prometheus").header("Authorization", "Bearer wrong"))
                            .andExpect(status().isUnauthorized());
                    mvc.perform(get("/observe/prometheus").header("Authorization", "Bearer " + TEST_TOKEN))
                            .andExpect(status().isOk())
                            .andExpect(content()
                                    .string(org.hamcrest.Matchers.containsString(
                                            "basic_framework_auth_refresh_replays_total 1.0")));
                    mvc.perform(post("/observe/prometheus").header("Authorization", "Bearer " + TEST_TOKEN))
                            .andExpect(status().isForbidden());
                    mvc.perform(get("/business").header("Authorization", "Bearer " + TEST_TOKEN))
                            .andExpect(status().isForbidden());
                });
    }

    @Test
    void exporterIsClosedByDefaultEvenWithAConfiguredToken() {
        runner.withPropertyValues("basic-framework.monitoring.prometheus.token=" + TEST_TOKEN)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                            .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                            .build();
                    mvc.perform(get("/observe/prometheus").header("Authorization", "Bearer " + TEST_TOKEN))
                            .andExpect(status().isUnauthorized());
                });
    }

    @Test
    void enabledExporterRejectsMissingOrMalformedCredentialsAtStartup() {
        for (String token : new String[] {"", "short", " ", "z".repeat(64)}) {
            runner.withPropertyValues(
                            "basic-framework.monitoring.prometheus.enabled=true",
                            "basic-framework.monitoring.prometheus.token=" + token)
                    .run(context -> assertThat(context).hasFailed());
        }
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BusinessSecurity {
        @Bean
        @Order(1)
        SecurityFilterChain businessSecurity(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(config -> config.anyRequest().denyAll())
                    .build();
        }
    }
}
