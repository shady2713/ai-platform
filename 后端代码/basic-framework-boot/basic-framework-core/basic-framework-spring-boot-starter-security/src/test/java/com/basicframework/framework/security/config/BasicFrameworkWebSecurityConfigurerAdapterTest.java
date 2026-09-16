package com.basicframework.framework.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.security.core.filter.TokenAuthenticationFilter;
import com.google.common.collect.Multimap;
import jakarta.annotation.security.PermitAll;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class BasicFrameworkWebSecurityConfigurerAdapterTest {

    @Test
    void permitAllAnnotationsRegisterOnlyExplicitlySupportedMethods() throws Exception {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(
                RequestMappingInfo.paths("/method-get")
                        .methods(RequestMethod.GET)
                        .build(),
                handler(new MethodPermitAllController(), "open"));
        handlers.put(
                RequestMappingInfo.paths("/class-post")
                        .methods(RequestMethod.POST)
                        .build(),
                handler(new ClassPermitAllController(), "open"));
        handlers.put(
                RequestMappingInfo.paths("/all-supported").build(), handler(new MethodPermitAllController(), "open"));
        handlers.put(
                RequestMappingInfo.paths("/closed").methods(RequestMethod.GET).build(),
                handler(new ClosedController(), "closed"));

        Multimap<HttpMethod, String> result = permitAllUrls(handlers);

        assertThat(result.get(HttpMethod.GET)).contains("/method-get", "/all-supported");
        assertThat(result.get(HttpMethod.POST)).contains("/class-post", "/all-supported");
        assertThat(result.get(HttpMethod.PUT)).containsExactly("/all-supported");
        assertThat(result.get(HttpMethod.DELETE)).containsExactly("/all-supported");
        assertThat(result.get(HttpMethod.HEAD)).containsExactly("/all-supported");
        assertThat(result.get(HttpMethod.PATCH)).containsExactly("/all-supported");
        assertThat(result.values()).doesNotContain("/closed");
    }

    @Test
    void permitAllAnnotationOnUnsupportedMethodFailsAtStartup() throws Exception {
        Map<RequestMappingInfo, HandlerMethod> handlers = Map.of(
                RequestMappingInfo.paths("/trace").methods(RequestMethod.TRACE).build(),
                handler(new MethodPermitAllController(), "open"));

        assertThatThrownBy(() -> permitAllUrls(handlers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("@PermitAll 不支持请求方法 TRACE");
    }

    private static Multimap<HttpMethod, String> permitAllUrls(Map<RequestMappingInfo, HandlerMethod> handlers) {
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(mapping.getHandlerMethods()).thenReturn(handlers);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean("requestMappingHandlerMapping")).thenReturn(mapping);
        BasicFrameworkWebSecurityConfigurerAdapter configurer = new BasicFrameworkWebSecurityConfigurerAdapter(
                mock(AuthenticationEntryPoint.class),
                mock(AccessDeniedHandler.class),
                mock(TokenAuthenticationFilter.class),
                List.of());
        return ReflectionTestUtils.invokeMethod(configurer, "getPermitAllUrlsFromAnnotations", applicationContext);
    }

    private static HandlerMethod handler(Object controller, String methodName) throws Exception {
        Method method = controller.getClass().getDeclaredMethod(methodName);
        return new HandlerMethod(controller, method);
    }

    private static final class MethodPermitAllController {

        @PermitAll
        public void open() {}
    }

    @PermitAll
    private static final class ClassPermitAllController {

        public void open() {}
    }

    private static final class ClosedController {

        public void closed() {}
    }
}
