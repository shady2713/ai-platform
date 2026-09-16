package com.basicframework.framework.web.core.handler;

import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.CONFLICT;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.FORBIDDEN;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.LOCKED;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.METHOD_NOT_ALLOWED;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.PAYLOAD_TOO_LARGE;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.REPEATED_REQUESTS;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.TOO_MANY_REQUESTS;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.UNSUPPORTED_MEDIA_TYPE;
import static com.basicframework.framework.web.core.handler.enums.TestStatusErrorCodeConstants.TEST_FOO_EXISTS;
import static com.basicframework.framework.web.core.handler.enums.TestStatusErrorCodeConstants.TEST_FOO_INVALID_STATE;
import static com.basicframework.framework.web.core.handler.enums.TestStatusErrorCodeConstants.TEST_FOO_NOT_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import com.basicframework.module.infra.api.logger.ApiErrorLogCommonApi;
import com.basicframework.module.infra.api.logger.dto.ApiErrorLogCreateReqDTO;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.google.common.util.concurrent.UncheckedExecutionException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * HTTP status semantics of {@link GlobalExceptionHandler} (ADR 0003): the HTTP status expresses
 * the generic outcome while the body code keeps the business sub-reason.
 */
class GlobalExceptionHandlerTest {

    private final ApiErrorLogCommonApi apiErrorLogApi = mock(ApiErrorLogCommonApi.class);

    private final TransportExceptionHandler transportHandler = new TransportExceptionHandler();

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler("test-app", apiErrorLogApi, transportHandler);

    @Test
    void serviceException_mapsUnauthorizedTo401WithChallengeHeader() {
        ResponseEntity<CommonResult<?>> entity = handler.serviceExceptionHandler(new ServiceException(UNAUTHORIZED));

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(entity.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo(GlobalExceptionHandler.WWW_AUTHENTICATE_VALUE);
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getCode()).isEqualTo(UNAUTHORIZED.getCode());
    }

    @Test
    void serviceException_mapsNotExistsSuffixTo404() {
        ResponseEntity<CommonResult<?>> entity =
                handler.serviceExceptionHandler(new ServiceException(TEST_FOO_NOT_EXISTS));

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getCode()).isEqualTo(TEST_FOO_NOT_EXISTS.getCode());
    }

    @Test
    void serviceException_mapsExistsSuffixTo409() {
        ResponseEntity<CommonResult<?>> entity = handler.serviceExceptionHandler(new ServiceException(TEST_FOO_EXISTS));

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getCode()).isEqualTo(TEST_FOO_EXISTS.getCode());
    }

    @Test
    void serviceException_mapsFrameworkConflictCodesTo409() {
        assertThat(handler.serviceExceptionHandler(new ServiceException(CONFLICT))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.serviceExceptionHandler(new ServiceException(LOCKED))
                        .getStatusCode()
                        .value())
                .isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(handler.serviceExceptionHandler(new ServiceException(REPEATED_REQUESTS))
                        .getStatusCode()
                        .value())
                .isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void serviceException_mapsRateLimitTo429WithRetryAfter() {
        ResponseEntity<CommonResult<?>> entity =
                handler.serviceExceptionHandler(new ServiceException(TOO_MANY_REQUESTS));

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(entity.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
    }

    @Test
    void serviceException_defaultsBusinessCodeTo422() {
        // Registered constant without a mapped suffix
        assertThat(handler.serviceExceptionHandler(new ServiceException(TEST_FOO_INVALID_STATE))
                        .getStatusCode()
                        .value())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        // Code absent from every registry
        assertThat(handler.serviceExceptionHandler(new ServiceException(1_999_999_999, "unknown"))
                        .getStatusCode()
                        .value())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
    }

    @Test
    void accessDenied_mapsTo403() {
        ResponseEntity<CommonResult<?>> entity =
                handler.accessDeniedExceptionHandler(new MockHttpServletRequest(), new AccessDeniedException("deny"));

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getCode()).isEqualTo(FORBIDDEN.getCode());
    }

    @Test
    void allExceptionHandler_dispatchesEverySupportedExceptionFamily() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        WebFrameworkUtils.setLoginUserType(request, 1);

        MethodArgumentTypeMismatchException typeMismatch = mock(MethodArgumentTypeMismatchException.class);
        when(typeMismatch.getName()).thenReturn("id");
        MethodArgumentNotValidException argumentNotValid = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        when(argumentNotValid.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getAllErrors()).thenReturn(List.of());

        List<ExceptionExpectation> expectations = List.of(
                new ExceptionExpectation(
                        new MissingServletRequestParameterException("name", String.class.getName()),
                        HttpStatus.BAD_REQUEST),
                new ExceptionExpectation(typeMismatch, HttpStatus.BAD_REQUEST),
                new ExceptionExpectation(argumentNotValid, HttpStatus.BAD_REQUEST),
                new ExceptionExpectation(new BindException(new Object(), "request"), HttpStatus.BAD_REQUEST),
                new ExceptionExpectation(new ConstraintViolationException(Set.of()), HttpStatus.BAD_REQUEST),
                new ExceptionExpectation(new ValidationException("invalid"), HttpStatus.BAD_REQUEST),
                new ExceptionExpectation(new MaxUploadSizeExceededException(1024), HttpStatus.PAYLOAD_TOO_LARGE),
                new ExceptionExpectation(
                        new NoHandlerFoundException("GET", "/missing", new HttpHeaders()), HttpStatus.NOT_FOUND),
                new ExceptionExpectation(
                        new NoResourceFoundException(HttpMethod.GET, "/missing"), HttpStatus.NOT_FOUND),
                new ExceptionExpectation(
                        new HttpRequestMethodNotSupportedException("POST", List.of("GET")),
                        HttpStatus.METHOD_NOT_ALLOWED),
                new ExceptionExpectation(
                        new HttpMediaTypeNotSupportedException(
                                MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)),
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE),
                new ExceptionExpectation(new HttpMessageNotReadableException("malformed"), HttpStatus.BAD_REQUEST),
                new ExceptionExpectation(
                        new UncheckedExecutionException(new ServiceException(LOCKED)), HttpStatus.CONFLICT),
                new ExceptionExpectation(new ServiceException(LOCKED), HttpStatus.CONFLICT),
                new ExceptionExpectation(new AccessDeniedException("denied"), HttpStatus.FORBIDDEN),
                new ExceptionExpectation(new RuntimeException("internal detail"), HttpStatus.INTERNAL_SERVER_ERROR));

        for (ExceptionExpectation expectation : expectations) {
            ResponseEntity<CommonResult<?>> response = handler.allExceptionHandler(request, expectation.exception());
            assertThat(response.getStatusCode())
                    .as("status for %s", expectation.exception().getClass().getSimpleName())
                    .isEqualTo(expectation.status());
        }
    }

    @Test
    void defaultException_mapsTo500WithSafeFixedMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // Provide the user type up front; otherwise the URL-prefix fallback needs the WebProperties bean.
        WebFrameworkUtils.setLoginUserType(request, 1);
        String leak = "sensitive internal detail";

        ResponseEntity<CommonResult<?>> entity = handler.defaultExceptionHandler(request, new RuntimeException(leak));

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getCode()).isEqualTo(INTERNAL_SERVER_ERROR.getCode());
        assertThat(entity.getBody().getMsg()).isEqualTo(INTERNAL_SERVER_ERROR.getMsg());
        assertThat(entity.getBody().getMsg()).doesNotContain(leak);
        verify(apiErrorLogApi).createApiErrorLogAsync(any(ApiErrorLogCreateReqDTO.class));
    }

    @Test
    void defaultException_sanitizesCredentialFieldsInErrorLog() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin-api/files/contact@example.com");
        request.setAttribute(
                org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/admin-api/files/{fileName}");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(
                ("""
                        {
                          "newPassword": "${TEST_SECRET}",
                          "apiKey": "${TEST_SECRET}",
                          "accessSecret": "${TEST_SECRET}",
                          "name": "kept"
                        }
                        """)
                        .getBytes(StandardCharsets.UTF_8));
        WebFrameworkUtils.setLoginUserType(request, 1);

        String sensitiveValue = UUID.randomUUID().toString();
        handler.defaultExceptionHandler(
                request, new RuntimeException(sensitiveValue, new IllegalArgumentException(sensitiveValue)));

        ArgumentCaptor<ApiErrorLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiErrorLogCreateReqDTO.class);
        verify(apiErrorLogApi).createApiErrorLogAsync(captor.capture());
        assertThat(captor.getValue().getRequestParams())
                .doesNotContain("${TEST_SECRET}", "newPassword", "apiKey", "accessSecret")
                .contains("name", "kept");
        assertThat(captor.getValue().getExceptionMessage()).doesNotContain(sensitiveValue);
        assertThat(captor.getValue().getExceptionRootCauseMessage()).doesNotContain(sensitiveValue);
        assertThat(captor.getValue().getExceptionStackTrace()).doesNotContain(sensitiveValue);
        assertThat(captor.getValue().getExceptionStackTrace())
                .contains(RuntimeException.class.getName(), IllegalArgumentException.class.getName());
        assertThat(captor.getValue().getRequestUrl())
                .isEqualTo("/admin-api/files/{fileName}")
                .doesNotContain("contact@example.com");
    }

    @Test
    void defaultException_persistsBoundedStackMetadataForEmptyAndOversizedTraces() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        WebFrameworkUtils.setLoginUserType(request, 1);
        ApiErrorLogCommonApi auditApi = mock(ApiErrorLogCommonApi.class);
        GlobalExceptionHandler localHandler = new GlobalExceptionHandler("test-app", auditApi, transportHandler);

        RuntimeException emptyTrace = new RuntimeException("private-empty-detail");
        emptyTrace.setStackTrace(new StackTraceElement[0]);
        RuntimeException oversizedTrace = new RuntimeException("private-oversized-detail");
        StackTraceElement[] frames = new StackTraceElement[1000];
        for (int index = 0; index < frames.length; index++) {
            frames[index] = new StackTraceElement(
                    "com.example.component.WithAnIntentionallyLongClassName" + index,
                    "executeOperation",
                    "WithAnIntentionallyLongClassName.java",
                    index + 1);
        }
        oversizedTrace.setStackTrace(frames);

        localHandler.defaultExceptionHandler(request, emptyTrace);
        localHandler.defaultExceptionHandler(request, oversizedTrace);

        ArgumentCaptor<ApiErrorLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiErrorLogCreateReqDTO.class);
        verify(auditApi, times(2)).createApiErrorLogAsync(captor.capture());
        ApiErrorLogCreateReqDTO emptyLog = captor.getAllValues().get(0);
        ApiErrorLogCreateReqDTO oversizedLog = captor.getAllValues().get(1);
        assertThat(emptyLog.getExceptionMethodName()).isEqualTo("unknown");
        assertThat(emptyLog.getExceptionLineNumber()).isEqualTo(-1);
        assertThat(emptyLog.getExceptionStackTrace()).doesNotContain("private-empty-detail");
        assertThat(oversizedLog.getExceptionStackTrace())
                .contains("... truncated")
                .doesNotContain("private-oversized-detail");
    }

    @Test
    void malformedRequestBody_doesNotEchoRejectedValue() {
        String sensitiveValue = UUID.randomUUID().toString();
        HttpMessageNotReadableException exception = mock(HttpMessageNotReadableException.class);
        InvalidFormatException cause = mock(InvalidFormatException.class);
        when(exception.getCause()).thenReturn(cause);
        when(cause.getValue()).thenReturn(sensitiveValue);

        ResponseEntity<CommonResult<?>> entity =
                transportHandler.methodArgumentTypeInvalidFormatExceptionHandler(exception);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getMsg()).doesNotContain(sensitiveValue);
    }

    @Test
    void validationHandlers_returnDeclaredMessagesAndSafeFallbacks() {
        MethodArgumentNotValidException fieldFailure = mock(MethodArgumentNotValidException.class);
        BindingResult fieldBinding = mock(BindingResult.class);
        when(fieldFailure.getBindingResult()).thenReturn(fieldBinding);
        when(fieldBinding.getFieldError()).thenReturn(new FieldError("request", "name", "名称不能为空"));

        MethodArgumentNotValidException objectFailure = mock(MethodArgumentNotValidException.class);
        BindingResult objectBinding = mock(BindingResult.class);
        when(objectFailure.getBindingResult()).thenReturn(objectBinding);
        when(objectBinding.getAllErrors()).thenReturn(List.of(new ObjectError("request", "组合参数无效")));

        BindException bindFailure = new BindException(new Object(), "request");
        bindFailure.addError(new FieldError("request", "name", "名称格式无效"));

        @SuppressWarnings("unchecked")
        jakarta.validation.ConstraintViolation<Object> violation = mock(jakarta.validation.ConstraintViolation.class);
        when(violation.getMessage()).thenReturn("约束校验失败");

        assertThat(transportHandler
                        .methodArgumentNotValidExceptionExceptionHandler(fieldFailure)
                        .getBody()
                        .getMsg())
                .isEqualTo("名称不能为空");
        assertThat(transportHandler
                        .methodArgumentNotValidExceptionExceptionHandler(objectFailure)
                        .getBody()
                        .getMsg())
                .isEqualTo("组合参数无效");
        assertThat(transportHandler.bindExceptionHandler(bindFailure).getBody().getMsg())
                .isEqualTo("名称格式无效");
        assertThat(transportHandler
                        .constraintViolationExceptionHandler(new ConstraintViolationException(Set.of(violation)))
                        .getBody()
                        .getMsg())
                .isEqualTo("约束校验失败");
    }

    @Test
    void malformedRequestBody_distinguishesMissingBodyWithoutEchoingParserDetails() {
        HttpMessageNotReadableException exception = mock(HttpMessageNotReadableException.class);
        when(exception.getMessage()).thenReturn("Required request body is missing: private-parser-detail");

        ResponseEntity<CommonResult<?>> entity =
                transportHandler.methodArgumentTypeInvalidFormatExceptionHandler(exception);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getMsg())
                .isEqualTo("请求参数类型错误: request body 缺失")
                .doesNotContain("private-parser-detail");
    }

    @Test
    void transportContractErrors_useStandardStatusesCodesAndAllowHeader() {
        ResponseEntity<CommonResult<?>> method = transportHandler.httpRequestMethodNotSupportedExceptionHandler(
                new HttpRequestMethodNotSupportedException("POST", java.util.List.of("GET", "PUT")));
        ResponseEntity<CommonResult<?>> media =
                transportHandler.httpMediaTypeNotSupportedExceptionHandler(new HttpMediaTypeNotSupportedException(
                        MediaType.TEXT_PLAIN, java.util.List.of(MediaType.APPLICATION_JSON)));
        ResponseEntity<CommonResult<?>> upload =
                transportHandler.maxUploadSizeExceededExceptionHandler(new MaxUploadSizeExceededException(1024));

        assertThat(method.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(method.getHeaders().getAllow())
                .containsExactlyInAnyOrder(
                        org.springframework.http.HttpMethod.GET, org.springframework.http.HttpMethod.PUT);
        assertThat(method.getBody()).isNotNull();
        assertThat(method.getBody().getCode()).isEqualTo(METHOD_NOT_ALLOWED.getCode());
        assertThat(media.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(media.getBody()).isNotNull();
        assertThat(media.getBody().getCode()).isEqualTo(UNSUPPORTED_MEDIA_TYPE.getCode());
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(upload.getBody()).isNotNull();
        assertThat(upload.getBody().getCode()).isEqualTo(PAYLOAD_TOO_LARGE.getCode());
    }

    @Test
    void uncheckedExecution_unwrapsExceptionsButNeverSwallowsJvmErrors() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        WebFrameworkUtils.setLoginUserType(request, 1);

        ResponseEntity<CommonResult<?>> entity = handler.uncheckedExecutionExceptionHandler(
                request, new UncheckedExecutionException(new ServiceException(LOCKED)));

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        AssertionError fatal = new AssertionError("fatal");
        assertThatThrownBy(() ->
                        handler.uncheckedExecutionExceptionHandler(request, new UncheckedExecutionException(fatal)))
                .isSameAs(fatal);
    }

    @Test
    void wrappedUnknownThrowable_usesTheSafeDefaultResponse() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        WebFrameworkUtils.setLoginUserType(request, 1);

        ResponseEntity<CommonResult<?>> entity = handler.uncheckedExecutionExceptionHandler(
                request, new UncheckedExecutionException(new Throwable("private throwable detail")));

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getMsg())
                .isEqualTo(INTERNAL_SERVER_ERROR.getMsg())
                .doesNotContain("private throwable detail");
    }

    @Test
    void defaultException_unwrapsNestedServiceException() {
        ResponseEntity<CommonResult<?>> entity = handler.defaultExceptionHandler(
                new MockHttpServletRequest(), new RuntimeException(new ServiceException(LOCKED)));

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getCode()).isEqualTo(LOCKED.getCode());
    }

    @Test
    void defaultException_isolatesAuditExceptionsButNeverSwallowsJvmErrors() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        WebFrameworkUtils.setLoginUserType(request, 1);
        ApiErrorLogCommonApi unavailableApi = mock(ApiErrorLogCommonApi.class);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(unavailableApi)
                .createApiErrorLogAsync(any(ApiErrorLogCreateReqDTO.class));
        GlobalExceptionHandler unavailableHandler =
                new GlobalExceptionHandler("test-app", unavailableApi, transportHandler);

        assertThatCode(() -> unavailableHandler.defaultExceptionHandler(request, new RuntimeException("business")))
                .doesNotThrowAnyException();

        ApiErrorLogCommonApi fatalApi = mock(ApiErrorLogCommonApi.class);
        AssertionError fatal = new AssertionError("fatal");
        doThrow(fatal).when(fatalApi).createApiErrorLogAsync(any(ApiErrorLogCreateReqDTO.class));
        GlobalExceptionHandler fatalHandler = new GlobalExceptionHandler("test-app", fatalApi, transportHandler);
        assertThatThrownBy(() -> fatalHandler.defaultExceptionHandler(request, new RuntimeException("business")))
                .isSameAs(fatal);
    }

    @Test
    void defaultException_mapsSqlUniqueConstraintTo409() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RuntimeException ex = new RuntimeException(
                new SQLIntegrityConstraintViolationException("Duplicate entry 'a' for key 'uk_code'"));

        ResponseEntity<CommonResult<?>> entity = handler.defaultExceptionHandler(request, ex);

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getMsg()).isEqualTo("数据已存在，请检查后重试");
    }

    @Test
    void defaultException_mapsKnownAndUnknownUniqueConstraintsWithoutLeakingSql() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<CommonResult<?>> known = handler.defaultExceptionHandler(
                request,
                new RuntimeException(new SQLIntegrityConstraintViolationException(
                        "Duplicate entry 'private-value' for key 'uk_name'")));
        ResponseEntity<CommonResult<?>> unknown = handler.defaultExceptionHandler(
                request,
                new RuntimeException(new SQLIntegrityConstraintViolationException(
                        "Duplicate entry 'private-value' for key 'uk_other'")));

        assertThat(known.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(known.getBody()).isNotNull();
        assertThat(known.getBody().getMsg()).isEqualTo("数据已存在，请检查后重试").doesNotContain("private-value");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(unknown.getBody()).isNotNull();
        assertThat(unknown.getBody().getMsg()).isEqualTo("数据已存在，请检查后重试").doesNotContain("private-value");
    }

    @Test
    void duplicateKeyMapsToSafeConflictInControllerFilterAndWrappedPaths() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        DuplicateKeyException failure = new DuplicateKeyException("INSERT private-phone-value uk_private_index");
        for (ResponseEntity<CommonResult<?>> response : List.of(
                handler.duplicateKeyExceptionHandler(failure),
                handler.allExceptionHandler(request, failure),
                handler.defaultExceptionHandler(request, new RuntimeException(failure)))) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getCode()).isEqualTo(CONFLICT.getCode());
            assertThat(response.getBody().getMsg()).isEqualTo("数据已存在，请检查后重试");
        }
    }

    @Test
    void missingRequestParameter_mapsTo400() {
        ResponseEntity<CommonResult<?>> entity = transportHandler.missingServletRequestParameterExceptionHandler(
                new MissingServletRequestParameterException("name", "java.lang.String"));

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void noHandlerFound_mapsTo404() {
        ResponseEntity<CommonResult<?>> entity = transportHandler.noHandlerFoundExceptionHandler(
                new NoHandlerFoundException("GET", "/lost", new HttpHeaders()));

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void writeResponse_copiesStatusHeadersAndBody() throws Exception {
        ResponseEntity<CommonResult<?>> entity =
                handler.serviceExceptionHandler(new ServiceException(TOO_MANY_REQUESTS));
        MockHttpServletResponse response = new MockHttpServletResponse();

        GlobalExceptionHandler.writeResponse(response, entity);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(response.getContentAsString())
                .contains(TOO_MANY_REQUESTS.getCode().toString());
    }

    private record ExceptionExpectation(Exception exception, HttpStatus status) {}
}
