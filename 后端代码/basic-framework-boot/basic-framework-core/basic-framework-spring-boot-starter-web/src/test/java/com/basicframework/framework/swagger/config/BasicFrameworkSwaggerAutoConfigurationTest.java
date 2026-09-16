package com.basicframework.framework.swagger.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.web.config.WebProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;

class BasicFrameworkSwaggerAutoConfigurationTest {

    private final BasicFrameworkSwaggerAutoConfiguration configuration = new BasicFrameworkSwaggerAutoConfiguration();

    @Test
    void createApi_hasOneAuthorizationSecurityRequirementWithoutSampleCredential() {
        SwaggerProperties properties = new SwaggerProperties();
        properties.setTitle("Framework API");
        properties.setDescription("Management APIs");
        properties.setVersion("1.0");

        OpenAPI openApi = configuration.createApi(properties);

        assertThat(openApi.getInfo().getTitle()).isEqualTo("Framework API");
        assertThat(openApi.getComponents().getSecuritySchemes()).containsOnlyKeys(HttpHeaders.AUTHORIZATION);
        assertThat(openApi.getSecurity()).singleElement().satisfies(requirement -> assertThat(requirement)
                .containsOnlyKeys(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void groupedOpenApi_usesConfiguredPrefixesWithoutDoubleSlash() {
        WebProperties webProperties = new WebProperties();
        webProperties.setAdminApi(new WebProperties.Api("/management", "**.controller.admin.**"));
        webProperties.setAppApi(new WebProperties.Api("/client", "**.controller.app.**"));

        GroupedOpenApi all = configuration.allGroupedOpenApi(webProperties);
        GroupedOpenApi system = BasicFrameworkSwaggerAutoConfiguration.buildGroupedOpenApi("system", webProperties);

        assertThat(all.getPathsToMatch()).containsExactly("/management/**", "/client/**");
        assertThat(system.getPathsToMatch()).containsExactly("/management/system/**", "/client/system/**");
    }

    @Test
    void groupedOpenApi_addsCredentialHeaderWithoutDefaultValueAndStableOperationId() throws Exception {
        GroupedOpenApi groupedOpenApi =
                BasicFrameworkSwaggerAutoConfiguration.buildGroupedOpenApi("sample", new WebProperties());
        Method method = SampleController.class.getDeclaredMethod("list");
        HandlerMethod handlerMethod = new HandlerMethod(new SampleController(), method);
        Operation operation = new Operation();

        for (OperationCustomizer customizer : groupedOpenApi.getOperationCustomizers()) {
            operation = customizer.customize(operation, handlerMethod);
        }

        assertThat(operation.getOperationId()).isEqualTo("Sample_list");
        assertThat(operation.getParameters()).singleElement().satisfies(parameter -> {
            assertThat(parameter.getName()).isEqualTo(HttpHeaders.AUTHORIZATION);
            assertThat(parameter.getSchema().getDefault()).isNull();
        });
    }

    private static final class SampleController {

        void list() {}
    }
}
