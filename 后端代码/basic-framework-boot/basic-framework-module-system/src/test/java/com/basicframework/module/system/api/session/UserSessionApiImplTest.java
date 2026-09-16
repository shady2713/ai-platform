package com.basicframework.module.system.api.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.module.system.api.session.dto.UserSessionCheckRespDTO;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.service.session.UserSessionService;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link UserSessionApiImpl} 单元测试
 *
 */
class UserSessionApiImplTest {

    @Test
    void getSupportedUserType_isAdmin() {
        UserSessionApiImpl userSessionApi = new UserSessionApiImpl(mock(UserSessionService.class));

        assertThat(userSessionApi.getSupportedUserType()).isEqualTo(UserTypeEnum.ADMIN.getValue());
    }

    @Test
    void checkAccessToken_convertsSessionDataObjectToDto() {
        UserSessionService userSessionService = mock(UserSessionService.class);
        UserSessionDO session = new UserSessionDO();
        session.setUserId(1L);
        session.setUserType(UserTypeEnum.ADMIN.getValue());
        session.setUserInfo(Map.of("nickname", "shady"));
        LocalDateTime expiresTime = LocalDateTime.of(2026, 9, 5, 12, 0, 0);
        session.setAccessExpiresTime(expiresTime);
        when(userSessionService.checkAccessToken("access-token-1")).thenReturn(session);

        UserSessionApiImpl userSessionApi = new UserSessionApiImpl(userSessionService);

        UserSessionCheckRespDTO result = userSessionApi.checkAccessToken("access-token-1");
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getUserType()).isEqualTo(UserTypeEnum.ADMIN.getValue());
        assertThat(result.getUserInfo()).containsEntry("nickname", "shady");
        assertThat(result.getAccessExpiresTime()).isEqualTo(expiresTime);
    }

    @Test
    void checkAccessToken_returnsNullWhenSessionMissing() {
        UserSessionService userSessionService = mock(UserSessionService.class);
        when(userSessionService.checkAccessToken("unknown-token")).thenReturn(null);

        UserSessionApiImpl userSessionApi = new UserSessionApiImpl(userSessionService);

        assertThat(userSessionApi.checkAccessToken("unknown-token")).isNull();
    }
}
