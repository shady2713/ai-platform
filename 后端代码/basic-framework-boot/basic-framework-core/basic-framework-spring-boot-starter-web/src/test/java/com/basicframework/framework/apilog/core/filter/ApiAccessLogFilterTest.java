package com.basicframework.framework.apilog.core.filter;

import static com.basicframework.framework.apilog.core.interceptor.ApiAccessLogInterceptor.ATTRIBUTE_HANDLER_METHOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.basicframework.framework.apilog.core.annotation.ApiAccessLog;
import com.basicframework.framework.apilog.core.enums.OperateTypeEnum;
import com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.web.config.WebProperties;
import com.basicframework.framework.web.core.handler.TransportExceptionHandler;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import com.basicframework.module.infra.api.logger.ApiAccessLogCommonApi;
import com.basicframework.module.infra.api.logger.dto.ApiAccessLogCreateReqDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.ServletException;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class ApiAccessLogFilterTest {

    @BeforeEach
    void setUpWebProperties() {
        new WebFrameworkUtils(new WebProperties());
    }

    @Test
    void doFilter_sanitizesDefaultAndEndpointSpecificKeys() throws Exception {
        ApiAccessLogCommonApi apiAccessLogApi = mock(ApiAccessLogCommonApi.class);
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test-app", apiAccessLogApi);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin-api/test");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(
                ("""
                        {
                          "oldPassword": "${TEST_SECRET}",
                          "API_SECRET": "${TEST_SECRET}",
                          "pin": "${TEST_SECRET}",
                          "name": "kept"
                        }
                        """)
                        .getBytes());
        WebFrameworkUtils.setLoginUserType(request, 1);
        Method endpoint = TestController.class.getDeclaredMethod("endpoint");
        request.setAttribute(ATTRIBUTE_HANDLER_METHOD, new HandlerMethod(new TestController(), endpoint));

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

        ArgumentCaptor<ApiAccessLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
        verify(apiAccessLogApi).createApiAccessLogAsync(captor.capture());
        assertThat(captor.getValue().getRequestParams())
                .doesNotContain("${TEST_SECRET}", "oldPassword", "API_SECRET", "pin")
                .contains("name", "kept");
    }

    @Test
    void doFilter_unhandledFailureStoresOnlySafeFixedMessage() throws Exception {
        ApiAccessLogCommonApi apiAccessLogApi = mock(ApiAccessLogCommonApi.class);
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test-app", apiAccessLogApi);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin-api/test");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    throw new ServletException("jdbc:mysql://secret-host/private_schema");
                }))
                .isInstanceOf(ServletException.class);

        ArgumentCaptor<ApiAccessLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
        verify(apiAccessLogApi).createApiAccessLogAsync(captor.capture());
        assertThat(captor.getValue().getResultCode())
                .isEqualTo(GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR.getCode());
        assertThat(captor.getValue().getResultMsg())
                .isEqualTo(GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR.getMsg())
                .doesNotContain("jdbc", "secret-host", "private_schema");
    }

    @Test
    void doFilter_recordsRouteTemplateInsteadOfDynamicPathValue() throws Exception {
        ApiAccessLogCommonApi apiAccessLogApi = mock(ApiAccessLogCommonApi.class);
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test-app", apiAccessLogApi);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin-api/files/contact@example.com");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/admin-api/files/{fileName}");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

        ArgumentCaptor<ApiAccessLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
        verify(apiAccessLogApi).createApiAccessLogAsync(captor.capture());
        assertThat(captor.getValue().getRequestUrl())
                .isEqualTo("/admin-api/files/{fileName}")
                .doesNotContain("contact@example.com");
    }

    @Test
    void notFoundResponsesAndAccessLogsDoNotReflectSensitivePaths() throws Exception {
        ApiAccessLogCommonApi apiAccessLogApi = mock(ApiAccessLogCommonApi.class);
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test-app", apiAccessLogApi);
        TransportExceptionHandler handler = new TransportExceptionHandler();
        String sensitivePath = "/admin-api/missing/contact@example.com/private-token-value";
        Logger logger = (Logger) LoggerFactory.getLogger(TransportExceptionHandler.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            for (boolean staticResource : new boolean[] {false, true}) {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", sensitivePath);
                var response = staticResource
                        ? handler.noResourceFoundExceptionHandler(
                                new NoResourceFoundException(HttpMethod.GET, sensitivePath))
                        : handler.noHandlerFoundExceptionHandler(
                                new NoHandlerFoundException("GET", sensitivePath, new HttpHeaders()));
                filter.doFilter(
                        request,
                        new MockHttpServletResponse(),
                        (req, res) -> WebFrameworkUtils.setCommonResult(request, response.getBody()));
                assertThat(response.getStatusCode().value()).isEqualTo(404);
                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().getMsg()).isEqualTo(GlobalErrorCodeConstants.NOT_FOUND.getMsg());
            }
        } finally {
            logger.setLevel(previousLevel);
            logger.detachAppender(appender);
            appender.stop();
        }
        ArgumentCaptor<ApiAccessLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
        verify(apiAccessLogApi, times(2)).createApiAccessLogAsync(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(accessLog -> {
            assertThat(accessLog.getResultCode()).isEqualTo(GlobalErrorCodeConstants.NOT_FOUND.getCode());
            assertThat(accessLog.getResultMsg()).isEqualTo(GlobalErrorCodeConstants.NOT_FOUND.getMsg());
            assertThat(accessLog.getRequestUrl()).isEqualTo("[unmapped]");
            assertThat(accessLog.getResponseBody()).isNull();
        });
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(
                        message -> message.contains("contact@example.com") || message.contains("private-token-value"));
    }

    @Test
    void doFilter_disabledEndpointDoesNotWriteAnAccessLog() throws Exception {
        ApiAccessLogCommonApi apiAccessLogApi = mock(ApiAccessLogCommonApi.class);
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test-app", apiAccessLogApi);
        MockHttpServletRequest request = requestFor("GET", "disabledEndpoint");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

        verifyNoInteractions(apiAccessLogApi);
    }

    @Test
    void doFilter_explicitAuditMetadataControlsPayloadAndOperationClassification() throws Exception {
        ApiAccessLogCommonApi apiAccessLogApi = mock(ApiAccessLogCommonApi.class);
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test-app", apiAccessLogApi);
        MockHttpServletRequest request = requestFor("PUT", "explicitAuditEndpoint");
        WebFrameworkUtils.setCommonResult(
                request, CommonResult.success(Map.of("accessToken", "private-token", "name", "kept")));

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

        ArgumentCaptor<ApiAccessLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
        verify(apiAccessLogApi).createApiAccessLogAsync(captor.capture());
        ApiAccessLogCreateReqDTO accessLog = captor.getValue();
        assertThat(accessLog.getRequestParams()).isNull();
        assertThat(accessLog.getResponseBody()).contains("kept").doesNotContain("private-token", "accessToken");
        assertThat(accessLog.getResultCode()).isEqualTo(GlobalErrorCodeConstants.SUCCESS.getCode());
        assertThat(accessLog.getOperateModule()).isEqualTo("security-admin");
        assertThat(accessLog.getOperateName()).isEqualTo("rotate credential");
        assertThat(accessLog.getOperateType()).isEqualTo(OperateTypeEnum.UPDATE.getType());
    }

    @Test
    void doFilter_openApiMetadataAndHttpMethodProvideStableFallbackClassification() throws Exception {
        ApiAccessLogCommonApi apiAccessLogApi = mock(ApiAccessLogCommonApi.class);
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test-app", apiAccessLogApi);
        String[] methods = {"GET", "POST", "PUT", "DELETE", "PATCH", "CUSTOM"};
        int[] expectedTypes = {
            OperateTypeEnum.GET.getType(),
            OperateTypeEnum.CREATE.getType(),
            OperateTypeEnum.UPDATE.getType(),
            OperateTypeEnum.DELETE.getType(),
            OperateTypeEnum.OTHER.getType(),
            OperateTypeEnum.OTHER.getType()
        };

        for (String method : methods) {
            filter.doFilter(requestFor(method, "documentedEndpoint"), new MockHttpServletResponse(), (req, res) -> {});
        }

        ArgumentCaptor<ApiAccessLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
        verify(apiAccessLogApi, times(methods.length)).createApiAccessLogAsync(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(accessLog -> {
            assertThat(accessLog.getOperateModule()).isEqualTo("test-module");
            assertThat(accessLog.getOperateName()).isEqualTo("documented operation");
        });
        assertThat(captor.getAllValues())
                .extracting(ApiAccessLogCreateReqDTO::getOperateType)
                .containsExactly(
                        expectedTypes[0],
                        expectedTypes[1],
                        expectedTypes[2],
                        expectedTypes[3],
                        expectedTypes[4],
                        expectedTypes[5]);
    }

    @Test
    void doFilter_auditExceptionIsIsolatedButJvmErrorPropagates() {
        ApiAccessLogCommonApi apiAccessLogApi = mock(ApiAccessLogCommonApi.class);
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test-app", apiAccessLogApi);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin-api/test");
        String sensitiveDetail = "jdbc:mysql://private-host/private-schema";
        doThrow(new IllegalStateException(sensitiveDetail))
                .when(apiAccessLogApi)
                .createApiAccessLogAsync(any(ApiAccessLogCreateReqDTO.class));
        Logger logger = (Logger) LoggerFactory.getLogger(ApiAccessLogFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatCode(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {}))
                    .doesNotThrowAnyException();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains(IllegalStateException.class.getName()))
                .noneMatch(message -> message.contains(sensitiveDetail));

        doThrow(new AssertionError("fatal"))
                .when(apiAccessLogApi)
                .createApiAccessLogAsync(any(ApiAccessLogCreateReqDTO.class));
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {}))
                .isInstanceOf(AssertionError.class)
                .hasMessage("fatal");
    }

    private static MockHttpServletRequest requestFor(String httpMethod, String endpointName) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(httpMethod, "/admin-api/test");
        Method endpoint = TestController.class.getDeclaredMethod(endpointName);
        request.setAttribute(ATTRIBUTE_HANDLER_METHOD, new HandlerMethod(new TestController(), endpoint));
        return request;
    }

    @Tag(name = "test-module")
    private static final class TestController {

        @ApiAccessLog(sanitizeKeys = "pin")
        void endpoint() {}

        @ApiAccessLog(enable = false)
        void disabledEndpoint() {}

        @ApiAccessLog(
                requestEnable = false,
                responseEnable = true,
                operateModule = "security-admin",
                operateName = "rotate credential",
                operateType = OperateTypeEnum.UPDATE)
        void explicitAuditEndpoint() {}

        @Operation(summary = "documented operation")
        void documentedEndpoint() {}
    }
}
