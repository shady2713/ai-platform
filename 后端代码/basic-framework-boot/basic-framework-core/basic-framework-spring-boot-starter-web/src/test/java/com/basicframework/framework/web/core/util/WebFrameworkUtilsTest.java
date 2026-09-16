package com.basicframework.framework.web.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.enums.TerminalEnum;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.web.config.WebProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class WebFrameworkUtilsTest {

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void getTerminal_acceptsPublishedAppValues() {
        bindTerminalHeader("32");

        assertThat(WebFrameworkUtils.getTerminal()).isEqualTo(TerminalEnum.ANDROID_APP.getTerminal());
    }

    @Test
    void getTerminal_mapsUnknownOrMalformedValuesToUnknown() {
        bindTerminalHeader("999");
        assertThat(WebFrameworkUtils.getTerminal()).isEqualTo(TerminalEnum.UNKNOWN.getTerminal());

        bindTerminalHeader("android");
        assertThat(WebFrameworkUtils.getTerminal()).isEqualTo(TerminalEnum.UNKNOWN.getTerminal());
    }

    @Test
    void getLoginUserType_matchesOnlyCompleteApiPrefix() {
        new WebFrameworkUtils(new WebProperties());

        MockHttpServletRequest adminRequest = new MockHttpServletRequest();
        adminRequest.setServletPath("/admin-api/system/user");
        assertThat(WebFrameworkUtils.getLoginUserType(adminRequest)).isEqualTo(UserTypeEnum.ADMIN.getValue());

        MockHttpServletRequest appRequest = new MockHttpServletRequest();
        appRequest.setServletPath("/app-api");
        assertThat(WebFrameworkUtils.getLoginUserType(appRequest)).isEqualTo(UserTypeEnum.MEMBER.getValue());

        MockHttpServletRequest unrelatedRequest = new MockHttpServletRequest();
        unrelatedRequest.setServletPath("/app-api-other/public");
        assertThat(WebFrameworkUtils.getLoginUserType(unrelatedRequest)).isNull();
    }

    @Test
    void requestAttributes_overridePrefixAndRoundTripFrameworkState() {
        new WebFrameworkUtils(new WebProperties());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/app-api/profile");
        CommonResult<String> result = CommonResult.success("ok");

        WebFrameworkUtils.setLoginUserId(request, 42L);
        WebFrameworkUtils.setLoginUserType(request, UserTypeEnum.ADMIN.getValue());
        WebFrameworkUtils.setCommonResult(request, result);

        assertThat(WebFrameworkUtils.getLoginUserId(request)).isEqualTo(42L);
        assertThat(WebFrameworkUtils.getLoginUserType(request)).isEqualTo(UserTypeEnum.ADMIN.getValue());
        assertThat(WebFrameworkUtils.getCommonResult(request)).isSameAs(result);
        assertThat(WebFrameworkUtils.getLoginUserId((MockHttpServletRequest) null))
                .isNull();
        assertThat(WebFrameworkUtils.getLoginUserType((MockHttpServletRequest) null))
                .isNull();
    }

    @Test
    void contextAccessors_areNullSafeAndReadBoundRequest() {
        new WebFrameworkUtils(new WebProperties());

        assertThat(WebFrameworkUtils.getRequest()).isNull();
        assertThat(WebFrameworkUtils.getLoginUserId()).isNull();
        assertThat(WebFrameworkUtils.getLoginUserType()).isNull();
        assertThat(WebFrameworkUtils.getTerminal()).isEqualTo(TerminalEnum.UNKNOWN.getTerminal());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/admin-api/system/user");
        WebFrameworkUtils.setLoginUserId(request, 7L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(WebFrameworkUtils.getRequest()).isSameAs(request);
        assertThat(WebFrameworkUtils.getLoginUserId()).isEqualTo(7L);
        assertThat(WebFrameworkUtils.getLoginUserType()).isEqualTo(UserTypeEnum.ADMIN.getValue());
    }

    @Test
    void rootApiPrefix_matchesAnyAbsoluteServletPath() {
        WebProperties properties = new WebProperties();
        properties.getAdminApi().setPrefix("/");
        properties.getAppApi().setPrefix("/member/");
        new WebFrameworkUtils(properties);
        MockHttpServletRequest rootRequest = new MockHttpServletRequest();
        rootRequest.setServletPath("/health");

        assertThat(WebFrameworkUtils.getLoginUserType(rootRequest)).isEqualTo(UserTypeEnum.ADMIN.getValue());
    }

    @Test
    void trailingSlashPrefix_isNormalizedBeforeMatching() {
        WebProperties properties = new WebProperties();
        properties.getAppApi().setPrefix("/member/");
        new WebFrameworkUtils(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/member/profile");

        assertThat(WebFrameworkUtils.getLoginUserType(request)).isEqualTo(UserTypeEnum.MEMBER.getValue());
    }

    private static void bindTerminalHeader(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(WebFrameworkUtils.HEADER_TERMINAL, value);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
