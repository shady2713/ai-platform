package com.basicframework.framework.security.core.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;

class TransmittableThreadLocalSecurityContextHolderStrategyTest {

    private final TransmittableThreadLocalSecurityContextHolderStrategy strategy =
            new TransmittableThreadLocalSecurityContextHolderStrategy();

    @AfterEach
    void clearContext() {
        strategy.clearContext();
    }

    @Test
    void getContext_lazilyCreatesAndRetainsOneContextPerThread() {
        SecurityContext first = strategy.getContext();

        assertThat(first).isSameAs(strategy.getContext());
        assertThat(first.getAuthentication()).isNull();
    }

    @Test
    void setAndClearContext_replaceThenRemoveAuthentication() {
        SecurityContext context = strategy.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("user", "N/A", java.util.List.of()));

        strategy.setContext(context);
        assertThat(strategy.getContext()).isSameAs(context);

        strategy.clearContext();
        assertThat(strategy.getContext()).isNotSameAs(context);
        assertThat(strategy.getContext().getAuthentication()).isNull();
    }

    @Test
    void setContext_rejectsNull() {
        assertThatThrownBy(() -> strategy.setContext(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-null SecurityContext");
    }
}
