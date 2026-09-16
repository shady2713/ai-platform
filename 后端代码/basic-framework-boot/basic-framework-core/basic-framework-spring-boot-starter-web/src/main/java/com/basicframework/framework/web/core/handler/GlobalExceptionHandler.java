package com.basicframework.framework.web.core.handler;

import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.*;
import static com.basicframework.framework.common.util.exception.SafeExceptionLogUtils.format;
import static com.basicframework.framework.web.core.util.SensitiveDataSanitizer.sanitizeRequestPath;
import static java.util.Map.entry;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.util.servlet.ServletUtils;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import com.basicframework.module.infra.api.logger.ApiErrorLogCommonApi;
import com.google.common.util.concurrent.UncheckedExecutionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Global exception handler: translates exceptions into an HTTP status plus a
 * {@link CommonResult} body (ADR 0003, docs/adr/0003-http-status-semantics.md).
 *
 * The HTTP status expresses the generic outcome; the body `code`/`msg` keeps the business
 * sub-reason. The code-to-status derivation lives only in {@link #resolveHttpStatus(Integer)}
 * and must not be scattered elsewhere. Transport-layer exceptions (validation, routing,
 * upload limits) are owned by {@link TransportExceptionHandler}; this class owns business,
 * authorization and catch-all semantics, and stays the single dispatch entry for filters.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * WWW-Authenticate header value carried by HTTP 401 responses (RFC 7235); shared with the
     * security starter's AuthenticationEntryPoint, which answers 401 outside the SpringMVC flow.
     */
    public static final String WWW_AUTHENTICATE_VALUE = "Bearer realm=\"basic-framework\"";

    /**
     * Retry-After hint (in seconds) carried by HTTP 429 responses. Fixed value: ServiceException
     * does not carry the per-endpoint limiter window; refine when the error body gains details.
     */
    private static final String RETRY_AFTER_SECONDS = "1";

    /**
     * Explicit HTTP status for framework global codes (GlobalErrorCodeConstants), restricted to
     * the ADR 0003 status set: concurrency/duplication codes map to 409, authorization denials to
     * 403, transport contract failures to their standard 4xx status, and server-side capability
     * codes to 500.
     */
    private static final Map<Integer, Integer> FRAMEWORK_CODE_STATUS = Map.ofEntries(
            entry(BAD_REQUEST.getCode(), HttpStatus.BAD_REQUEST.value()),
            entry(UNAUTHORIZED.getCode(), HttpStatus.UNAUTHORIZED.value()),
            entry(FORBIDDEN.getCode(), HttpStatus.FORBIDDEN.value()),
            entry(NOT_FOUND.getCode(), HttpStatus.NOT_FOUND.value()),
            entry(METHOD_NOT_ALLOWED.getCode(), HttpStatus.METHOD_NOT_ALLOWED.value()),
            entry(PAYLOAD_TOO_LARGE.getCode(), HttpStatus.PAYLOAD_TOO_LARGE.value()),
            entry(UNSUPPORTED_MEDIA_TYPE.getCode(), HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()),
            entry(CONFLICT.getCode(), HttpStatus.CONFLICT.value()),
            entry(LOCKED.getCode(), HttpStatus.CONFLICT.value()),
            entry(TOO_MANY_REQUESTS.getCode(), HttpStatus.TOO_MANY_REQUESTS.value()),
            entry(INTERNAL_SERVER_ERROR.getCode(), HttpStatus.INTERNAL_SERVER_ERROR.value()),
            entry(NOT_IMPLEMENTED.getCode(), HttpStatus.INTERNAL_SERVER_ERROR.value()),
            entry(ERROR_CONFIGURATION.getCode(), HttpStatus.INTERNAL_SERVER_ERROR.value()),
            entry(REPEATED_REQUESTS.getCode(), HttpStatus.CONFLICT.value()),
            entry(UNKNOWN.getCode(), HttpStatus.INTERNAL_SERVER_ERROR.value()));

    private final ApiErrorLogReporter errorLogReporter;

    private final TransportExceptionHandler transportExceptionHandler;

    public GlobalExceptionHandler(
            String applicationName,
            ApiErrorLogCommonApi apiErrorLogApi,
            TransportExceptionHandler transportExceptionHandler) {
        this.errorLogReporter = new ApiErrorLogReporter(applicationName, apiErrorLogApi);
        this.transportExceptionHandler = transportExceptionHandler;
        // Fail loud at startup when the error-code name registry cannot be built.
        ErrorCodeNameRegistry.init();
    }

    /**
     * 处理所有异常，主要是提供给 Filter 使用
     * 因为 Filter 不走 SpringMVC 的流程，但是我们又需要兜底处理异常，所以这里提供一个全量的异常处理过程，保持逻辑统一。
     *
     * @param request 请求
     * @param ex 异常
     * @return 通用返回，携带 ADR 0003 映射后的 HTTP 状态码
     */
    public ResponseEntity<CommonResult<?>> allExceptionHandler(HttpServletRequest request, Exception ex) {
        if (ex instanceof MissingServletRequestParameterException) {
            return transportExceptionHandler.missingServletRequestParameterExceptionHandler(
                    (MissingServletRequestParameterException) ex);
        }
        if (ex instanceof MethodArgumentTypeMismatchException) {
            return transportExceptionHandler.methodArgumentTypeMismatchExceptionHandler(
                    (MethodArgumentTypeMismatchException) ex);
        }
        if (ex instanceof MethodArgumentNotValidException) {
            return transportExceptionHandler.methodArgumentNotValidExceptionExceptionHandler(
                    (MethodArgumentNotValidException) ex);
        }
        if (ex instanceof BindException) {
            return transportExceptionHandler.bindExceptionHandler((BindException) ex);
        }
        if (ex instanceof ConstraintViolationException) {
            return transportExceptionHandler.constraintViolationExceptionHandler((ConstraintViolationException) ex);
        }
        if (ex instanceof ValidationException) {
            return transportExceptionHandler.validationException((ValidationException) ex);
        }
        if (ex instanceof MaxUploadSizeExceededException) {
            return transportExceptionHandler.maxUploadSizeExceededExceptionHandler((MaxUploadSizeExceededException) ex);
        }
        if (ex instanceof NoHandlerFoundException) {
            return transportExceptionHandler.noHandlerFoundExceptionHandler((NoHandlerFoundException) ex);
        }
        if (ex instanceof NoResourceFoundException) {
            return transportExceptionHandler.noResourceFoundExceptionHandler((NoResourceFoundException) ex);
        }
        if (ex instanceof HttpRequestMethodNotSupportedException) {
            return transportExceptionHandler.httpRequestMethodNotSupportedExceptionHandler(
                    (HttpRequestMethodNotSupportedException) ex);
        }
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return transportExceptionHandler.httpMediaTypeNotSupportedExceptionHandler(
                    (HttpMediaTypeNotSupportedException) ex);
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return transportExceptionHandler.methodArgumentTypeInvalidFormatExceptionHandler(
                    (HttpMessageNotReadableException) ex);
        }
        if (ex instanceof UncheckedExecutionException) {
            return uncheckedExecutionExceptionHandler(request, (UncheckedExecutionException) ex);
        }
        if (ex instanceof ServiceException) {
            return serviceExceptionHandler((ServiceException) ex);
        }
        if (ex instanceof AccessDeniedException) {
            return accessDeniedExceptionHandler(request, (AccessDeniedException) ex);
        }
        if (ex instanceof DuplicateKeyException duplicateKeyException) {
            return duplicateKeyExceptionHandler(duplicateKeyException);
        }
        return defaultExceptionHandler(request, ex);
    }

    /**
     * Write a ResponseEntity produced by this handler into a raw servlet response. Servlet
     * filters (for example, token authentication and authorization-denial filters) run outside the
     * SpringMVC flow but must share the same status semantics.
     *
     * @param response raw servlet response
     * @param entity entity produced by this handler
     */
    public static void writeResponse(HttpServletResponse response, ResponseEntity<CommonResult<?>> entity) {
        entity.getHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        ServletUtils.writeJSON(response, entity.getStatusCode().value(), entity.getBody());
    }

    /**
     * Derive the HTTP status for an error code; the single home of the code-to-status mapping
     * (ADR 0003). Framework global codes are mapped explicitly in {@link #FRAMEWORK_CODE_STATUS};
     * business codes follow the constant-name suffix convention (`*_NOT_EXISTS` to 404,
     * `*_EXISTS`/conflict-like names to 409) via {@link ErrorCodeNameRegistry}; anything else
     * defaults to 422.
     *
     * @param code error code carried by a ServiceException
     * @return HTTP status for the response
     */
    static int resolveHttpStatus(Integer code) {
        Integer mapped = FRAMEWORK_CODE_STATUS.get(code);
        if (mapped != null) {
            return mapped;
        }
        String name = ErrorCodeNameRegistry.nameOf(code);
        if (name == null) {
            return HttpStatus.UNPROCESSABLE_ENTITY.value();
        }
        if (name.endsWith("_NOT_EXISTS")) {
            return HttpStatus.NOT_FOUND.value();
        }
        if (name.contains("EXISTS") || name.contains("CONFLICT") || name.contains("DUPLICATE")) {
            return HttpStatus.CONFLICT.value();
        }
        return HttpStatus.UNPROCESSABLE_ENTITY.value();
    }

    private static ResponseEntity<CommonResult<?>> error(HttpStatus status, CommonResult<?> body) {
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 处理 Spring Security 权限不足的异常
     *
     * 来源是，使用 @PreAuthorize 注解，AOP 进行权限拦截
     */
    @ExceptionHandler(value = AccessDeniedException.class)
    public ResponseEntity<CommonResult<?>> accessDeniedExceptionHandler(
            HttpServletRequest req, AccessDeniedException ex) {
        log.warn(
                "[accessDeniedExceptionHandler][userId({}) 无法访问 url({})]",
                WebFrameworkUtils.getLoginUserId(req),
                sanitizeRequestPath(req));
        return error(HttpStatus.FORBIDDEN, CommonResult.error(FORBIDDEN));
    }

    /**
     * 处理 Guava UncheckedExecutionException
     *
     * 例如说，缓存加载报错时，会被包装成该异常
     */
    @ExceptionHandler(value = UncheckedExecutionException.class)
    public ResponseEntity<CommonResult<?>> uncheckedExecutionExceptionHandler(
            HttpServletRequest req, UncheckedExecutionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof Exception exception) {
            return allExceptionHandler(req, exception);
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return defaultExceptionHandler(req, ex);
    }

    /**
     * 处理业务异常 ServiceException
     *
     * 例如说，商品库存不足，用户手机号已存在。
     */
    @ExceptionHandler(value = ServiceException.class)
    public ResponseEntity<CommonResult<?>> serviceExceptionHandler(ServiceException ex) {
        int status = resolveHttpStatus(ex.getCode());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (status == HttpStatus.UNAUTHORIZED.value()) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE, WWW_AUTHENTICATE_VALUE);
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            builder.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return builder.body(CommonResult.error(ex.getCode(), ex.getPublicMessage()));
    }

    /** 数据库并发唯一约束是最终裁决；SQL、索引名和冲突字段原值均不参与对外消息。 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<CommonResult<?>> duplicateKeyExceptionHandler(DuplicateKeyException ex) {
        return duplicateDataConflict();
    }

    private static ResponseEntity<CommonResult<?>> duplicateDataConflict() {
        return error(HttpStatus.CONFLICT, CommonResult.error(CONFLICT.getCode(), "数据已存在，请检查后重试"));
    }

    /**
     * 处理系统异常，兜底处理所有的一切
     */
    @ExceptionHandler(value = Exception.class)
    public ResponseEntity<CommonResult<?>> defaultExceptionHandler(HttpServletRequest req, Exception ex) {
        // 包装异常中的业务异常仍按业务错误返回
        if (ex.getCause() instanceof ServiceException serviceException) {
            return serviceExceptionHandler(serviceException);
        }
        // 处理数据库唯一索引冲突
        ResponseEntity<CommonResult<?>> sqlResult = handleSqlException(ex);
        if (sqlResult != null) {
            return sqlResult;
        }
        log.error(
                "[defaultExceptionHandler][exceptionName({}) stackTrace({})]",
                ex.getClass().getName(),
                format(ex));
        // 插入异常日志
        errorLogReporter.report(req, ex);
        // 返回 ERROR CommonResult；对外固定文案，ex.getMessage() 不进入响应
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                CommonResult.error(INTERNAL_SERVER_ERROR.getCode(), INTERNAL_SERVER_ERROR.getMsg()));
    }

    /**
     * 保持包装异常与直接异常的相同约束冲突语义，不检查驱动异常消息。
     */
    private ResponseEntity<CommonResult<?>> handleSqlException(Exception ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof DuplicateKeyException
                    || cause instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return duplicateDataConflict();
            }
            cause = cause.getCause();
        }
        return null;
    }
}
