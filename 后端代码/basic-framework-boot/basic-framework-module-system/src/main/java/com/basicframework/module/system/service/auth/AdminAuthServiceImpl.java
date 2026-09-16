package com.basicframework.module.system.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.util.servlet.ServletUtils.getClientIP;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;

import cn.hutool.core.util.ObjectUtil;
import com.anji.captcha.model.common.ResponseModel;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.util.monitor.TracerUtils;
import com.basicframework.framework.common.util.servlet.ServletUtils;
import com.basicframework.framework.datapermission.core.annotation.DataPermission;
import com.basicframework.module.system.convert.auth.AuthConvert;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.enums.logger.LoginResultEnum;
import com.basicframework.module.system.enums.permission.RoleCodeEnum;
import com.basicframework.module.system.enums.sms.SmsSceneEnum;
import com.basicframework.module.system.service.auth.dto.AuthChangeExpiredPasswordDTO;
import com.basicframework.module.system.service.auth.dto.AuthLoginDTO;
import com.basicframework.module.system.service.auth.dto.AuthLoginResultDTO;
import com.basicframework.module.system.service.auth.dto.AuthResetPasswordDTO;
import com.basicframework.module.system.service.auth.dto.AuthSmsSendDTO;
import com.basicframework.module.system.service.logger.LoginLogService;
import com.basicframework.module.system.service.logger.dto.LoginLogCreateReqDTO;
import com.basicframework.module.system.service.permission.PermissionService;
import com.basicframework.module.system.service.session.UserSessionService;
import com.basicframework.module.system.service.sms.SmsCodeService;
import com.basicframework.module.system.service.sms.dto.SmsCodeUseReqDTO;
import com.basicframework.module.system.service.user.AdminUserService;
import com.google.common.annotations.VisibleForTesting;
import java.util.Collections;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auth Service 实现类
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthServiceImpl implements AdminAuthService {

    private final AdminUserService userService;

    private final PermissionService permissionService;

    private final LoginLogService loginLogService;

    private final UserSessionService userSessionService;

    private final SmsCodeService smsCodeService;

    private final LoginProtectionService loginProtectionService;

    private final CaptchaVerificationService captchaVerificationService;

    private final PasswordTimingProtectionService passwordTimingProtectionService;

    @Override
    public AdminUserDO authenticate(String username, String password) {
        final LoginLogTypeEnum logTypeEnum = LoginLogTypeEnum.LOGIN_USERNAME;
        // 校验账号是否存在
        AdminUserDO user = userService.getUserByUsername(username);
        if (user == null) {
            passwordTimingProtectionService.verifyAgainstDummyHash(password);
            createLoginLog(null, username, logTypeEnum, LoginResultEnum.BAD_CREDENTIALS);
            throw exception(AUTH_LOGIN_BAD_CREDENTIALS);
        }
        if (loginProtectionService.isLocked(user.getId())) {
            passwordTimingProtectionService.verifyAgainstDummyHash(password);
            createLoginLog(user.getId(), username, logTypeEnum, LoginResultEnum.ACCOUNT_LOCKED);
            throw exception(AUTH_LOGIN_BAD_CREDENTIALS);
        }
        if (!userService.isPasswordMatch(password, user.getPassword())) {
            boolean locked = loginProtectionService.recordFailure(user.getId());
            createLoginLog(
                    user.getId(),
                    username,
                    logTypeEnum,
                    locked ? LoginResultEnum.ACCOUNT_LOCKED : LoginResultEnum.BAD_CREDENTIALS);
            throw exception(AUTH_LOGIN_BAD_CREDENTIALS);
        }
        // 校验是否禁用
        if (!CommonStatusEnum.ENABLE.getStatus().equals(user.getStatus())) {
            createLoginLog(user.getId(), username, logTypeEnum, LoginResultEnum.USER_DISABLED);
            throw exception(AUTH_LOGIN_USER_DISABLED);
        }
        loginProtectionService.clear(user.getId());
        // 初始口令视为已知口令：标记账号在签发任何会话前必须先改密
        if (!Boolean.FALSE.equals(user.getMustChangePassword())) {
            createLoginLog(user.getId(), username, logTypeEnum, LoginResultEnum.PASSWORD_EXPIRED);
            throw exception(AUTH_PASSWORD_EXPIRED);
        }
        user.setPassword(userService.upgradePasswordEncodingIfNeeded(user.getId(), password, user.getPassword()));
        return user;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @DataPermission(enable = false)
    public AuthLoginResultDTO login(AuthLoginDTO reqDTO) {
        // 校验验证码
        validateCaptcha(reqDTO);

        // 使用账号密码，进行登录
        AdminUserDO user = authenticate(reqDTO.getUsername(), reqDTO.getPassword());

        // 创建 Token 令牌，记录登录日志
        return AuthLoginResultDTO.token(createTokenAfterLoginSuccess(user, LoginLogTypeEnum.LOGIN_USERNAME));
    }

    @Override
    public void sendSmsCode(AuthSmsSendDTO reqDTO) {
        // 如果是重置密码场景，需要校验图形验证码是否正确
        if (Objects.equals(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene(), reqDTO.getScene())) {
            ResponseModel response = captchaVerificationService.verify(reqDTO);
            if (!response.isSuccess()) {
                throw exception(AUTH_REGISTER_CAPTCHA_CODE_ERROR, response.getRepMsg());
            }
        }

        // 匿名端点对未知手机号保持相同响应，避免泄露后台账号是否存在。
        if (userService.getUserByMobile(reqDTO.getMobile()) == null) {
            return;
        }
        // 发送验证码
        smsCodeService.sendSmsCode(AuthConvert.INSTANCE.convert(reqDTO).setCreateIp(getClientIP()));
    }

    private void createLoginLog(
            Long userId, String username, LoginLogTypeEnum logTypeEnum, LoginResultEnum loginResult) {
        // 插入登录日志
        LoginLogCreateReqDTO reqDTO = new LoginLogCreateReqDTO();
        reqDTO.setLogType(logTypeEnum.getType());
        reqDTO.setTraceId(TracerUtils.getTraceId());
        reqDTO.setUserId(userId);
        reqDTO.setUserType(getUserType().getValue());
        reqDTO.setUsername(username);
        reqDTO.setUserAgent(ServletUtils.getUserAgent());
        reqDTO.setUserIp(ServletUtils.getClientIP());
        reqDTO.setResult(loginResult.getResult());
        loginLogService.createLoginLog(reqDTO);
        // 更新最后登录时间
        if (userId != null && Objects.equals(LoginResultEnum.SUCCESS.getResult(), loginResult.getResult())) {
            userService.updateUserLogin(userId, ServletUtils.getClientIP());
        }
    }

    @VisibleForTesting
    void validateCaptcha(AuthLoginDTO reqDTO) {
        ResponseModel response = captchaVerificationService.verify(reqDTO);
        // 校验验证码
        if (!response.isSuccess()) {
            // 创建登录失败日志（验证码不正确)
            createLoginLog(
                    null, reqDTO.getUsername(), LoginLogTypeEnum.LOGIN_USERNAME, LoginResultEnum.CAPTCHA_CODE_ERROR);
            throw exception(AUTH_LOGIN_CAPTCHA_CODE_ERROR, response.getRepMsg());
        }
    }

    private UserSessionDO createTokenAfterLoginSuccess(AdminUserDO user, LoginLogTypeEnum logType) {
        UserSessionDO session =
                userSessionService.createSession(user.getId(), getUserType().getValue(), user.getPassword());
        // 成功日志与会话同事务提交；失败日志独立提交，避免外层回滚掩盖认证失败。
        createLoginLog(user.getId(), user.getUsername(), logType, LoginResultEnum.SUCCESS);
        return session;
    }

    @Override
    public UserSessionDO refreshToken(String refreshToken) {
        return userSessionService.refreshSession(refreshToken);
    }

    @Override
    public void logout(String token, Integer logType) {
        // 删除访问令牌
        UserSessionDO session = userSessionService.removeSessionByAccessToken(token);
        if (session == null) {
            return;
        }
        // 删除成功，则记录登出日志
        createLogoutLog(session.getUserId(), session.getUserType(), logType);
    }

    @Override
    public void logoutByAccessTokenId(Long operatorUserId, Long accessTokenId, Integer logType) {
        // 先读后删：撤销超管会话是特权操作，删除前必须完成目标身份校验
        UserSessionDO session = userSessionService.getSessionById(accessTokenId);
        if (session != null) {
            permissionService.validatePrivilegedUserMutation(
                    operatorUserId, Collections.singletonList(session.getUserId()));
        }
        session = userSessionService.removeSessionById(accessTokenId);
        if (session != null) {
            createLogoutLog(session.getUserId(), session.getUserType(), logType);
        }
    }

    @Override
    public void logoutByRefreshToken(String refreshToken, Integer logType) {
        UserSessionDO session = userSessionService.removeSessionByRefreshToken(refreshToken);
        if (session != null) {
            createLogoutLog(session.getUserId(), session.getUserType(), logType);
        }
    }

    private void createLogoutLog(Long userId, Integer userType, Integer logType) {
        LoginLogCreateReqDTO reqDTO = new LoginLogCreateReqDTO();
        reqDTO.setLogType(logType);
        reqDTO.setTraceId(TracerUtils.getTraceId());
        reqDTO.setUserId(userId);
        reqDTO.setUserType(userType);
        if (userId != null && ObjectUtil.equal(getUserType().getValue(), userType)) {
            reqDTO.setUsername(getUsername(userId));
        }
        reqDTO.setUserAgent(ServletUtils.getUserAgent());
        reqDTO.setUserIp(ServletUtils.getClientIP());
        reqDTO.setResult(LoginResultEnum.SUCCESS.getResult());
        loginLogService.createLoginLog(reqDTO);
    }

    private String getUsername(Long userId) {
        AdminUserDO user = userService.getUser(userId);
        return user != null ? user.getUsername() : null;
    }

    private UserTypeEnum getUserType() {
        return UserTypeEnum.ADMIN;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(AuthResetPasswordDTO reqDTO) {
        smsCodeService.useSmsCode(new SmsCodeUseReqDTO()
                .setCode(reqDTO.getCode())
                .setMobile(reqDTO.getMobile())
                .setScene(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene())
                .setUsedIp(getClientIP()));

        // 短信码校验成功但账号已删除时仍返回相同结果，避免形成账号枚举旁路。
        AdminUserDO userByMobile = userService.getUserByMobile(reqDTO.getMobile());
        if (userByMobile == null) {
            return;
        }
        // 匿名短信重置链不服务超管账号：超管凭证只经管理员重置链轮换，攻击者无法仅凭
        // 劫持手机号接管超管。与账号不存在保持相同的静默返回，避免借响应差异枚举超管
        // 手机号；命中即留下安全日志供告警。
        if (permissionService.hasAnyRoles(userByMobile.getId(), RoleCodeEnum.SUPER_ADMIN.getCode())) {
            log.warn("拒绝匿名短信重置超管账号密码: userId={}", userByMobile.getId());
            return;
        }
        // 手机号是本次身份证明的边界；锁定后复核，避免改绑手机号后仍用旧短信码改密。
        AdminUserDO currentUser = userService.getUserForSession(userByMobile.getId());
        if (currentUser == null || !Objects.equals(currentUser.getMobile(), userByMobile.getMobile())) {
            return;
        }
        // 机主本人已通过短信证明身份：改密后不再要求首登改密
        userService.completePasswordChange(userByMobile.getId(), reqDTO.getPassword());
        loginProtectionService.clear(userByMobile.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @DataPermission(enable = false)
    public void changeExpiredPassword(AuthChangeExpiredPasswordDTO reqDTO) {
        final LoginLogTypeEnum logTypeEnum = LoginLogTypeEnum.LOGIN_USERNAME;
        // 校验账号是否存在；未知账号保持与旧密码错误一致的响应与时序成本
        AdminUserDO user = userService.getUserByUsername(reqDTO.getUsername());
        if (user == null) {
            passwordTimingProtectionService.verifyAgainstDummyHash(reqDTO.getOldPassword());
            createLoginLog(null, reqDTO.getUsername(), logTypeEnum, LoginResultEnum.BAD_CREDENTIALS);
            throw exception(AUTH_LOGIN_BAD_CREDENTIALS);
        }
        if (loginProtectionService.isLocked(user.getId())) {
            passwordTimingProtectionService.verifyAgainstDummyHash(reqDTO.getOldPassword());
            createLoginLog(user.getId(), reqDTO.getUsername(), logTypeEnum, LoginResultEnum.ACCOUNT_LOCKED);
            throw exception(AUTH_LOGIN_BAD_CREDENTIALS);
        }
        if (!userService.isPasswordMatch(reqDTO.getOldPassword(), user.getPassword())) {
            boolean locked = loginProtectionService.recordFailure(user.getId());
            createLoginLog(
                    user.getId(),
                    reqDTO.getUsername(),
                    logTypeEnum,
                    locked ? LoginResultEnum.ACCOUNT_LOCKED : LoginResultEnum.BAD_CREDENTIALS);
            throw exception(AUTH_LOGIN_BAD_CREDENTIALS);
        }
        if (!CommonStatusEnum.ENABLE.getStatus().equals(user.getStatus())) {
            createLoginLog(user.getId(), reqDTO.getUsername(), logTypeEnum, LoginResultEnum.USER_DISABLED);
            throw exception(AUTH_LOGIN_USER_DISABLED);
        }
        // 旧密码已证明身份后才暴露账号状态语义，失败关闭：未标记账号不允许走认证前改密
        if (!Boolean.TRUE.equals(user.getMustChangePassword())) {
            throw exception(AUTH_PASSWORD_CHANGE_NOT_REQUIRED);
        }
        if (userService.isPasswordMatch(reqDTO.getNewPassword(), user.getPassword())) {
            throw exception(AUTH_PASSWORD_SAME_AS_OLD);
        }
        userService.completeExpiredPasswordChange(user.getId(), user.getPassword(), reqDTO.getNewPassword());
        loginProtectionService.clear(user.getId());
    }

    @Override
    public void unlockLogin(Long operatorUserId, Long userId) {
        // 登录锁定是暴力破解防线：解除超管账号的锁定仅限超管操作者，
        // 否则持 system:user:update 的普通管理员可先解锁再配合密码喷洒攻击超管
        permissionService.validatePrivilegedUserMutation(operatorUserId, Collections.singletonList(userId));
        if (userService.getUser(userId) == null) {
            throw exception(USER_NOT_EXISTS);
        }
        loginProtectionService.clear(userId);
    }
}
