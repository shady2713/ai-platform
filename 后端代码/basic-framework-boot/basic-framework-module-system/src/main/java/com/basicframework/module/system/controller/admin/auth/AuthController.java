package com.basicframework.module.system.controller.admin.auth;

import static com.basicframework.framework.common.pojo.CommonResult.success;
import static com.basicframework.framework.common.util.collection.CollectionUtils.convertSet;
import static com.basicframework.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.apilog.core.annotation.ApiAccessLog;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import com.basicframework.framework.ratelimiter.core.keyresolver.impl.ClientIpRateLimiterKeyResolver;
import com.basicframework.framework.security.config.SecurityProperties;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.system.controller.admin.auth.vo.*;
import com.basicframework.module.system.convert.auth.AuthConvert;
import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.service.auth.AdminAuthService;
import com.basicframework.module.system.service.auth.dto.AuthChangeExpiredPasswordDTO;
import com.basicframework.module.system.service.auth.dto.AuthLoginDTO;
import com.basicframework.module.system.service.auth.dto.AuthLoginResultDTO;
import com.basicframework.module.system.service.auth.dto.AuthResetPasswordDTO;
import com.basicframework.module.system.service.auth.dto.AuthSmsSendDTO;
import com.basicframework.module.system.service.permission.MenuService;
import com.basicframework.module.system.service.permission.PermissionService;
import com.basicframework.module.system.service.permission.RoleService;
import com.basicframework.module.system.service.user.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 管理后台的认证 Controller，提供登录、登出、获取用户信息等能力 */
@Tag(name = "管理后台 - 认证")
@RestController
@RequestMapping("/system/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AdminAuthService authService;

    private final AdminUserService userService;

    private final RoleService roleService;

    private final MenuService menuService;

    private final PermissionService permissionService;

    private final SecurityProperties securityProperties;

    private final AuthRefreshTokenCookieManager refreshTokenCookieManager;

    @PostMapping("/login")
    @PermitAll
    @Operation(summary = "使用账号密码登录")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    public CommonResult<AuthLoginRespVO> login(@RequestBody @Valid AuthLoginReqVO reqVO, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        return authenticationSuccess(authService.login(BeanUtils.toBean(reqVO, AuthLoginDTO.class)), response);
    }

    @PostMapping("/logout")
    @PermitAll
    @Operation(summary = "登出系统")
    @RateLimiter(time = 60, count = 30, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    public CommonResult<Boolean> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = SecurityFrameworkUtils.obtainAuthorization(request, securityProperties.getTokenHeader());
        if (StrUtil.isNotBlank(token)) {
            authService.logout(token, LoginLogTypeEnum.LOGOUT_SELF.getType());
        }
        String refreshToken = refreshTokenCookieManager.read(request);
        if (StrUtil.isNotBlank(refreshToken)) {
            refreshTokenCookieManager.validateBrowserOrigin(request);
            authService.logoutByRefreshToken(refreshToken, LoginLogTypeEnum.LOGOUT_SELF.getType());
        }
        refreshTokenCookieManager.clear(response);
        return success(true);
    }

    @PostMapping("/refresh-token")
    @PermitAll
    @Operation(summary = "刷新令牌")
    @RateLimiter(time = 60, count = 30, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    public CommonResult<AuthLoginRespVO> refreshToken(HttpServletRequest request, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        String refreshToken = refreshTokenCookieManager.require(request);
        refreshTokenCookieManager.validateBrowserOrigin(request);
        return authenticationSuccess(AuthLoginResultDTO.token(authService.refreshToken(refreshToken)), response);
    }

    @GetMapping("/get-permission-info")
    @Operation(summary = "获取登录用户的权限信息")
    @AuthenticatedOnly
    public CommonResult<AuthPermissionInfoRespVO> getPermissionInfo() {
        // 1.1 获得用户信息
        AdminUserDO user = userService.getUser(getLoginUserId());
        if (user == null) {
            return success(null);
        }

        // 1.2 获得角色列表
        Set<Long> roleIds = permissionService.getUserRoleIdListByUserId(getLoginUserId());
        if (CollUtil.isEmpty(roleIds)) {
            return success(AuthConvert.INSTANCE.convert(user, Collections.emptyList(), Collections.emptyList()));
        }
        List<RoleDO> roles = roleService.getRoleList(roleIds).stream()
                .filter(role -> CommonStatusEnum.ENABLE.getStatus().equals(role.getStatus()))
                .toList();

        // 1.3 获得菜单列表
        Set<Long> menuIds = permissionService.getRoleMenuListByRoleId(convertSet(roles, RoleDO::getId));
        List<MenuDO> menuList = menuService.getMenuList(menuIds);
        menuList = menuService.filterDisableMenus(menuList);

        // 2. 拼接结果返回
        return success(AuthConvert.INSTANCE.convert(user, roles, menuList));
    }

    @PostMapping("/send-sms-code")
    @PermitAll
    @Operation(summary = "发送手机验证码", description = "未知手机号同样返回受理成功，但不会发送短信")
    @RateLimiter(time = 60, count = 5, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    public CommonResult<Boolean> sendSmsCode(@RequestBody @Valid AuthSmsSendReqVO reqVO) {
        authService.sendSmsCode(BeanUtils.toBean(reqVO, AuthSmsSendDTO.class));
        return success(true);
    }

    @PostMapping("/reset-password")
    @PermitAll
    @Operation(summary = "重置密码", description = "短信码校验通过但账号已不存在时返回通用成功结果")
    @RateLimiter(time = 60, count = 5, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = "code")
    public CommonResult<Boolean> resetPassword(@RequestBody @Valid AuthResetPasswordReqVO reqVO) {
        authService.resetPassword(BeanUtils.toBean(reqVO, AuthResetPasswordDTO.class));
        return success(true);
    }

    @PostMapping("/change-expired-password")
    @PermitAll
    @Operation(summary = "认证前修改过期密码", description = "仅对 must_change_password 账号开放；旧密码错误计入登录失败锁定")
    @RateLimiter(time = 60, count = 5, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"oldPassword", "newPassword"})
    public CommonResult<Boolean> changeExpiredPassword(
            @RequestBody @Valid AuthChangeExpiredPasswordReqVO reqVO, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        authService.changeExpiredPassword(BeanUtils.toBean(reqVO, AuthChangeExpiredPasswordDTO.class));
        return success(true);
    }

    private static void disableAuthenticationResponseCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
    }

    private CommonResult<AuthLoginRespVO> authenticationSuccess(
            AuthLoginResultDTO result, HttpServletResponse response) {
        if (StrUtil.isNotBlank(result.getRefreshToken())) {
            refreshTokenCookieManager.issue(response, result.getRefreshToken());
        }
        return success(BeanUtils.toBean(result, AuthLoginRespVO.class));
    }
}
