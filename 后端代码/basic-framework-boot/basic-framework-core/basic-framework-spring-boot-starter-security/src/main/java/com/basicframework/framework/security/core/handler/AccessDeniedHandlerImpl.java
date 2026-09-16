package com.basicframework.framework.security.core.handler;

import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.FORBIDDEN;

import com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.util.servlet.ServletUtils;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.ExceptionTranslationFilter;

/**
 * 访问一个需要认证的 URL 资源，已经认证（登录）但是没有权限的情况下，返回 {@link GlobalErrorCodeConstants#FORBIDDEN} 错误码。
 *
 * 补充：Spring Security 通过 {@link ExceptionTranslationFilter#handleAccessDeniedException(HttpServletRequest, HttpServletResponse, FilterChain, AccessDeniedException)} 方法，调用当前类
 *
 */
@Slf4j
@SuppressWarnings("JavadocReference")
public class AccessDeniedHandlerImpl implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException, ServletException {
        log.warn(
                "[commence][访问 URL({}) 时，用户({}) 权限不够，exception({})]",
                request.getRequestURI(),
                SecurityFrameworkUtils.getLoginUserId(),
                e.getClass().getSimpleName());
        // ADR 0003：已认证但无权限，返回 HTTP 403 + FORBIDDEN 错误码
        ServletUtils.writeJSON(response, HttpStatus.FORBIDDEN.value(), CommonResult.error(FORBIDDEN));
    }
}
