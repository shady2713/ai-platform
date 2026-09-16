package com.basicframework.framework.apilog.core.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mockStatic;

import com.basicframework.framework.common.util.spring.SpringUtils;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class ApiAccessLogInterceptorTest {

    private final ApiAccessLogInterceptor interceptor = new ApiAccessLogInterceptor();

    @Test
    void preHandle_preservesHandlerMetadataInProductionWithoutDebugState() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin-api/test");
        HandlerMethod handlerMethod = handlerMethod();

        try (MockedStatic<SpringUtils> springUtils = mockStatic(SpringUtils.class)) {
            springUtils.when(SpringUtils::isProd).thenReturn(true);

            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethod))
                    .isTrue();
            assertThat(request.getAttribute(ApiAccessLogInterceptor.ATTRIBUTE_HANDLER_METHOD))
                    .isSameAs(handlerMethod);
            assertThatCode(() ->
                            interceptor.afterCompletion(request, new MockHttpServletResponse(), handlerMethod, null))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void preHandle_tracksNonProductionRequestAndCompletesForNonControllerHandler() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin-api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<SpringUtils> springUtils = mockStatic(SpringUtils.class)) {
            springUtils.when(SpringUtils::isProd).thenReturn(false);

            assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
            assertThat(request.getAttribute(ApiAccessLogInterceptor.ATTRIBUTE_HANDLER_METHOD))
                    .isNull();
            assertThatCode(() -> interceptor.afterCompletion(request, response, new Object(), null))
                    .doesNotThrowAnyException();
        }
    }

    private static HandlerMethod handlerMethod() throws NoSuchMethodException {
        Method method = SampleController.class.getDeclaredMethod("list");
        return new HandlerMethod(new SampleController(), method);
    }

    private static final class SampleController {

        void list() {}
    }
}
