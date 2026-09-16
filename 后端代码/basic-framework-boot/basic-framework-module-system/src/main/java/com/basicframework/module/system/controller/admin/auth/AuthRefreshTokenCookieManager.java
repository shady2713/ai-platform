package com.basicframework.module.system.controller.admin.auth;

import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.FORBIDDEN;
import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID;

import com.basicframework.framework.security.config.SecurityProperties;
import com.basicframework.framework.web.config.WebProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;

/** 管理刷新令牌 Cookie 的唯一入口。 */
@Component
public class AuthRefreshTokenCookieManager {

    static final String COOKIE_NAME = "basic_framework_refresh_token";
    private static final int MAX_TOKEN_LENGTH = 128;
    private static final String SAME_SITE = "Strict";

    private final SecurityProperties securityProperties;
    private final String cookiePath;
    private final CorsConfiguration corsConfiguration;

    public AuthRefreshTokenCookieManager(SecurityProperties securityProperties, WebProperties webProperties) {
        this.securityProperties = securityProperties;
        this.cookiePath = normalizeApiPrefix(webProperties.getAdminApi().getPrefix()) + "/system/auth";
        this.corsConfiguration = new CorsConfiguration();
        if (webProperties.getCorsAllowedOrigins() != null) {
            webProperties.getCorsAllowedOrigins().forEach(corsConfiguration::addAllowedOriginPattern);
        }
    }

    public void issue(HttpServletResponse response, String refreshToken) {
        if (!isValid(refreshToken)) {
            throw exception(SESSION_REFRESH_TOKEN_INVALID);
        }
        response.addHeader(
                HttpHeaders.SET_COOKIE, buildCookie(refreshToken, null).toString());
    }

    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                return isValid(value) ? value : null;
            }
        }
        return null;
    }

    public String require(HttpServletRequest request) {
        String refreshToken = read(request);
        if (!isValid(refreshToken)) {
            throw exception(SESSION_REFRESH_TOKEN_INVALID);
        }
        return refreshToken;
    }

    /**
     * 拒绝携带浏览器 Origin、但并非同源或 CORS 许可源的刷新令牌 Cookie 请求。
     *
     * <p>SameSite=Strict 不能阻止同一主域下的不可信子域发起请求；浏览器请求会带 Origin，
     * 因此在消费或撤销刷新令牌前必须完成精确校验。无 Origin 的非浏览器调用不由本检查拒绝，
     * 仍必须持有 HttpOnly Cookie 才能使用刷新令牌。
     *
     * @param request 当前 HTTP 请求
     */
    public void validateBrowserOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (!StringUtils.hasText(origin)) {
            return;
        }
        if (isSameRequestOrigin(request, origin) || corsConfiguration.checkOrigin(origin) != null) {
            return;
        }
        throw exception(FORBIDDEN);
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(
                HttpHeaders.SET_COOKIE, buildCookie("", Duration.ZERO).toString());
    }

    private ResponseCookie buildCookie(String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(securityProperties.getRefreshCookie().isSecure())
                .sameSite(SAME_SITE)
                .path(cookiePath);
        if (maxAge != null) {
            builder.maxAge(maxAge);
        }
        return builder.build();
    }

    private static boolean isValid(String refreshToken) {
        return StringUtils.hasText(refreshToken) && refreshToken.length() <= MAX_TOKEN_LENGTH;
    }

    private static String normalizeApiPrefix(String prefix) {
        int end = prefix.length();
        while (end > 1 && prefix.charAt(end - 1) == '/') {
            end--;
        }
        return prefix.substring(0, end);
    }

    private static boolean isSameRequestOrigin(HttpServletRequest request, String origin) {
        try {
            URI originUri = new URI(origin);
            if (originUri.getScheme() == null
                    || originUri.getHost() == null
                    || StringUtils.hasText(originUri.getRawPath())
                    || originUri.getRawQuery() != null
                    || originUri.getRawFragment() != null
                    || StringUtils.hasText(originUri.getUserInfo())) {
                return false;
            }
            return request.getScheme().equalsIgnoreCase(originUri.getScheme())
                    && request.getServerName().equalsIgnoreCase(originUri.getHost())
                    && normalizePort(request.getScheme(), request.getServerPort())
                            == normalizePort(originUri.getScheme(), originUri.getPort());
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private static int normalizePort(String scheme, int port) {
        if (port >= 0) {
            return port;
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            return 80;
        }
        return port;
    }
}
