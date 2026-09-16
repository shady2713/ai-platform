package com.basicframework.module.system.controller.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.basicframework.framework.security.config.SecurityProperties;
import com.basicframework.framework.web.config.WebProperties;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthRefreshTokenCookieManagerTest {

    private AuthRefreshTokenCookieManager cookieManager;

    @BeforeEach
    void setUp() {
        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.getRefreshCookie().setSecure(true);
        WebProperties webProperties = new WebProperties();
        webProperties.setCorsAllowedOrigins(List.of("https://admin.example.com"));
        cookieManager = new AuthRefreshTokenCookieManager(securityProperties, webProperties);
    }

    @Test
    void issue_usesHostOnlyHardenedSessionCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookieManager.issue(response, "refresh-token");

        assertThat(response.getHeader("Set-Cookie"))
                .isEqualTo(
                        "basic_framework_refresh_token=refresh-token; Path=/admin-api/system/auth; Secure; HttpOnly; SameSite=Strict");
    }

    @Test
    void require_readsValidCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthRefreshTokenCookieManager.COOKIE_NAME, "refresh-token"));

        assertThat(cookieManager.require(request)).isEqualTo("refresh-token");
    }

    @Test
    void require_rejectsMissingOrOversizedCookie() {
        assertInvalid(new MockHttpServletRequest());
        MockHttpServletRequest oversized = new MockHttpServletRequest();
        oversized.setCookies(new Cookie(AuthRefreshTokenCookieManager.COOKIE_NAME, "x".repeat(129)));
        assertInvalid(oversized);
    }

    @Test
    void validateBrowserOrigin_allowsSameOriginAndConfiguredCorsOrigin() {
        MockHttpServletRequest sameOriginRequest = requestWithOrigin("https://api.example.com");
        sameOriginRequest.setScheme("https");
        sameOriginRequest.setServerName("api.example.com");
        sameOriginRequest.setServerPort(443);

        cookieManager.validateBrowserOrigin(sameOriginRequest);
        cookieManager.validateBrowserOrigin(requestWithOrigin("https://admin.example.com"));
        cookieManager.validateBrowserOrigin(new MockHttpServletRequest());
    }

    @Test
    void validateBrowserOrigin_rejectsUntrustedOrigin() {
        assertThatThrownBy(() -> cookieManager.validateBrowserOrigin(requestWithOrigin("https://attacker.example.com")))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(GlobalErrorCodeConstants.FORBIDDEN.getCode());
    }

    @Test
    void validateBrowserOrigin_allowsDevelopmentCorsPattern() {
        SecurityProperties securityProperties = new SecurityProperties();
        AuthRefreshTokenCookieManager developmentCookieManager =
                new AuthRefreshTokenCookieManager(securityProperties, new WebProperties());

        developmentCookieManager.validateBrowserOrigin(requestWithOrigin("http://localhost:5173"));
    }

    @Test
    void clear_expiresCookieAtSamePath() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookieManager.clear(response);

        assertThat(response.getHeader("Set-Cookie"))
                .startsWith("basic_framework_refresh_token=;")
                .contains(
                        "Path=/admin-api/system/auth",
                        "Max-Age=0",
                        "Expires=Thu, 1 Jan 1970 00:00:00 GMT",
                        "Secure",
                        "HttpOnly",
                        "SameSite=Strict");
    }

    @Test
    void issue_rejectsBlankOrOversizedToken() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> cookieManager.issue(response, ""))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode());
        assertThatThrownBy(() -> cookieManager.issue(response, "x".repeat(129)))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode());
    }

    @Test
    void read_ignoresUnrelatedCookiesAndReturnsNull() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("session", "a"), new Cookie("remember", "b"));

        assertThat(cookieManager.read(request)).isNull();
    }

    @Test
    void constructor_stripsTrailingSlashesFromAdminApiPrefix() {
        SecurityProperties securityProperties = new SecurityProperties();
        WebProperties webProperties = new WebProperties();
        webProperties.getAdminApi().setPrefix("/admin-api///");
        AuthRefreshTokenCookieManager trailingSlashManager =
                new AuthRefreshTokenCookieManager(securityProperties, webProperties);

        MockHttpServletResponse response = new MockHttpServletResponse();
        trailingSlashManager.issue(response, "refresh-token");

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("Path=/admin-api/system/auth");
    }

    @Test
    void validateBrowserOrigin_rejectsOriginCarryingRelativePath() {
        assertThatThrownBy(
                        () -> cookieManager.validateBrowserOrigin(requestWithOrigin("https://api.example.com/console")))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(GlobalErrorCodeConstants.FORBIDDEN.getCode());
    }

    @Test
    void validateBrowserOrigin_rejectsMalformedOriginUri() {
        assertThatThrownBy(() -> cookieManager.validateBrowserOrigin(requestWithOrigin("https://exa mple.com")))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(GlobalErrorCodeConstants.FORBIDDEN.getCode());
    }

    @Test
    void validateBrowserOrigin_allowsSameOriginWithDefaultHttpPort() {
        MockHttpServletRequest request = requestWithOrigin("http://example.com");
        request.setScheme("http");
        request.setServerName("example.com");
        request.setServerPort(-1);

        cookieManager.validateBrowserOrigin(request);
    }

    @Test
    void validateBrowserOrigin_allowsSameOriginWithNonStandardScheme() {
        MockHttpServletRequest request = requestWithOrigin("ftp://example.com");
        request.setScheme("ftp");
        request.setServerName("example.com");
        request.setServerPort(-1);

        cookieManager.validateBrowserOrigin(request);
    }

    private void assertInvalid(MockHttpServletRequest request) {
        assertThatThrownBy(() -> cookieManager.require(request))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode());
    }

    private static MockHttpServletRequest requestWithOrigin(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ORIGIN, origin);
        return request;
    }
}
