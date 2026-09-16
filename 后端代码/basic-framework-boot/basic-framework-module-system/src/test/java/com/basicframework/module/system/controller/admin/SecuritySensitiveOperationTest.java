package com.basicframework.module.system.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import com.basicframework.module.system.controller.admin.auth.AuthController;
import com.basicframework.module.system.controller.admin.auth.vo.AuthLoginRespVO;
import com.basicframework.module.system.controller.admin.permission.MenuController;
import com.basicframework.module.system.controller.admin.permission.PermissionController;
import com.basicframework.module.system.controller.admin.permission.RoleController;
import com.basicframework.module.system.controller.admin.session.UserSessionController;
import com.basicframework.module.system.controller.admin.session.vo.UserSessionRespVO;
import com.basicframework.module.system.controller.admin.sms.SmsChannelController;
import com.basicframework.module.system.controller.admin.user.UserController;
import com.basicframework.module.system.controller.admin.user.UserProfileController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestBody;

class SecuritySensitiveOperationTest {

    @Test
    void accountSecurityOperations_requireAuthorization() {
        assertProtectedMethods(
                UserController.class,
                "createUser",
                "updateUser",
                "deleteUser",
                "deleteUserList",
                "updateUserPassword",
                "updateUserStatus",
                "unlockLogin",
                "export",
                "importExcel");
        assertProtectedMethods(UserProfileController.class, "updateUserProfile", "updateUserProfilePassword");
    }

    @Test
    void authorizationModelOperations_requireAuthorization() {
        assertProtectedMethods(PermissionController.class, "assignRoleMenu", "assignRoleDataScope", "assignUserRole");
        assertProtectedMethods(MenuController.class, "createMenu", "updateMenu", "deleteMenu", "deleteMenuList");
        assertProtectedMethods(RoleController.class, "createRole", "updateRole", "deleteRole", "deleteRoleList");
    }

    @Test
    void credentialAndSessionOperations_requireAuthorization() {
        assertProtectedMethods(UserSessionController.class, "revokeSession", "revokeSessionList");
        assertProtectedMethods(
                SmsChannelController.class,
                "createSmsChannel",
                "updateSmsChannel",
                "deleteSmsChannel",
                "deleteSmsChannelList",
                "getSmsChannel",
                "getSmsChannelPage");
    }

    @Test
    void publicTokenEndpoints_areRateLimitedAndKeepSecretsOutOfQueryParameters() throws NoSuchMethodException {
        Method refreshToken = AuthController.class.getDeclaredMethod(
                "refreshToken", HttpServletRequest.class, HttpServletResponse.class);
        assertThat(refreshToken.isAnnotationPresent(RateLimiter.class)).isTrue();
        assertThat(Arrays.stream(refreshToken.getParameters())
                        .map(Parameter::getAnnotations)
                        .flatMap(Arrays::stream)
                        .noneMatch(RequestBody.class::isInstance))
                .isTrue();
        assertThat(AuthLoginRespVO.class.getDeclaredFields())
                .noneMatch(field -> field.getName().equals("refreshToken"));
    }

    @Test
    void sessionManagementContract_neverReturnsOrAcceptsBearerTokens() throws NoSuchMethodException {
        assertThat(UserSessionRespVO.class.getDeclaredFields())
                .noneMatch(field -> Set.of("accessToken", "refreshToken").contains(field.getName()));
        assertThat(UserSessionController.class.getDeclaredMethod("revokeSession", Long.class))
                .isNotNull();
        assertThat(UserSessionController.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().equals("revokeSessionList"))
                .hasSize(1)
                .allMatch(method -> method.getParameterTypes()[0].equals(java.util.List.class));
    }

    private static void assertProtectedMethods(Class<?> controllerType, String... expectedMethods) {
        Set<String> protectedMethods = Arrays.stream(controllerType.getDeclaredMethods())
                .filter(method ->
                        method.isAnnotationPresent(org.springframework.security.access.prepost.PreAuthorize.class)
                                || controllerType.isAnnotationPresent(
                                        com.basicframework.framework.security.core.annotation.AuthenticatedOnly.class))
                .map(method -> method.getName())
                .collect(Collectors.toSet());

        assertThat(protectedMethods).contains(expectedMethods);
    }
}
