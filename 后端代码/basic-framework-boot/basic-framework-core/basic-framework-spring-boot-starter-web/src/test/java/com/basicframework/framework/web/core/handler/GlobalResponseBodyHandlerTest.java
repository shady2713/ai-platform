package com.basicframework.framework.web.core.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@link GlobalResponseBodyHandler#supports} return-type matching, including the
 * ResponseEntity&lt;CommonResult&gt; shape produced by {@link GlobalExceptionHandler} (ADR 0003).
 */
class GlobalResponseBodyHandlerTest {

    private final GlobalResponseBodyHandler advice = new GlobalResponseBodyHandler();

    @Test
    void supports_commonResultAndResponseEntityOfCommonResult() throws Exception {
        assertThat(supports("commonResult")).isTrue();
        assertThat(supports("responseEntityOfCommonResult")).isTrue();
        assertThat(supports("responseEntityOfRawCommonResult")).isTrue();
    }

    @Test
    void supports_rejectsUnrelatedReturnTypes() throws Exception {
        assertThat(supports("responseEntityOfString")).isFalse();
        assertThat(supports("plainString")).isFalse();
    }

    @Test
    void supports_rejectsParametersWithoutAMethod() throws Exception {
        MethodParameter constructorParameter = new MethodParameter(FakeController.class.getDeclaredConstructor(), -1);

        assertThat(advice.supports(constructorParameter, null)).isFalse();
    }

    @Test
    void beforeBodyWrite_recordsTheExactCommonResultWithoutChangingIt() throws Exception {
        CommonResult<String> result = CommonResult.success("value");
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        ServletServerHttpRequest request = new ServletServerHttpRequest(servletRequest);
        Method method = FakeController.class.getMethod("commonResult");

        Object returned = advice.beforeBodyWrite(
                result, new MethodParameter(method, -1), null, null, request, mock(ServerHttpResponse.class));

        assertThat(returned).isSameAs(result);
        assertThat(WebFrameworkUtils.getCommonResult(servletRequest)).isSameAs(result);
    }

    private boolean supports(String methodName) throws Exception {
        Method method = FakeController.class.getMethod(methodName);
        return advice.supports(new MethodParameter(method, -1), null);
    }

    @SuppressWarnings("unused")
    static class FakeController {

        public CommonResult<?> commonResult() {
            return null;
        }

        public ResponseEntity<CommonResult<?>> responseEntityOfCommonResult() {
            return null;
        }

        @SuppressWarnings("rawtypes")
        public ResponseEntity<CommonResult> responseEntityOfRawCommonResult() {
            return null;
        }

        public ResponseEntity<String> responseEntityOfString() {
            return null;
        }

        public String plainString() {
            return null;
        }
    }
}
