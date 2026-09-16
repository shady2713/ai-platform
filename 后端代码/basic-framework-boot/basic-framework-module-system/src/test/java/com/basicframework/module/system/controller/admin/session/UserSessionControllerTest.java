package com.basicframework.module.system.controller.admin.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.controller.admin.session.vo.UserSessionPageReqVO;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.service.auth.AdminAuthService;
import com.basicframework.module.system.service.session.UserSessionService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class UserSessionControllerTest {

    private final UserSessionService userSessionService = mock(UserSessionService.class);
    private final AdminAuthService authService = mock(AdminAuthService.class);
    private final UserSessionController controller = new UserSessionController(userSessionService, authService);

    @Test
    void pagePreservesIdentityFiltersAndMapsSessionData() {
        UserSessionPageReqVO request = new UserSessionPageReqVO();
        request.setUserId(7L);
        request.setUserType(2);
        UserSessionDO session = new UserSessionDO().setId(11L).setUserId(7L).setUserType(2);
        when(userSessionService.getSessionPage(same(request), same(7L), same(2)))
                .thenReturn(new PageResult<>(List.of(session), 1L));

        var result = controller.getSessionPage(request).getData();

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getList())
                .singleElement()
                .extracting("id", "userId", "userType")
                .containsExactly(11L, 7L, 2);
        verify(userSessionService).getSessionPage(same(request), same(7L), same(2));
    }

    @Test
    void revokeUsesAccessTokenIdAndForcedLogoutAuditType() {
        assertThat(controller.revokeSession(11L).getData()).isTrue();

        verify(authService).logoutByAccessTokenId(null, 11L, LoginLogTypeEnum.LOGOUT_DELETE.getType());
    }

    @Test
    void revokeListProcessesEveryIdAndBothRevocationRoutesRequireAuthorization() throws Exception {
        assertThat(controller.revokeSessionList(List.of(11L, 12L)).getData()).isTrue();

        verify(authService).logoutByAccessTokenId(null, 11L, LoginLogTypeEnum.LOGOUT_DELETE.getType());
        verify(authService).logoutByAccessTokenId(null, 12L, LoginLogTypeEnum.LOGOUT_DELETE.getType());
        Method revoke = UserSessionController.class.getMethod("revokeSession", Long.class);
        Method revokeList = UserSessionController.class.getMethod("revokeSessionList", List.class);
        assertThat(revoke.isAnnotationPresent(org.springframework.security.access.prepost.PreAuthorize.class))
                .isTrue();
        assertThat(revokeList.isAnnotationPresent(org.springframework.security.access.prepost.PreAuthorize.class))
                .isTrue();
        assertThat(revoke.getAnnotation(PreAuthorize.class).value()).contains("system:session:revoke");
        assertThat(revokeList.getAnnotation(PreAuthorize.class).value()).contains("system:session:revoke");
    }
}
