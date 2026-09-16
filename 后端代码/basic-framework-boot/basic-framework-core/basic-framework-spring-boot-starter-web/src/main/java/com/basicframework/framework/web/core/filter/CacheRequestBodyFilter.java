package com.basicframework.framework.web.core.filter;

import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.PAYLOAD_TOO_LARGE;

import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.util.servlet.ServletUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Request Body 缓存 Filter，实现它的可重复读取
 *
 */
@RequiredArgsConstructor
public class CacheRequestBodyFilter extends OncePerRequestFilter {

    /**
     * 需要排除的 URI
     *
     * 1. 排除 Spring Boot Admin 和 Actuator 请求，避免缓存流影响管理端点。
     */
    private static final String[] IGNORE_URIS = {"/admin/", "/actuator/"};

    private final int maxBodyBytes;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws IOException, ServletException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            writePayloadTooLarge(response);
            return;
        }
        try {
            filterChain.doFilter(new CacheRequestBodyWrapper(request, maxBodyBytes), response);
        } catch (RequestBodyTooLargeException exception) {
            writePayloadTooLarge(response);
        }
    }

    private static void writePayloadTooLarge(HttpServletResponse response) throws IOException {
        ServletUtils.writeJSON(
                response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, CommonResult.error(PAYLOAD_TOO_LARGE));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 1. 校验是否为排除的 URL
        String requestURI = request.getRequestURI();
        if (StrUtil.startWithAny(requestURI, IGNORE_URIS)) {
            return true;
        }

        // 2. 只处理 json 请求内容
        return !ServletUtils.isJsonRequest(request);
    }
}
