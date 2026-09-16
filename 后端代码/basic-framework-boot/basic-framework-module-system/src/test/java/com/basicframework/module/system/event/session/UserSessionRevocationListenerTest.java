package com.basicframework.module.system.event.session;

import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.PASSWORD_CHANGED;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.module.system.service.session.UserSessionService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class UserSessionRevocationListenerTest {

    @InjectMocks
    private UserSessionRevocationListener listener;

    @Mock
    private UserSessionService userSessionService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    void onEvent_revokesEveryAffectedUser() {
        int userType = UserTypeEnum.ADMIN.getValue();
        when(userSessionService.removeSessionsByUser(1L, userType)).thenReturn(3);

        listener.onEvent(new UserSessionRevocationEvent(Set.of(1L, 2L), userType, PASSWORD_CHANGED));

        verify(userSessionService).removeSessionsByUser(1L, userType);
        verify(userSessionService).removeSessionsByUser(2L, userType);
        verify(eventPublisher).publishEvent(new SessionRevocationCompletedEvent(PASSWORD_CHANGED, 3));
    }

    @Test
    void onEvent_doesNotPublishMetricsWhenThereAreNoSessions() {
        listener.onEvent(new UserSessionRevocationEvent(Set.of(1L), UserTypeEnum.ADMIN.getValue(), PASSWORD_CHANGED));
        verifyNoInteractions(eventPublisher);
    }
}
