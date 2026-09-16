package com.basicframework.framework.security.core.handler;

import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;

import com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.util.servlet.ServletUtils;
import com.basicframework.framework.web.core.handler.GlobalExceptionHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.ExceptionTranslationFilter;

/**
 * 访问一个需要认证的 URL 资源，但是此时自己尚未认证（登录）的情况下，返回 HTTP 401 +
 * {@link GlobalErrorCodeConstants#UNAUTHORIZED} 错误码（ADR 0003），并携带 RFC 7235 要求的
 * WWW-Authenticate 响应头，从而使前端重定向到登录页
 *
 * 补充：Spring Security 通过 {@link ExceptionTranslationFilter#sendStartAuthentication(HttpServletRequest, HttpServletResponse, FilterChain, AuthenticationException)} 方法，调用当前类
 *
 */
@Slf4j
@SuppressWarnings("JavadocReference")
public class AuthenticationEntryPointImpl implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e) {
        log.debug(
                "[commence][访问 URL({}) 时，没有登录，exception({})]",
                request.getRequestURI(),
                e.getClass().getSimpleName());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, GlobalExceptionHandler.WWW_AUTHENTICATE_VALUE);
        ServletUtils.writeJSON(response, HttpStatus.UNAUTHORIZED.value(), CommonResult.error(UNAUTHORIZED));
    }
}
