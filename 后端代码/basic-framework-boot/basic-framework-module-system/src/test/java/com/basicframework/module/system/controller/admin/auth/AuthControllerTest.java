package com.basicframework.module.system.controller.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.security.config.SecurityProperties;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.system.controller.admin.auth.vo.AuthLoginReqVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthLoginRespVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthPermissionInfoRespVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthResetPasswordReqVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthSmsSendReqVO;
import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.enums.permission.MenuTypeEnum;
import com.basicframework.module.system.enums.sms.SmsSceneEnum;
import com.basicframework.module.system.service.auth.AdminAuthService;
import com.basicframework.module.system.service.auth.dto.AuthLoginDTO;
import com.basicframework.module.system.service.auth.dto.AuthLoginResultDTO;
import com.basicframework.module.system.service.permission.MenuService;
import com.basicframework.module.system.service.permission.PermissionService;
import com.basicframework.module.system.service.permission.RoleService;
import com.basicframework.module.system.service.user.AdminUserService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthControllerTest {

    private static final Long USER_ID = 23L;
    private static final String ACCESS_TOKEN = "access-token";
    private static final String REFRESH_TOKEN = "refresh-token";

    private final AdminAuthService authService = mock(AdminAuthService.class);
    private final AdminUserService userService = mock(AdminUserService.class);
    private final RoleService roleService = mock(RoleService.class);
    private final MenuService menuService = mock(MenuService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final SecurityProperties securityProperties = new SecurityProperties();
    private final AuthRefreshTokenCookieManager refreshTokenCookieManager = mock(AuthRefreshTokenCookieManager.class);
    private final AuthController controller = new AuthController(
            authService,
            userService,
            roleService,
            menuService,
            permissionService,
            securityProperties,
            refreshTokenCookieManager);

    @Test
    void login_issuesRefreshTokenAndDisablesAuthenticationCaching() {
        AuthLoginReqVO request = AuthLoginReqVO.builder()
                .username("admin")
                .password("CorrectPassword1")
                .build();
        when(authService.login(any(AuthLoginDTO.class))).thenReturn(tokenResult());
        MockHttpServletResponse response = new MockHttpServletResponse();

        CommonResult<AuthLoginRespVO> result = controller.login(request, response);

        assertThat(result.getData().getUserId()).isEqualTo(USER_ID);
        assertThat(result.getData().getAccessToken()).isEqualTo(ACCESS_TOKEN);
        assertAuthenticationCachingDisabled(response);
        verify(authService)
                .login(argThat(login ->
                        "admin".equals(login.getUsername()) && "CorrectPassword1".equals(login.getPassword())));
        verify(refreshTokenCookieManager).issue(response, REFRESH_TOKEN);
    }

    @Test
    void logout_revokesBothCredentialKindsAndClearsRefreshCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(refreshTokenCookieManager.read(request)).thenReturn(REFRESH_TOKEN);

        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockStatic(SecurityFrameworkUtils.class)) {
            securityUtils
                    .when(() ->
                            SecurityFrameworkUtils.obtainAuthorization(request, securityProperties.getTokenHeader()))
                    .thenReturn(ACCESS_TOKEN);

            CommonResult<Boolean> result = controller.logout(request, response);

            assertThat(result.getData()).isTrue();
        }

        verify(authService).logout(ACCESS_TOKEN, LoginLogTypeEnum.LOGOUT_SELF.getType());
        verify(authService).logoutByRefreshToken(REFRESH_TOKEN, LoginLogTypeEnum.LOGOUT_SELF.getType());
        verify(refreshTokenCookieManager).validateBrowserOrigin(request);
        verify(refreshTokenCookieManager).clear(response);
    }

    @Test
    void logout_stillClearsCookieWhenTheRequestHasNoCredentials() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(refreshTokenCookieManager.read(request)).thenReturn(null);

        try (MockedStatic<SecurityFrameworkUtils> ignored = mockStatic(SecurityFrameworkUtils.class)) {
            CommonResult<Boolean> result = controller.logout(request, response);

            assertThat(result.getData()).isTrue();
        }

        verify(authService, never()).logout(anyString(), any());
        verify(authService, never()).logoutByRefreshToken(anyString(), any());
        verify(refreshTokenCookieManager, never()).validateBrowserOrigin(request);
        verify(refreshTokenCookieManager).clear(response);
    }

    @Test
    void refreshToken_requiresCookieAndRotatesTheNewRefreshToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        UserSessionDO session = new UserSessionDO();
        session.setUserId(USER_ID);
        session.setAccessToken(ACCESS_TOKEN);
        session.setRefreshToken(REFRESH_TOKEN);
        session.setAccessExpiresTime(LocalDateTime.of(2026, 8, 31, 17, 0));
        when(refreshTokenCookieManager.require(request)).thenReturn(REFRESH_TOKEN);
        when(authService.refreshToken(REFRESH_TOKEN)).thenReturn(session);

        CommonResult<AuthLoginRespVO> result = controller.refreshToken(request, response);

        assertThat(result.getData().getAccessToken()).isEqualTo(ACCESS_TOKEN);
        assertAuthenticationCachingDisabled(response);
        verify(authService).refreshToken(REFRESH_TOKEN);
        verify(refreshTokenCookieManager).validateBrowserOrigin(request);
        verify(refreshTokenCookieManager).issue(response, REFRESH_TOKEN);
    }

    @Test
    void getPermissionInfo_returnsNullWhenTheCurrentUserNoLongerExists() {
        when(userService.getUser(USER_ID)).thenReturn(null);

        CommonResult<AuthPermissionInfoRespVO> result;
        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            result = controller.getPermissionInfo();
        }

        assertThat(result.getData()).isNull();
    }

    @Test
    void getPermissionInfo_returnsAnEmptyPermissionSetWithoutRoles() {
        when(userService.getUser(USER_ID)).thenReturn(user());
        when(permissionService.getUserRoleIdListByUserId(USER_ID)).thenReturn(Set.of());

        CommonResult<AuthPermissionInfoRespVO> result;
        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            result = controller.getPermissionInfo();
        }

        assertThat(result.getData().getRoles()).isEmpty();
        assertThat(result.getData().getPermissions()).isEmpty();
        assertThat(result.getData().getMenus()).isEmpty();
    }

    @Test
    void getPermissionInfo_filtersDisabledRolesAndBuildsAllowedMenuTree() {
        RoleDO enabledRole = role(1L, "admin", CommonStatusEnum.ENABLE.getStatus());
        RoleDO disabledRole = role(2L, "disabled", CommonStatusEnum.DISABLE.getStatus());
        MenuDO menu = new MenuDO();
        menu.setId(3L);
        menu.setParentId(MenuDO.ID_ROOT);
        menu.setType(MenuTypeEnum.MENU.getType());
        menu.setSort(1);
        menu.setName("Dashboard");
        menu.setPermission("system:dashboard:read");
        List<MenuDO> menus = new ArrayList<>(List.of(menu));
        when(userService.getUser(USER_ID)).thenReturn(user());
        when(permissionService.getUserRoleIdListByUserId(USER_ID)).thenReturn(Set.of(1L, 2L));
        when(roleService.getRoleList(Set.of(1L, 2L))).thenReturn(List.of(enabledRole, disabledRole));
        when(permissionService.getRoleMenuListByRoleId(Set.of(1L))).thenReturn(Set.of(3L));
        when(menuService.getMenuList(Set.of(3L))).thenReturn(menus);
        when(menuService.filterDisableMenus(menus)).thenReturn(menus);

        CommonResult<AuthPermissionInfoRespVO> result;
        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            result = controller.getPermissionInfo();
        }

        assertThat(result.getData().getRoles()).containsExactly("admin");
        assertThat(result.getData().getPermissions()).containsExactly("system:dashboard:read");
        assertThat(result.getData().getMenus())
                .singleElement()
                .extracting(AuthPermissionInfoRespVO.MenuVO::getName)
                .isEqualTo("Dashboard");
        verify(permissionService).getRoleMenuListByRoleId(Set.of(1L));
        verify(menuService).filterDisableMenus(menus);
    }

    @Test
    void smsAndPasswordResetEndpointsMapInput() {
        AuthSmsSendReqVO smsSendRequest = AuthSmsSendReqVO.builder()
                .mobile("13800138000")
                .scene(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene())
                .build();
        AuthResetPasswordReqVO resetPasswordRequest = AuthResetPasswordReqVO.builder()
                .mobile("13800138000")
                .code("123456")
                .password("CorrectPassword1")
                .build();

        CommonResult<Boolean> sendResult = controller.sendSmsCode(smsSendRequest);
        CommonResult<Boolean> resetResult = controller.resetPassword(resetPasswordRequest);

        assertThat(sendResult.getData()).isTrue();
        assertThat(resetResult.getData()).isTrue();
        verify(authService)
                .sendSmsCode(argThat(send -> "13800138000".equals(send.getMobile())
                        && SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene().equals(send.getScene())));
        verify(authService)
                .resetPassword(argThat(reset -> "CorrectPassword1".equals(reset.getPassword())
                        && "13800138000".equals(reset.getMobile())
                        && "123456".equals(reset.getCode())));
    }

    private static AuthLoginResultDTO tokenResult() {
        return AuthLoginResultDTO.builder()
                .userId(USER_ID)
                .accessToken(ACCESS_TOKEN)
                .refreshToken(REFRESH_TOKEN)
                .expiresTime(LocalDateTime.of(2026, 8, 31, 17, 0))
                .build();
    }

    private static AdminUserDO user() {
        AdminUserDO user = new AdminUserDO();
        user.setId(USER_ID);
        user.setUsername("admin");
        user.setNickname("管理员");
        return user;
    }

    private static RoleDO role(Long id, String code, Integer status) {
        RoleDO role = new RoleDO();
        role.setId(id);
        role.setCode(code);
        role.setStatus(status);
        return role;
    }

    private static MockedStatic<SecurityFrameworkUtils> mockCurrentUser() {
        MockedStatic<SecurityFrameworkUtils> securityUtils = mockStatic(SecurityFrameworkUtils.class);
        securityUtils.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(USER_ID);
        return securityUtils;
    }

    private static void assertAuthenticationCachingDisabled(MockHttpServletResponse response) {
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
    }

    @Test
    void changeExpiredPasswordDelegatesCredentialsWithoutCachingOrIssuingCookies() {
        var request = new com.basicframework.module.system.controller.admin.auth.vo.AuthChangeExpiredPasswordReqVO();
        request.setUsername("admin");
        request.setOldPassword("original-password");
        request.setNewPassword("different-password");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(controller.changeExpiredPassword(request, response).getData())
                .isTrue();
        assertAuthenticationCachingDisabled(response);
        verify(authService)
                .changeExpiredPassword(argThat(change -> "admin".equals(change.getUsername())
                        && "original-password".equals(change.getOldPassword())
                        && "different-password".equals(change.getNewPassword())));
        org.mockito.Mockito.verifyNoInteractions(refreshTokenCookieManager);
    }
}
