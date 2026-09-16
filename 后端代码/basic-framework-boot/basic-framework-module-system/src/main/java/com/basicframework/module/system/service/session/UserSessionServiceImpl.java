package com.basicframework.module.system.service.session;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.HexUtil;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.date.DateUtils;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.system.config.SessionProperties;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.session.UserSessionMapper;
import com.basicframework.module.system.service.metrics.SecuritySignalMetrics;
import com.basicframework.module.system.service.user.AdminUserService;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户会话以数据库为唯一权威；签发、刷新与用户变更按用户行锁串行，刷新令牌仅能轮换一次，旧代重放吊销整个会话。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSessionServiceImpl implements UserSessionService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final UserSessionMapper userSessionMapper;
    private final SessionProperties sessionProperties;
    private final AdminUserService adminUserService;
    private final SecuritySignalMetrics securitySignalMetrics;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserSessionDO createSession(Long userId, Integer userType, String expectedPassword) {
        validateIdentity(userId, userType);
        if (expectedPassword == null || expectedPassword.isBlank()) {
            throw exception(AUTH_LOGIN_BAD_CREDENTIALS);
        }
        AdminUserDO user = requireSessionUser(userId);
        if (!expectedPassword.equals(user.getPassword())) {
            throw exception(AUTH_LOGIN_BAD_CREDENTIALS);
        }
        UserSessionDO session = new UserSessionDO()
                .setUserId(userId)
                .setUserType(userType)
                .setRefreshExpiresTime(LocalDateTime.now().plus(sessionProperties.getRefreshTokenTtl()));
        rotateTokens(session, user, generateSecureToken());
        userSessionMapper.insert(session);
        return session;
    }

    @Override
    @Transactional(noRollbackFor = ServiceException.class)
    public UserSessionDO refreshSession(String refreshToken) {
        String refreshFamily = SessionTokenDigest.refreshFamily(refreshToken);
        if (refreshFamily == null) {
            throw exception(SESSION_REFRESH_TOKEN_INVALID);
        }
        String expectedRefreshTokenHash = SessionTokenDigest.digest(refreshToken);
        UserSessionDO session = userSessionMapper.selectByRefreshTokenHash(expectedRefreshTokenHash);
        if (session == null) {
            revokeSessionOnReplay(expectedRefreshTokenHash, refreshFamily);
            throw exception(SESSION_REFRESH_TOKEN_INVALID);
        }
        validateIdentity(session.getUserId(), session.getUserType());
        // 先锁用户，再修改会话，避免与改密、禁用和按用户撤销形成反向锁顺序。
        AdminUserDO user = requireSessionUser(session.getUserId());
        if (DateUtils.isExpired(session.getRefreshExpiresTime())) {
            if (userSessionMapper.deleteByIdAndRefreshTokenHash(session.getId(), expectedRefreshTokenHash) == 0) {
                throw exception(SESSION_REFRESH_TOKEN_INVALID);
            }
            throw exception(SESSION_REFRESH_TOKEN_EXPIRED);
        }
        rotateTokens(session, user, refreshFamily);
        if (userSessionMapper.rotate(session, expectedRefreshTokenHash) == 0) {
            throw exception(SESSION_REFRESH_TOKEN_INVALID);
        }
        return session;
    }

    @Override
    public UserSessionDO getSessionByAccessToken(String accessToken) {
        return userSessionMapper.selectByAccessTokenHash(SessionTokenDigest.digest(accessToken));
    }

    @Override
    public UserSessionDO checkAccessToken(String accessToken) {
        UserSessionDO session = getSessionByAccessToken(accessToken);
        if (session == null) {
            throw exception(SESSION_ACCESS_TOKEN_NOT_EXISTS);
        }
        if (DateUtils.isExpired(session.getAccessExpiresTime())) {
            throw exception(SESSION_ACCESS_TOKEN_EXPIRED);
        }
        return session;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserSessionDO removeSessionByAccessToken(String accessToken) {
        return removeSession(userSessionMapper.selectByAccessTokenHash(SessionTokenDigest.digest(accessToken)));
    }

    @Override
    public UserSessionDO getSessionById(Long id) {
        return userSessionMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserSessionDO removeSessionById(Long id) {
        return removeSession(getSessionById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserSessionDO removeSessionByRefreshToken(String refreshToken) {
        String refreshFamily = SessionTokenDigest.refreshFamily(refreshToken);
        if (refreshFamily == null) {
            return null;
        }
        return removeSession(userSessionMapper.selectByRefreshTokenOrFamilyHash(
                SessionTokenDigest.digest(refreshToken), SessionTokenDigest.digest(refreshFamily)));
    }

    private UserSessionDO removeSession(UserSessionDO session) {
        if (session == null || userSessionMapper.deleteById(session.getId()) == 0) {
            return null;
        }
        return session;
    }

    /**
     * 旧代刷新令牌重放视为凭据被盗（RFC 6819 重用检测）：吊销所属会话并留下安全日志，
     * 使被盗令牌即刻失去价值。本方法依赖 {@code noRollbackFor = ServiceException.class}，
     * 删除在随后抛出的业务异常下仍然提交。
     */
    private void revokeSessionOnReplay(String refreshTokenHash, String refreshFamily) {
        UserSessionDO replayed = userSessionMapper.selectByRefreshTokenOrFamilyHash(
                refreshTokenHash, SessionTokenDigest.digest(refreshFamily));
        if (replayed == null) {
            return;
        }
        // 只要检测到旧代令牌即计数：该信号用于告警，删除竞态造成的零行更新不应掩盖入侵迹象。
        securitySignalMetrics.recordRefreshReplay();
        if (userSessionMapper.deleteById(replayed.getId()) > 0) {
            log.warn("检测到旧代刷新令牌重放，吊销会话: userId={}, sessionId={}", replayed.getUserId(), replayed.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeSessionsByUser(Long userId, Integer userType) {
        validateIdentity(userId, userType);
        // 删除用户时该行可能已逻辑删除；原事务仍持有更新锁，旧会话必须继续清理。
        adminUserService.getUserForSession(userId);
        return userSessionMapper.deleteByUser(userId, userType);
    }

    @Override
    public PageResult<UserSessionDO> getSessionPage(PageParam pageParam, Long userId, Integer userType) {
        return userSessionMapper.selectPage(pageParam, userId, userType);
    }

    private void rotateTokens(UserSessionDO session, AdminUserDO user, String refreshFamily) {
        String accessToken = generateSecureToken();
        String refreshToken = refreshFamily + generateSecureToken();
        LocalDateTime accessExpiresTime = LocalDateTime.now().plus(sessionProperties.getAccessTokenTtl());
        if (accessExpiresTime.isAfter(session.getRefreshExpiresTime())) {
            accessExpiresTime = session.getRefreshExpiresTime();
        }
        session.setAccessToken(accessToken);
        session.setAccessTokenHash(SessionTokenDigest.digest(accessToken));
        session.setRefreshToken(refreshToken);
        session.setRefreshTokenHash(SessionTokenDigest.digest(refreshToken));
        session.setRefreshFamilyHash(SessionTokenDigest.digest(refreshFamily));
        session.setAccessExpiresTime(accessExpiresTime);
        session.setUserInfo(buildUserInfo(user));
    }

    private AdminUserDO requireSessionUser(Long userId) {
        AdminUserDO user = adminUserService.getUserForSession(userId);
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }
        if (!CommonStatusEnum.ENABLE.getStatus().equals(user.getStatus())) {
            throw exception(AUTH_LOGIN_USER_DISABLED);
        }
        if (!Boolean.FALSE.equals(user.getMustChangePassword())) {
            throw exception(AUTH_PASSWORD_EXPIRED);
        }
        return user;
    }

    private static Map<String, String> buildUserInfo(AdminUserDO user) {
        return MapUtil.builder(LoginUser.INFO_KEY_NICKNAME, user.getNickname())
                .put(
                        LoginUser.INFO_KEY_DEPT_ID,
                        user.getDeptId() != null ? user.getDeptId().toString() : null)
                .build();
    }

    private static void validateIdentity(Long userId, Integer userType) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("用户编号必须为正数");
        }
        if (!UserTypeEnum.ADMIN.getValue().equals(userType)) {
            throw new IllegalArgumentException("系统模块仅支持管理员会话");
        }
    }

    private static String generateSecureToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return HexUtil.encodeHexStr(bytes);
    }
}
