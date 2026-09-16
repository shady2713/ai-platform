package com.basicframework.framework.security.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.framework.web.config.WebProperties;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityFrameworkUtilsTest {

    @BeforeEach
    void setUp() {
        new WebFrameworkUtils(new WebProperties());
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void obtainAuthorization_readsHeaderOnly() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer access-token");
        // URL 参数不再是取令牌通道，必须被忽略
        request.addParameter("token", "parameter-token");

        assertThat(SecurityFrameworkUtils.obtainAuthorization(request, "Authorization"))
                .isEqualTo("access-token");
    }

    @Test
    void obtainAuthorization_returnsRawHeaderWithoutBearerPrefix() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "raw-token");

        assertThat(SecurityFrameworkUtils.obtainAuthorization(request, "Authorization"))
                .isEqualTo("raw-token");
    }

    @Test
    void obtainAuthorization_returnsNullWhenHeaderMissingOrBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("token", "parameter-token");
        assertThat(SecurityFrameworkUtils.obtainAuthorization(request, "Authorization"))
                .isNull();

        MockHttpServletRequest blankRequest = new MockHttpServletRequest();
        blankRequest.addHeader("Authorization", " ");
        assertThat(SecurityFrameworkUtils.obtainAuthorization(blankRequest, "Authorization"))
                .isNull();
    }

    @Test
    void contextAccessors_readFromCurrentAuthentication() {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(7L);
        loginUser.setUserType(UserTypeEnum.ADMIN.getValue());
        loginUser.setInfo(Map.of(LoginUser.INFO_KEY_NICKNAME, "管理员", LoginUser.INFO_KEY_DEPT_ID, "100"));
        SecurityFrameworkUtils.setLoginUser(loginUser, new MockHttpServletRequest());

        assertThat(SecurityFrameworkUtils.getLoginUserId()).isEqualTo(7L);
        assertThat(SecurityFrameworkUtils.getLoginUserNickname()).isEqualTo("管理员");
        assertThat(SecurityFrameworkUtils.getLoginUserDeptId()).isEqualTo(100L);
    }

    @Test
    void contextAccessors_returnNullWithoutLoginUser() {
        assertThat(SecurityFrameworkUtils.getLoginUser()).isNull();
        assertThat(SecurityFrameworkUtils.getLoginUserId()).isNull();
        assertThat(SecurityFrameworkUtils.getLoginUserNickname()).isNull();
        assertThat(SecurityFrameworkUtils.getLoginUserDeptId()).isNull();

        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("anonymous", null));
        assertThat(SecurityFrameworkUtils.getLoginUser()).isNull();
    }
}
