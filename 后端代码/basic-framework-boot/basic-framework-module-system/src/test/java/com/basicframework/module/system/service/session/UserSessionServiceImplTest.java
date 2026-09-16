package com.basicframework.module.system.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.config.SessionProperties;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.session.UserSessionMapper;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import com.basicframework.module.system.service.metrics.SecuritySignalMetrics;
import com.basicframework.module.system.service.user.AdminUserService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserSessionServiceImplTest {

    @InjectMocks
    private UserSessionServiceImpl sessionService;

    @Mock
    private UserSessionMapper userSessionMapper;

    @Mock
    private SessionProperties sessionProperties;

    @Mock
    private AdminUserService adminUserService;

    @Mock
    private SecuritySignalMetrics securitySignalMetrics;

    @Test
    void createSession_generatesIndependent256BitTokensAndOneRow() {
        stubCreateDependencies();
        UserSessionDO session = sessionService.createSession(1L, UserTypeEnum.ADMIN.getValue(), "hash");

        assertThat(session.getAccessToken()).matches("^[0-9a-f]{64}$");
        assertThat(session.getRefreshToken()).matches("^[0-9a-f]{128}$");
        assertThat(session.getAccessToken()).isNotEqualTo(session.getRefreshToken());
        assertThat(session.getAccessTokenHash()).isEqualTo(SessionTokenDigest.digest(session.getAccessToken()));
        assertThat(session.getRefreshTokenHash()).isEqualTo(SessionTokenDigest.digest(session.getRefreshToken()));
        assertThat(session.getRefreshFamilyHash())
                .isEqualTo(SessionTokenDigest.digest(session.getRefreshToken().substring(0, 64)));
        assertThat(session.getAccessExpiresTime()).isBefore(session.getRefreshExpiresTime());
        assertThat(session.getUserInfo()).containsEntry("nickname", "tester");
        verify(userSessionMapper).insert(session);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void createSession_rejectsInvalidUserId(Long id) {
        assertThatThrownBy(() -> sessionService.createSession(id, UserTypeEnum.ADMIN.getValue(), "hash"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(userSessionMapper, never()).insert(any(UserSessionDO.class));
    }

    @Test
    void createSession_rejectsUnsupportedUserType() {
        assertThatThrownBy(() -> sessionService.createSession(1L, UserTypeEnum.MEMBER.getValue(), "hash"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void createSession_requiresAnAuthenticatedCredential(String expectedPassword) {
        assertCode(
                () -> sessionService.createSession(1L, UserTypeEnum.ADMIN.getValue(), expectedPassword),
                ErrorCodeConstants.AUTH_LOGIN_BAD_CREDENTIALS.getCode());
        verify(adminUserService, never()).getUserForSession(any());
        verify(userSessionMapper, never()).insert(any(UserSessionDO.class));
    }

    @Test
    void createSession_rejectsPasswordChangedSinceAuthentication() {
        when(adminUserService.getUserForSession(1L)).thenReturn(activeUser().setPassword("reset-hash"));
        assertCode(
                () -> sessionService.createSession(1L, UserTypeEnum.ADMIN.getValue(), "hash"),
                ErrorCodeConstants.AUTH_LOGIN_BAD_CREDENTIALS.getCode());
        verify(userSessionMapper, never()).insert(any(UserSessionDO.class));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {1, 2})
    void createSession_failsClosedForDisabledOrUnknownStatus(Integer status) {
        when(adminUserService.getUserForSession(1L)).thenReturn(activeUser().setStatus(status));
        assertCode(
                () -> sessionService.createSession(1L, UserTypeEnum.ADMIN.getValue(), "hash"),
                ErrorCodeConstants.AUTH_LOGIN_USER_DISABLED.getCode());
        verify(userSessionMapper, never()).insert(any(UserSessionDO.class));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = {true})
    void createSession_requiresExplicitlyCompletedPasswordRotation(Boolean mustChangePassword) {
        when(adminUserService.getUserForSession(1L)).thenReturn(activeUser().setMustChangePassword(mustChangePassword));
        assertCode(
                () -> sessionService.createSession(1L, UserTypeEnum.ADMIN.getValue(), "hash"),
                ErrorCodeConstants.AUTH_PASSWORD_EXPIRED.getCode());
        verify(userSessionMapper, never()).insert(any(UserSessionDO.class));
    }

    @Test
    void createSession_rejectsDeletedUser() {
        assertCode(
                () -> sessionService.createSession(1L, UserTypeEnum.ADMIN.getValue(), "hash"),
                ErrorCodeConstants.USER_NOT_EXISTS.getCode());
    }

    @Test
    void createSession_largeSampleHasNoRepeatedToken() {
        stubCreateDependencies();
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            UserSessionDO session = sessionService.createSession(1L, UserTypeEnum.ADMIN.getValue(), "hash");
            assertThat(tokens.add(session.getAccessToken())).isTrue();
            assertThat(tokens.add(session.getRefreshToken())).isTrue();
        }
    }

    @Test
    void refreshSession_rotatesOnceUsingCurrentLockedIdentityAndKeepsAbsoluteExpiry() {
        stubRotationDependencies();
        UserSessionDO current = currentSession(LocalDateTime.now().plusMinutes(1));
        LocalDateTime expiry = current.getRefreshExpiresTime();
        String previousAccessHash = current.getAccessTokenHash();
        String previousRefreshHash = current.getRefreshTokenHash();
        when(userSessionMapper.selectByRefreshTokenHash(previousRefreshHash)).thenReturn(current);
        when(userSessionMapper.rotate(current, previousRefreshHash)).thenReturn(1);

        UserSessionDO result = sessionService.refreshSession(current.getRefreshToken());

        assertThat(result.getRefreshTokenHash()).isNotEqualTo(previousRefreshHash);
        assertThat(result.getAccessTokenHash()).isNotEqualTo(previousAccessHash);
        assertThat(result.getRefreshExpiresTime()).isEqualTo(expiry);
        assertThat(result.getAccessExpiresTime()).isEqualTo(expiry);
        assertThat(result.getUserInfo()).containsEntry("nickname", "tester");
        assertThat(result.getRefreshToken()).hasSize(128).startsWith("a".repeat(64));
        assertThat(result.getRefreshFamilyHash()).isEqualTo(previousRefreshHash);
    }

    @Test
    void refreshSession_concurrentReplayCannotPublishTokens() {
        stubRotationDependencies();
        UserSessionDO current = currentSession(LocalDateTime.now().plusDays(7));
        when(userSessionMapper.selectByRefreshTokenHash(current.getRefreshTokenHash()))
                .thenReturn(current);
        assertCode(
                () -> sessionService.refreshSession(current.getRefreshToken()),
                ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode());
    }

    @Test
    void refreshSession_oldGenerationTokenCountsReplaySignal() {
        String replayToken = "a".repeat(64);
        when(userSessionMapper.selectByRefreshTokenHash(any())).thenReturn(null);
        UserSessionDO current = currentSession(LocalDateTime.now().plusDays(7));
        when(userSessionMapper.selectByRefreshTokenOrFamilyHash(any(), any())).thenReturn(current);

        assertCode(
                () -> sessionService.refreshSession(replayToken),
                ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode());

        // 检测到旧代令牌即计入告警信号；删除竞态导致的零行更新不得掩盖入侵迹象。
        verify(securitySignalMetrics).recordRefreshReplay();
    }

    @Test
    void refreshSession_rejectsMissingAndRevokedSession() {
        assertCode(
                () -> sessionService.refreshSession("unknown"),
                ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode());
        verify(adminUserService, never()).getUserForSession(any());
    }

    @Test
    void refreshSession_oldGenerationReplayRevokesFamilySession() {
        String family = "a".repeat(64);
        String oldRefreshToken = family + "b".repeat(64);
        UserSessionDO current = currentSession(LocalDateTime.now().plusDays(7))
                .setRefreshTokenHash(SessionTokenDigest.digest(family + "c".repeat(64)));
        when(userSessionMapper.selectByRefreshTokenOrFamilyHash(
                        SessionTokenDigest.digest(oldRefreshToken), SessionTokenDigest.digest(family)))
                .thenReturn(current);
        when(userSessionMapper.deleteById(42L)).thenReturn(1);
        assertCode(
                () -> sessionService.refreshSession(oldRefreshToken),
                ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode());
        verify(userSessionMapper).deleteById(42L);
        verify(userSessionMapper, never()).rotate(any(), any());
        verify(adminUserService, never()).getUserForSession(any());
    }

    @Test
    void refreshSession_unknownTokenLeavesOtherSessionsUntouched() {
        String unknownToken = "d".repeat(64) + "e".repeat(64);
        assertCode(
                () -> sessionService.refreshSession(unknownToken),
                ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode());
        verify(userSessionMapper, never()).deleteById(any());
        verify(adminUserService, never()).getUserForSession(any());
    }

    @Test
    void refreshSession_rejectsDisabledUserBeforeRotation() {
        UserSessionDO current = currentSession(LocalDateTime.now().plusDays(7));
        when(userSessionMapper.selectByRefreshTokenHash(current.getRefreshTokenHash()))
                .thenReturn(current);
        when(adminUserService.getUserForSession(1L)).thenReturn(activeUser().setStatus(1));
        assertCode(
                () -> sessionService.refreshSession(current.getRefreshToken()),
                ErrorCodeConstants.AUTH_LOGIN_USER_DISABLED.getCode());
        verify(userSessionMapper, never()).rotate(any(), any());
    }

    @Test
    void refreshSession_deletesExpiredSessionOnlyIfCredentialStillMatches() {
        UserSessionDO current = currentSession(LocalDateTime.now().minusDays(1));
        when(userSessionMapper.selectByRefreshTokenHash(current.getRefreshTokenHash()))
                .thenReturn(current);
        when(adminUserService.getUserForSession(1L)).thenReturn(activeUser());
        when(userSessionMapper.deleteByIdAndRefreshTokenHash(42L, current.getRefreshTokenHash()))
                .thenReturn(1, 0);
        assertCode(
                () -> sessionService.refreshSession(current.getRefreshToken()),
                ErrorCodeConstants.SESSION_REFRESH_TOKEN_EXPIRED.getCode());
        assertCode(
                () -> sessionService.refreshSession(current.getRefreshToken()),
                ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode());
        verify(userSessionMapper, never()).rotate(any(), any());
    }

    @Test
    void accessTokenLookupObservesDatabaseRevocationWithoutReusingPreviouslyReadSession() {
        UserSessionDO current = currentSession(LocalDateTime.now().plusDays(1));
        String accessToken = "access-token";
        when(userSessionMapper.selectByAccessTokenHash(SessionTokenDigest.digest(accessToken)))
                .thenReturn(current, null);
        assertThat(sessionService.checkAccessToken(accessToken)).isSameAs(current);
        assertCode(
                () -> sessionService.checkAccessToken(accessToken),
                ErrorCodeConstants.SESSION_ACCESS_TOKEN_NOT_EXISTS.getCode());
    }

    @Test
    void checkAccessToken_rejectsExpiry() {
        UserSessionDO current = currentSession(LocalDateTime.now().plusDays(1));
        current.setAccessExpiresTime(LocalDateTime.now().minusSeconds(1));
        when(userSessionMapper.selectByAccessTokenHash(SessionTokenDigest.digest("access")))
                .thenReturn(current);
        assertCode(
                () -> sessionService.checkAccessToken("access"),
                ErrorCodeConstants.SESSION_ACCESS_TOKEN_EXPIRED.getCode());
    }

    @Test
    void getSessionByAccessToken_neverAcceptsRefreshToken() {
        assertThat(sessionService.getSessionByAccessToken("refresh-token")).isNull();
        verify(userSessionMapper, never()).selectByRefreshTokenHash(any());
    }

    @Test
    void removeSessionById_returnsOnlyActuallyDeletedSession() {
        UserSessionDO current = currentSession(LocalDateTime.now().plusDays(1));
        when(userSessionMapper.selectById(42L)).thenReturn(current);
        when(userSessionMapper.deleteById(42L)).thenReturn(1, 0);
        assertThat(sessionService.removeSessionById(42L)).isSameAs(current);
        assertThat(sessionService.removeSessionById(42L)).isNull();
        assertThat(sessionService.removeSessionById(43L)).isNull();
    }

    @Test
    void removeSessionByPresentedToken_revokesPersistedSession() {
        UserSessionDO current = currentSession(LocalDateTime.now().plusDays(1));
        String refresh = "b".repeat(64);
        when(userSessionMapper.selectByAccessTokenHash(SessionTokenDigest.digest("access")))
                .thenReturn(current);
        when(userSessionMapper.selectByRefreshTokenOrFamilyHash(
                        SessionTokenDigest.digest(refresh), SessionTokenDigest.digest(refresh)))
                .thenReturn(current);
        when(userSessionMapper.deleteById(42L)).thenReturn(1);
        assertThat(sessionService.removeSessionByAccessToken("access")).isSameAs(current);
        assertThat(sessionService.removeSessionByRefreshToken(refresh)).isSameAs(current);
    }

    @Test
    void removeSessionByOldRefreshTokenUsesStableFamilyInsteadOfRotatingSecret() {
        String family = "a".repeat(64);
        String oldRefreshToken = family + "b".repeat(64);
        UserSessionDO current = currentSession(LocalDateTime.now().plusDays(1))
                .setRefreshTokenHash(SessionTokenDigest.digest(family + "c".repeat(64)));
        when(userSessionMapper.selectByRefreshTokenOrFamilyHash(
                        SessionTokenDigest.digest(oldRefreshToken), SessionTokenDigest.digest(family)))
                .thenReturn(current);
        when(userSessionMapper.deleteById(42L)).thenReturn(1);
        assertThat(sessionService.removeSessionByRefreshToken(oldRefreshToken)).isSameAs(current);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"malformed"})
    void malformedRefreshTokenCannotRefreshOrRevoke(String refreshToken) {
        assertCode(
                () -> sessionService.refreshSession(refreshToken),
                ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode());
        assertThat(sessionService.removeSessionByRefreshToken(refreshToken)).isNull();
        verify(userSessionMapper, never()).selectByRefreshTokenOrFamilyHash(any(), any());
    }

    @Test
    void removeSessionsByUser_serializesWithIssuanceEvenWhenUserWasDeleted() {
        when(userSessionMapper.deleteByUser(1L, UserTypeEnum.ADMIN.getValue())).thenReturn(3);
        assertThat(sessionService.removeSessionsByUser(1L, UserTypeEnum.ADMIN.getValue()))
                .isEqualTo(3);
        var order = inOrder(adminUserService, userSessionMapper);
        order.verify(adminUserService).getUserForSession(1L);
        order.verify(userSessionMapper).deleteByUser(1L, UserTypeEnum.ADMIN.getValue());
    }

    @Test
    void getSessionPage_returnsPersistedPage() {
        PageParam request = new PageParam();
        PageResult<UserSessionDO> page =
                new PageResult<>(List.of(currentSession(LocalDateTime.now().plusDays(1))), 1L);
        when(userSessionMapper.selectPage(request, 1L, UserTypeEnum.ADMIN.getValue()))
                .thenReturn(page);
        assertThat(sessionService.getSessionPage(request, 1L, UserTypeEnum.ADMIN.getValue()))
                .isSameAs(page);
    }

    private void stubCreateDependencies() {
        stubRotationDependencies();
        when(sessionProperties.getRefreshTokenTtl()).thenReturn(Duration.ofDays(30));
    }

    private void stubRotationDependencies() {
        when(sessionProperties.getAccessTokenTtl()).thenReturn(Duration.ofMinutes(30));
        when(adminUserService.getUserForSession(1L)).thenReturn(activeUser());
    }

    private static AdminUserDO activeUser() {
        return new AdminUserDO()
                .setId(1L)
                .setNickname("tester")
                .setDeptId(null)
                .setPassword("hash")
                .setStatus(0)
                .setMustChangePassword(false);
    }

    private static void assertCode(Runnable operation, int expectedCode) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(expectedCode);
    }

    private static UserSessionDO currentSession(LocalDateTime refreshExpiresTime) {
        String refreshToken = "a".repeat(64);
        return new UserSessionDO()
                .setId(42L)
                .setAccessTokenHash("old-access-hash")
                .setRefreshToken(refreshToken)
                .setRefreshTokenHash(SessionTokenDigest.digest(refreshToken))
                .setUserId(1L)
                .setUserType(UserTypeEnum.ADMIN.getValue())
                .setAccessExpiresTime(LocalDateTime.now().plusMinutes(10))
                .setRefreshExpiresTime(refreshExpiresTime);
    }
}
