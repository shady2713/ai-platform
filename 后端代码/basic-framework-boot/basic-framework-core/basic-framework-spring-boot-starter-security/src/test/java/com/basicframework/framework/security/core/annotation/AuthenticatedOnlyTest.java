package com.basicframework.framework.security.core.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;

class AuthenticatedOnlyTest {

    @Test
    void shouldRequireAuthenticatedIdentity() {
        PreAuthorize preAuthorize =
                AnnotatedElementUtils.findMergedAnnotation(AuthenticatedOnly.class, PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("isAuthenticated()");
    }
}
