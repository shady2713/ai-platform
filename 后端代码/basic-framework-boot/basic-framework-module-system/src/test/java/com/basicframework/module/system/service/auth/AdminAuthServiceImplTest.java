package com.basicframework.module.system.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.anji.captcha.model.common.ResponseModel;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
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
import com.basicframework.module.system.service.sms.dto.SmsCodeSendReqDTO;
import com.basicframework.module.system.service.sms.dto.SmsCodeUseReqDTO;
import com.basicframework.module.system.service.user.AdminUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminAuthServiceImplTest {

    @InjectMocks
    private AdminAuthServiceImpl authService;

    @Mock
    private AdminUserService userService;

    @Mock
    private PermissionService permissionService;

    @Mock
    private LoginLogService loginLogService;

    @Mock
    private UserSessionService userSessionService;

    @Mock
    private SmsCodeService smsCodeService;

    @Mock
    private LoginProtectionService loginProtectionService;

    @Mock
    private CaptchaVerificationService captchaVerificationService;

    @Mock
    private PasswordTimingProtectionService passwordTimingProtectionService;

    @Test
    void login_rejectsCaptchaBeforeLookingUpOrAuthenticatingAccount() {
        AuthLoginDTO request = new AuthLoginDTO();
        request.setUsername("admin");
        ResponseModel rejectedCaptcha = mock(ResponseModel.class);
        when(rejectedCaptcha.isSuccess()).thenReturn(false);
        when(rejectedCaptcha.getRepMsg()).thenReturn("captcha invalid");
        when(captchaVerificationService.verify(request)).thenReturn(rejectedCaptcha);

        assertThatThrownBy(() -> authService.login(request)).hasMessageContaining("验证码不正确");

        verifyNoInteractions(userSessionService, loginProtectionService);
    }

    @Test
    void login_issuesSessionOnlyAfterCaptchaPasswordAndChecksSucceed() {
        AuthLoginDTO request = new AuthLoginDTO();
        request.setUsername("admin");
        request.setPassword("correct-password");
        AdminUserDO user = AdminUserDO.builder()
                .id(1L)
                .username("admin")
                .status(0)
                .mustChangePassword(false)
                .password("hash")
                .build();
        UserSessionDO session =
                new UserSessionDO().setUserId(1L).setAccessToken("access-token").setRefreshToken("refresh-token");
        when(captchaVerificationService.verify(request)).thenReturn(ResponseModel.success());
        when(userService.getUserByUsername(request.getUsername())).thenReturn(user);
        when(userService.isPasswordMatch(request.getPassword(), user.getPassword()))
                .thenReturn(true);
        when(userService.upgradePasswordEncodingIfNeeded(1L, "correct-password", "hash"))
                .thenReturn("upgraded-hash");
        when(userSessionService.createSession(1L, UserTypeEnum.ADMIN.getValue(), "upgraded-hash"))
                .thenReturn(session);

        AuthLoginResultDTO result = authService.login(request);

        assertThat(result.getAccessToken()).isEqualTo("access-token");
        assertThat(result.getRefreshToken()).isEqualTo("refresh-token");
        InOrder order = inOrder(userSessionService, loginLogService);
        order.verify(userSessionService).createSession(1L, UserTypeEnum.ADMIN.getValue(), "upgraded-hash");
        order.verify(loginLogService).createLoginLog(any(LoginLogCreateReqDTO.class));
    }

    @Test
    void authenticate_rejectsLockedAccountBeforePasswordCheck() {
        AdminUserDO user =
                AdminUserDO.builder().id(1L).username("admin").password("hash").build();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(loginProtectionService.isLocked(1L)).thenReturn(true);

        assertThatThrownBy(() -> authService.authenticate("admin", "password")).hasMessageContaining("账号密码不正确");

        verify(passwordTimingProtectionService).verifyAgainstDummyHash("password");
        verify(loginLogService).createLoginLog(org.mockito.ArgumentMatchers.any(LoginLogCreateReqDTO.class));
    }

    @Test
    void login_doesNotRecordSuccessWhenCredentialsChangeBeforeSessionIssuance() {
        AuthLoginDTO request = new AuthLoginDTO();
        request.setUsername("admin");
        request.setPassword("correct-password");
        AdminUserDO user = new AdminUserDO()
                .setId(1L)
                .setUsername("admin")
                .setPassword("hash")
                .setStatus(0)
                .setMustChangePassword(false);
        when(captchaVerificationService.verify(request)).thenReturn(ResponseModel.success());
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(userService.isPasswordMatch("correct-password", "hash")).thenReturn(true);
        when(userService.upgradePasswordEncodingIfNeeded(1L, "correct-password", "hash"))
                .thenReturn("hash");
        when(userSessionService.createSession(1L, UserTypeEnum.ADMIN.getValue(), "hash"))
                .thenThrow(com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception(
                        com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_LOGIN_BAD_CREDENTIALS));

        assertThatThrownBy(() -> authService.login(request)).hasMessageContaining("账号密码不正确");

        verifyNoInteractions(loginLogService);
        verify(userService, never()).updateUserLogin(anyLong(), any());
    }

    @Test
    void authenticate_rejectsUnknownUsernameWithTheSameBadCredentialsResponse() {
        when(userService.getUserByUsername("unknown")).thenReturn(null);

        assertThatThrownBy(() -> authService.authenticate("unknown", "password"))
                .hasMessageContaining("账号密码不正确");

        verify(passwordTimingProtectionService).verifyAgainstDummyHash("password");
        verifyNoInteractions(loginProtectionService);
    }

    @Test
    void authenticate_fifthFailureLocksAccount() {
        AdminUserDO user =
                AdminUserDO.builder().id(1L).username("admin").password("hash").build();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(userService.isPasswordMatch("wrong-password", "hash")).thenReturn(false);
        when(loginProtectionService.recordFailure(1L)).thenReturn(true);

        assertThatThrownBy(() -> authService.authenticate("admin", "wrong-password"))
                .hasMessageContaining("账号密码不正确");

        verify(loginProtectionService).recordFailure(1L);
    }

    @Test
    void authenticate_recordsOrdinaryBadCredentialsBeforeTheLockThreshold() {
        AdminUserDO user =
                AdminUserDO.builder().id(1L).username("admin").password("hash").build();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(userService.isPasswordMatch("wrong-password", "hash")).thenReturn(false);
        when(loginProtectionService.recordFailure(1L)).thenReturn(false);

        assertThatThrownBy(() -> authService.authenticate("admin", "wrong-password"))
                .hasMessageContaining("账号密码不正确");

        verify(loginProtectionService).recordFailure(1L);
    }

    @Test
    void authenticate_successClearsFailures() {
        AdminUserDO user = AdminUserDO.builder()
                .id(1L)
                .username("admin")
                .status(0)
                .mustChangePassword(false)
                .password("hash")
                .build();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(userService.isPasswordMatch("correct-password", "hash")).thenReturn(true);

        assertThat(authService.authenticate("admin", "correct-password")).isSameAs(user);

        verify(userService).upgradePasswordEncodingIfNeeded(1L, "correct-password", "hash");
        verify(loginProtectionService).clear(1L);
    }

    @Test
    void authenticate_rejectsDisabledAccountAfterVerifyingPassword() {
        AdminUserDO user = AdminUserDO.builder()
                .id(1L)
                .username("admin")
                .status(1)
                .password("hash")
                .build();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(userService.isPasswordMatch("correct-password", "hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.authenticate("admin", "correct-password"))
                .hasMessageContaining("账号被禁用");

        verify(userService, never())
                .upgradePasswordEncodingIfNeeded(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void sendSmsCode_rejectsResetPasswordRequestWhenCaptchaFailsBeforeAccountLookup() {
        AuthSmsSendDTO request = new AuthSmsSendDTO();
        request.setMobile("13900000001");
        request.setScene(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene());
        ResponseModel rejectedCaptcha = mock(ResponseModel.class);
        when(rejectedCaptcha.isSuccess()).thenReturn(false);
        when(rejectedCaptcha.getRepMsg()).thenReturn("captcha invalid");
        when(captchaVerificationService.verify(request)).thenReturn(rejectedCaptcha);

        assertThatThrownBy(() -> authService.sendSmsCode(request)).hasMessageContaining("验证码不正确");

        verifyNoInteractions(userService, smsCodeService);
    }

    @Test
    void sendSmsCode_returnsGenericAcceptanceForUnknownAccountAndSendsOnlyForExistingAccount() {
        AuthSmsSendDTO missingUserRequest = new AuthSmsSendDTO();
        missingUserRequest.setMobile("13900000001");
        missingUserRequest.setScene(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene());
        when(userService.getUserByMobile(missingUserRequest.getMobile())).thenReturn(null);
        ResponseModel acceptedCaptcha = mock(ResponseModel.class);
        when(acceptedCaptcha.isSuccess()).thenReturn(true);
        when(captchaVerificationService.verify(missingUserRequest)).thenReturn(acceptedCaptcha);

        authService.sendSmsCode(missingUserRequest);

        verifyNoInteractions(smsCodeService);

        AuthSmsSendDTO validRequest = new AuthSmsSendDTO();
        validRequest.setMobile("13900000002");
        validRequest.setScene(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene());
        when(userService.getUserByMobile(validRequest.getMobile()))
                .thenReturn(AdminUserDO.builder().id(2L).build());
        when(captchaVerificationService.verify(validRequest)).thenReturn(acceptedCaptcha);

        authService.sendSmsCode(validRequest);

        ArgumentCaptor<SmsCodeSendReqDTO> smsCodeCaptor = ArgumentCaptor.forClass(SmsCodeSendReqDTO.class);
        verify(smsCodeService).sendSmsCode(smsCodeCaptor.capture());
        assertThat(smsCodeCaptor.getValue().getMobile()).isEqualTo(validRequest.getMobile());
        assertThat(smsCodeCaptor.getValue().getScene()).isEqualTo(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene());
    }

    @Test
    void logoutByAccessTokenId_recordsTheRevokedUser() {
        UserSessionDO session = new UserSessionDO().setId(10L).setUserId(1L).setUserType(UserTypeEnum.ADMIN.getValue());
        when(userSessionService.getSessionById(10L)).thenReturn(session);
        when(userSessionService.removeSessionById(10L)).thenReturn(session);
        when(userService.getUser(1L))
                .thenReturn(AdminUserDO.builder().id(1L).username("admin").build());

        authService.logoutByAccessTokenId(9L, 10L, LoginLogTypeEnum.LOGOUT_DELETE.getType());

        ArgumentCaptor<LoginLogCreateReqDTO> log = ArgumentCaptor.forClass(LoginLogCreateReqDTO.class);
        verify(loginLogService).createLoginLog(log.capture());
        assertThat(log.getValue().getUserId()).isEqualTo(1L);
        assertThat(log.getValue().getUsername()).isEqualTo("admin");
    }

    @Test
    void logoutByAccessTokenId_rejectsPrivilegedSessionForPlainOperator() {
        UserSessionDO session = new UserSessionDO().setId(12L).setUserId(1L).setUserType(UserTypeEnum.ADMIN.getValue());
        when(userSessionService.getSessionById(12L)).thenReturn(session);
        doThrow(exception(ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN))
                .when(permissionService)
                .validatePrivilegedUserMutation(eq(9L), argThat(ids -> ids.contains(1L)));

        assertThatThrownBy(() -> authService.logoutByAccessTokenId(9L, 12L, LoginLogTypeEnum.LOGOUT_DELETE.getType()))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN.getCode()));
        verify(userSessionService, never()).removeSessionById(anyLong());
        verifyNoInteractions(loginLogService);
    }

    @Test
    void logoutByAccessTokenId_recordsNullUsernameWhenUserWasDeletedBeforeLogout() {
        UserSessionDO session = new UserSessionDO().setId(11L).setUserId(2L).setUserType(UserTypeEnum.ADMIN.getValue());
        when(userSessionService.getSessionById(11L)).thenReturn(session);
        when(userSessionService.removeSessionById(11L)).thenReturn(session);
        when(userService.getUser(2L)).thenReturn(null);

        authService.logoutByAccessTokenId(9L, 11L, LoginLogTypeEnum.LOGOUT_DELETE.getType());

        ArgumentCaptor<LoginLogCreateReqDTO> log = ArgumentCaptor.forClass(LoginLogCreateReqDTO.class);
        verify(loginLogService).createLoginLog(log.capture());
        assertThat(log.getValue().getUserId()).isEqualTo(2L);
        assertThat(log.getValue().getUsername()).isNull();
    }

    @Test
    void refreshToken_delegatesToSessionService() {
        UserSessionDO refreshedSession = new UserSessionDO().setId(10L);
        when(userSessionService.refreshSession("refresh-token")).thenReturn(refreshedSession);

        UserSessionDO result = authService.refreshToken("refresh-token");

        assertThat(result).isSameAs(refreshedSession);
        verify(userSessionService).refreshSession("refresh-token");
    }

    @Test
    void logout_removesSessionFirstAndRecordsOnlySuccessfulRevocations() {
        UserSessionDO session = new UserSessionDO().setUserId(1L).setUserType(UserTypeEnum.ADMIN.getValue());
        when(userSessionService.removeSessionByAccessToken("missing")).thenReturn(null);
        when(userSessionService.removeSessionByAccessToken("active")).thenReturn(session);
        when(userService.getUser(1L))
                .thenReturn(AdminUserDO.builder().id(1L).username("admin").build());

        authService.logout("missing", LoginLogTypeEnum.LOGOUT_SELF.getType());
        verifyNoInteractions(loginLogService);

        authService.logout("active", LoginLogTypeEnum.LOGOUT_SELF.getType());

        verify(loginLogService).createLoginLog(org.mockito.ArgumentMatchers.any(LoginLogCreateReqDTO.class));
    }

    @Test
    void logoutByRefreshToken_recordsOnlyTheSessionActuallyRevoked() {
        UserSessionDO session = new UserSessionDO().setUserId(1L).setUserType(UserTypeEnum.ADMIN.getValue());
        when(userSessionService.removeSessionByRefreshToken("refresh-token")).thenReturn(session);
        when(userService.getUser(1L))
                .thenReturn(AdminUserDO.builder().id(1L).username("admin").build());

        authService.logoutByRefreshToken("refresh-token", LoginLogTypeEnum.LOGOUT_SELF.getType());

        verify(loginLogService).createLoginLog(org.mockito.ArgumentMatchers.any(LoginLogCreateReqDTO.class));
    }

    @Test
    void resetPassword_consumesResetSmsCodeAndClearsAccountLock() {
        AuthResetPasswordDTO request = new AuthResetPasswordDTO();
        request.setMobile("13900000001");
        request.setCode("123456");
        request.setPassword("new-password");
        when(userService.getUserByMobile(request.getMobile()))
                .thenReturn(
                        AdminUserDO.builder().id(1L).mobile(request.getMobile()).build());
        when(userService.getUserForSession(1L))
                .thenReturn(
                        AdminUserDO.builder().id(1L).mobile(request.getMobile()).build());

        authService.resetPassword(request);

        ArgumentCaptor<SmsCodeUseReqDTO> smsCodeCaptor = ArgumentCaptor.forClass(SmsCodeUseReqDTO.class);
        verify(smsCodeService).useSmsCode(smsCodeCaptor.capture());
        assertThat(smsCodeCaptor.getValue().getMobile()).isEqualTo(request.getMobile());
        assertThat(smsCodeCaptor.getValue().getCode()).isEqualTo(request.getCode());
        assertThat(smsCodeCaptor.getValue().getScene()).isEqualTo(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene());
        verify(userService).completePasswordChange(1L, request.getPassword());
        verify(loginProtectionService).clear(1L);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(
            value = {"13900000002,false", "NULL,false", "NULL,true"},
            nullValues = "NULL")
    void resetPassword_doesNotUseOldPhoneProofAfterConcurrentUnbindRebindOrDeletion(
            String currentMobile, boolean deleted) {
        AuthResetPasswordDTO request = new AuthResetPasswordDTO()
                .setMobile("13900000001")
                .setCode("123456")
                .setPassword("new-password");
        when(userService.getUserByMobile(request.getMobile()))
                .thenReturn(
                        AdminUserDO.builder().id(1L).mobile(request.getMobile()).build());
        when(userService.getUserForSession(1L))
                .thenReturn(
                        deleted
                                ? null
                                : AdminUserDO.builder()
                                        .id(1L)
                                        .mobile(currentMobile)
                                        .build());
        authService.resetPassword(request);
        verify(userService, never()).completePasswordChange(anyLong(), anyString());
        verifyNoInteractions(loginProtectionService);
    }

    @Test
    void resetPassword_consumesCodeBeforeLookupAndReturnsGenericSuccessWhenAccountWasDeleted() {
        AuthResetPasswordDTO request = new AuthResetPasswordDTO();
        request.setMobile("13900000001");
        request.setCode("123456");
        request.setPassword("new-password");
        when(userService.getUserByMobile(request.getMobile())).thenReturn(null);

        authService.resetPassword(request);

        ArgumentCaptor<SmsCodeUseReqDTO> smsCodeCaptor = ArgumentCaptor.forClass(SmsCodeUseReqDTO.class);
        InOrder order = inOrder(smsCodeService, userService);
        order.verify(smsCodeService).useSmsCode(smsCodeCaptor.capture());
        order.verify(userService).getUserByMobile(request.getMobile());
        assertThat(smsCodeCaptor.getValue().getScene()).isEqualTo(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene());
        verify(userService, never()).completePasswordChange(anyLong(), anyString());
        verify(userService, never()).completeExpiredPasswordChange(anyLong(), anyString(), anyString());
        verifyNoInteractions(loginProtectionService);
    }

    @Test
    void resetPassword_silentlySkipsPrivilegedAccount() {
        AuthResetPasswordDTO request = new AuthResetPasswordDTO();
        request.setMobile("13900000001");
        request.setCode("123456");
        request.setPassword("new-password");
        when(userService.getUserByMobile(request.getMobile()))
                .thenReturn(
                        AdminUserDO.builder().id(1L).mobile(request.getMobile()).build());
        when(permissionService.hasAnyRoles(1L, RoleCodeEnum.SUPER_ADMIN.getCode()))
                .thenReturn(true);

        authService.resetPassword(request);

        verify(smsCodeService).useSmsCode(any(SmsCodeUseReqDTO.class));
        verify(userService, never()).completePasswordChange(anyLong(), anyString());
        verifyNoInteractions(loginProtectionService);
    }

    @Test
    void authenticate_rejectsMustChangePasswordAccountBeforeIssuingAnySession() {
        AdminUserDO user = AdminUserDO.builder()
                .id(1L)
                .username("admin")
                .status(0)
                .password("hash")
                .mustChangePassword(true)
                .build();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(userService.isPasswordMatch("correct-password", "hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.authenticate("admin", "correct-password"))
                .hasMessageContaining("密码已过期");

        ArgumentCaptor<LoginLogCreateReqDTO> log = ArgumentCaptor.forClass(LoginLogCreateReqDTO.class);
        verify(loginLogService).createLoginLog(log.capture());
        assertThat(log.getValue().getResult()).isEqualTo(LoginResultEnum.PASSWORD_EXPIRED.getResult());
        // 旧口令已验证成功，失败计数仍然清零；但不签发会话
        verify(loginProtectionService).clear(1L);
        verifyNoInteractions(userSessionService);
    }

    @Test
    void authenticate_allowsAccountWithClearedMustChangePasswordFlag() {
        AdminUserDO user = AdminUserDO.builder()
                .id(1L)
                .username("admin")
                .status(0)
                .password("hash")
                .mustChangePassword(false)
                .build();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(userService.isPasswordMatch("correct-password", "hash")).thenReturn(true);

        assertThat(authService.authenticate("admin", "correct-password")).isSameAs(user);

        verify(loginProtectionService).clear(1L);
    }

    @Test
    void changeExpiredPassword_rejectsUnknownUsernameWithDummyHashAndBadCredentials() {
        AuthChangeExpiredPasswordDTO request = expiredPasswordRequest("ghost");
        when(userService.getUserByUsername("ghost")).thenReturn(null);

        assertThatThrownBy(() -> authService.changeExpiredPassword(request)).hasMessageContaining("账号密码不正确");

        verify(passwordTimingProtectionService).verifyAgainstDummyHash("old-password");
        verify(userService, never()).completePasswordChange(anyLong(), anyString());
        verify(userService, never()).completeExpiredPasswordChange(anyLong(), anyString(), anyString());
    }

    @Test
    void changeExpiredPassword_rejectsLockedAccountWithDummyHashAndBadCredentials() {
        AuthChangeExpiredPasswordDTO request = expiredPasswordRequest("admin");
        AdminUserDO user = mustChangeUser();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(loginProtectionService.isLocked(1L)).thenReturn(true);

        assertThatThrownBy(() -> authService.changeExpiredPassword(request)).hasMessageContaining("账号密码不正确");

        verify(passwordTimingProtectionService).verifyAgainstDummyHash("old-password");
        verify(userService, never()).completePasswordChange(anyLong(), anyString());
        verify(userService, never()).completeExpiredPasswordChange(anyLong(), anyString(), anyString());
    }

    @Test
    void changeExpiredPassword_recordsFailureAndRejectsWhenOldPasswordMismatch() {
        AuthChangeExpiredPasswordDTO request = expiredPasswordRequest("admin");
        request.setOldPassword("wrong-password");
        AdminUserDO user = mustChangeUser();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(userService.isPasswordMatch("wrong-password", "hash")).thenReturn(false);
        when(loginProtectionService.recordFailure(1L)).thenReturn(true);

        assertThatThrownBy(() -> authService.changeExpiredPassword(request)).hasMessageContaining("账号密码不正确");

        verify(loginProtectionService).recordFailure(1L);
        ArgumentCaptor<LoginLogCreateReqDTO> log = ArgumentCaptor.forClass(LoginLogCreateReqDTO.class);
        verify(loginLogService).createLoginLog(log.capture());
        assertThat(log.getValue().getResult()).isEqualTo(LoginResultEnum.ACCOUNT_LOCKED.getResult());
        verify(userService, never()).completePasswordChange(anyLong(), anyString());
        verify(userService, never()).completeExpiredPasswordChange(anyLong(), anyString(), anyString());
    }

    @Test
    void changeExpiredPassword_rejectsAccountWithoutMustChangeFlag() {
        AuthChangeExpiredPasswordDTO request = expiredPasswordRequest("admin");
        AdminUserDO user = AdminUserDO.builder()
                .id(1L)
                .username("admin")
                .password("hash")
                .status(0)
                .mustChangePassword(false)
                .build();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(userService.isPasswordMatch("old-password", "hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.changeExpiredPassword(request)).hasMessageContaining("无需强制修改密码");

        verify(userService, never()).completePasswordChange(anyLong(), anyString());
        verify(userService, never()).completeExpiredPasswordChange(anyLong(), anyString(), anyString());
    }

    @Test
    void changeExpiredPassword_rejectsNewPasswordEqualToOld() {
        AuthChangeExpiredPasswordDTO request = expiredPasswordRequest("admin");
        AdminUserDO user = mustChangeUser();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(userService.isPasswordMatch("old-password", "hash")).thenReturn(true);
        when(userService.isPasswordMatch("brand-new-password", "hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.changeExpiredPassword(request)).hasMessageContaining("不能与原密码相同");

        verify(userService, never()).completePasswordChange(anyLong(), anyString());
        verify(userService, never()).completeExpiredPasswordChange(anyLong(), anyString(), anyString());
    }

    @Test
    void changeExpiredPassword_successChangesPasswordAtomicallyWithoutIssuingSession() {
        AuthChangeExpiredPasswordDTO request = expiredPasswordRequest("admin");
        AdminUserDO user = mustChangeUser();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(userService.isPasswordMatch("old-password", "hash")).thenReturn(true);
        when(userService.isPasswordMatch("brand-new-password", "hash")).thenReturn(false);

        authService.changeExpiredPassword(request);

        verify(userService).completeExpiredPasswordChange(1L, "hash", "brand-new-password");
        verify(loginProtectionService).clear(1L);
        verifyNoInteractions(loginLogService);
        // 改密完成不等于登录成功：不签发会话
        verifyNoInteractions(userSessionService);
    }

    @Test
    void changeExpiredPassword_rejectsDisabledAccountWithoutMutation() {
        AdminUserDO user = mustChangeUser().setStatus(1);
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(userService.isPasswordMatch("old-password", "hash")).thenReturn(true);
        assertThatThrownBy(() -> authService.changeExpiredPassword(expiredPasswordRequest("admin")))
                .isInstanceOf(com.basicframework.framework.common.exception.ServiceException.class);
        verify(userService, never()).completeExpiredPasswordChange(anyLong(), anyString(), anyString());
        verify(loginLogService).createLoginLog(any());
        verifyNoInteractions(userSessionService);
    }

    private static AuthChangeExpiredPasswordDTO expiredPasswordRequest(String username) {
        AuthChangeExpiredPasswordDTO request = new AuthChangeExpiredPasswordDTO();
        request.setUsername(username);
        request.setOldPassword("old-password");
        request.setNewPassword("brand-new-password");
        return request;
    }

    private static AdminUserDO mustChangeUser() {
        return AdminUserDO.builder()
                .id(1L)
                .username("admin")
                .status(0)
                .password("hash")
                .mustChangePassword(true)
                .build();
    }

    @Test
    void unlockLogin_onlyClearsLockForExistingUser() {
        when(userService.getUser(1L)).thenReturn(AdminUserDO.builder().id(1L).build());

        authService.unlockLogin(9L, 1L);

        verify(permissionService).validatePrivilegedUserMutation(9L, java.util.Collections.singletonList(1L));
        verify(loginProtectionService).clear(1L);
        assertThatThrownBy(() -> authService.unlockLogin(9L, 2L)).hasMessageContaining("用户不存在");
    }

    @Test
    void unlockLogin_rejectsPrivilegedTargetForPlainOperator() {
        doThrow(exception(ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN))
                .when(permissionService)
                .validatePrivilegedUserMutation(eq(9L), argThat(ids -> ids.contains(1L)));

        assertThatThrownBy(() -> authService.unlockLogin(9L, 1L))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN.getCode()));
        verifyNoInteractions(loginProtectionService);
    }
}
