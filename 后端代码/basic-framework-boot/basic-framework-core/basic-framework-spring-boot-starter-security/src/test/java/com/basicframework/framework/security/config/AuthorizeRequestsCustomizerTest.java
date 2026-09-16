package com.basicframework.framework.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.web.config.WebProperties;
import org.junit.jupiter.api.Test;

class AuthorizeRequestsCustomizerTest {

    @Test
    void buildsPathsFromOwningWebPrefixesAndUsesDefaultOrder() {
        WebProperties properties = new WebProperties();
        properties.setAdminApi(new WebProperties.Api("/control-api", "**.controller.admin.**"));
        properties.setAppApi(new WebProperties.Api("/client-api", "**.controller.app.**"));
        FixtureCustomizer customizer = new FixtureCustomizer(properties);

        assertThat(customizer.adminPath("/users")).isEqualTo("/control-api/users");
        assertThat(customizer.appPath("/profile")).isEqualTo("/client-api/profile");
        assertThat(customizer.getOrder()).isZero();
    }

    private static final class FixtureCustomizer extends AuthorizeRequestsCustomizer {

        private FixtureCustomizer(WebProperties webProperties) {
            super(webProperties);
        }

        private String adminPath(String path) {
            return buildAdminApi(path);
        }

        private String appPath(String path) {
            return buildAppApi(path);
        }

        @Override
        public void customize(
                org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<
                                        org.springframework.security.config.annotation.web.builders.HttpSecurity>
                                .AuthorizationManagerRequestMatcherRegistry
                        registry) {
            // Test fixture: path helpers and ordering are the contract under test.
        }
    }
}
