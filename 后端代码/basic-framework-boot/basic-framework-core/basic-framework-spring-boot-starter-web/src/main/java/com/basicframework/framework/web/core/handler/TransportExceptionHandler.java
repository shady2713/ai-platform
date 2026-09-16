package com.basicframework.framework.web.core.handler;

import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.METHOD_NOT_ALLOWED;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.NOT_FOUND;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.PAYLOAD_TOO_LARGE;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.UNSUPPORTED_MEDIA_TYPE;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.common.pojo.CommonResult;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
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
 * SpringMVC 传输层异常翻译：参数缺失/类型错误、校验失败、路由与方法不匹配、
 * 上传超限等已知 4xx 语义（ADR 0003）。业务异常与兜底语义仍由
 * {@link GlobalExceptionHandler} 拥有；本类只承接与 HTTP 契约直接相关的异常簇。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class TransportExceptionHandler {

    /**
     * 处理 SpringMVC 请求参数缺失
     *
     * 例如说，接口上设置了 @RequestParam("xx") 参数，结果并未传递 xx 参数
     */
    @ExceptionHandler(value = MissingServletRequestParameterException.class)
    public ResponseEntity<CommonResult<?>> missingServletRequestParameterExceptionHandler(
            MissingServletRequestParameterException ex) {
        log.debug("[missingServletRequestParameterExceptionHandler][parameterName({})]", ex.getParameterName());
        return error(
                HttpStatus.BAD_REQUEST,
                CommonResult.error(BAD_REQUEST.getCode(), String.format("请求参数缺失:%s", ex.getParameterName())));
    }

    /**
     * 处理 SpringMVC 请求参数类型错误
     *
     * 例如说，接口上设置了 @RequestParam("xx") 参数为 Integer，结果传递 xx 参数类型为 String
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<CommonResult<?>> methodArgumentTypeMismatchExceptionHandler(
            MethodArgumentTypeMismatchException ex) {
        log.debug("[methodArgumentTypeMismatchExceptionHandler][parameterName({})]", ex.getName());
        return error(
                HttpStatus.BAD_REQUEST,
                CommonResult.error(BAD_REQUEST.getCode(), String.format("请求参数类型错误:%s", ex.getName())));
    }

    /**
     * 处理 SpringMVC 参数校验不正确
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CommonResult<?>> methodArgumentNotValidExceptionExceptionHandler(
            MethodArgumentNotValidException ex) {
        // 获取 errorMessage
        String errorMessage = null;
        FieldError fieldError = ex.getBindingResult().getFieldError();
        if (fieldError == null) {
            // 组合校验时，尝试从全量错误信息中提取提示
            List<ObjectError> allErrors = ex.getBindingResult().getAllErrors();
            if (CollUtil.isNotEmpty(allErrors)) {
                errorMessage = allErrors.get(0).getDefaultMessage();
            }
        } else {
            errorMessage = fieldError.getDefaultMessage();
        }
        // 转换 CommonResult
        if (StrUtil.isEmpty(errorMessage)) {
            return error(HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST));
        }
        return error(HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST.getCode(), errorMessage));
    }

    /**
     * 处理 SpringMVC 参数绑定不正确，本质上也是通过 Validator 校验
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<CommonResult<?>> bindExceptionHandler(BindException ex) {
        FieldError fieldError = ex.getFieldError();
        if (fieldError == null || StrUtil.isEmpty(fieldError.getDefaultMessage())) {
            return error(HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST));
        }
        return error(HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST.getCode(), fieldError.getDefaultMessage()));
    }

    /**
     * 处理 SpringMVC 请求体不可读
     *
     * 例如说，接口上设置了 @RequestBody 实体中 xx 属性类型为 Integer，结果传递 xx 参数类型为 String
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<?>> methodArgumentTypeInvalidFormatExceptionHandler(
            HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof InvalidFormatException) {
            return error(HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST.getCode(), "请求参数类型错误"));
        }
        if (StrUtil.startWith(ex.getMessage(), "Required request body is missing")) {
            return error(
                    HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST.getCode(), "请求参数类型错误: request body 缺失"));
        }
        return error(HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST.getCode(), "请求体格式错误"));
    }

    /**
     * 处理 Validator 校验不通过产生的异常
     */
    @ExceptionHandler(value = ConstraintViolationException.class)
    public ResponseEntity<CommonResult<?>> constraintViolationExceptionHandler(ConstraintViolationException ex) {
        ConstraintViolation<?> constraintViolation =
                ex.getConstraintViolations().stream().findFirst().orElse(null);
        if (constraintViolation == null || StrUtil.isEmpty(constraintViolation.getMessage())) {
            return error(HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST));
        }
        return error(
                HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST.getCode(), constraintViolation.getMessage()));
    }

    /**
     * 处理 ValidationException 异常（无结构化明细，固定返回通用 400 文案）
     */
    @ExceptionHandler(value = ValidationException.class)
    public ResponseEntity<CommonResult<?>> validationException(ValidationException ex) {
        return error(HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST));
    }

    /**
     * 处理上传文件过大异常
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<CommonResult<?>> maxUploadSizeExceededExceptionHandler(MaxUploadSizeExceededException ex) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, CommonResult.error(PAYLOAD_TOO_LARGE));
    }

    /**
     * 未匹配路径可能包含凭据或个人信息，响应及日志均不得反射原始路径。
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<CommonResult<?>> noHandlerFoundExceptionHandler(NoHandlerFoundException ex) {
        return error(HttpStatus.NOT_FOUND, CommonResult.error(NOT_FOUND));
    }

    /**
     * 静态资源未命中使用同一固定提示，避免原始路径经访问日志的结果消息再次落盘。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<CommonResult<?>> noResourceFoundExceptionHandler(NoResourceFoundException ex) {
        return error(HttpStatus.NOT_FOUND, CommonResult.error(NOT_FOUND));
    }

    /**
     * 处理 SpringMVC 请求方法不正确
     *
     * 例如说，A 接口的方法为 GET 方式，结果请求方法为 POST 方式，导致不匹配
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<CommonResult<?>> httpRequestMethodNotSupportedExceptionHandler(
            HttpRequestMethodNotSupportedException ex) {
        log.debug("[httpRequestMethodNotSupportedExceptionHandler][method({})]", ex.getMethod());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        Set<HttpMethod> supportedMethods = ex.getSupportedHttpMethods();
        if (CollUtil.isNotEmpty(supportedMethods)) {
            builder.allow(supportedMethods.toArray(HttpMethod[]::new));
        }
        return builder.body(
                CommonResult.error(METHOD_NOT_ALLOWED.getCode(), String.format("请求方法不正确:%s", ex.getMethod())));
    }

    /**
     * 处理 SpringMVC 请求的 Content-Type 不正确
     *
     * 例如说，A 接口的 Content-Type 为 application/json，结果请求的 Content-Type 为 application/octet-stream，导致不匹配
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<CommonResult<?>> httpMediaTypeNotSupportedExceptionHandler(
            HttpMediaTypeNotSupportedException ex) {
        log.debug("[httpMediaTypeNotSupportedExceptionHandler][contentType({})]", ex.getContentType());
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, CommonResult.error(UNSUPPORTED_MEDIA_TYPE));
    }

    private static ResponseEntity<CommonResult<?>> error(HttpStatus status, CommonResult<?> body) {
        return ResponseEntity.status(status).body(body);
    }
}
