package com.basicframework.framework.web.core.handler;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 全局响应结果（ResponseBody）处理器
 *
 * 不同于在网上看到的很多文章，会选择自动将 Controller 返回结果包上 {@link CommonResult}，
 * 在 onemall 中，是 Controller 在返回时，主动自己包上 {@link CommonResult}。
 * 原因是，GlobalResponseBodyHandler 本质上是 AOP，它不应该改变 Controller 返回的数据结构
 *
 * 目前，GlobalResponseBodyHandler 的主要作用是，记录 Controller 的返回结果，
 * 方便 {@link com.basicframework.framework.apilog.core.filter.ApiAccessLogFilter} 记录访问日志
 *
 * ADR 0003：异常路径由 {@link GlobalExceptionHandler} 返回 ResponseEntity&lt;CommonResult&gt;，
 * 此处同步识别该返回类型，保证错误响应的业务错误码仍进入访问日志。
 */
@ControllerAdvice
public class GlobalResponseBodyHandler implements ResponseBodyAdvice {

    @Override
    @SuppressWarnings("NullableProblems") // 避免 IDEA 警告
    public boolean supports(MethodParameter returnType, Class converterType) {
        if (returnType.getMethod() == null) {
            return false;
        }
        // 只拦截返回结果为 CommonResult 类型
        if (returnType.getMethod().getReturnType() == CommonResult.class) {
            return true;
        }
        // ADR 0003: exception handlers answer ResponseEntity<CommonResult>; record the body too
        return isCommonResultEntity(returnType.getMethod().getGenericReturnType());
    }

    private static boolean isCommonResultEntity(Type type) {
        if (!(type instanceof ParameterizedType)
                || ((ParameterizedType) type).getRawType() != ResponseEntity.class
                || ((ParameterizedType) type).getActualTypeArguments().length != 1) {
            return false;
        }
        Type argument = ((ParameterizedType) type).getActualTypeArguments()[0];
        return argument == CommonResult.class
                || (argument instanceof ParameterizedType
                        && ((ParameterizedType) argument).getRawType() == CommonResult.class);
    }

    @Override
    @SuppressWarnings("NullableProblems") // 避免 IDEA 警告
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        // 记录 Controller 结果
        WebFrameworkUtils.setCommonResult(
                ((ServletServerHttpRequest) request).getServletRequest(), (CommonResult<?>) body);
        return body;
    }
}
