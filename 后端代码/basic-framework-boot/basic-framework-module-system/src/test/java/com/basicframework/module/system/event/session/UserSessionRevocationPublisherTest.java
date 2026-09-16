package com.basicframework.module.system.event.session;

import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.ROLE_PERMISSION_CHANGED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.basicframework.framework.common.enums.UserTypeEnum;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class UserSessionRevocationPublisherTest {

    @InjectMocks
    private UserSessionRevocationPublisher publisher;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    void revokeAdminSessions_normalizesIdsAndPublishesEvent() {
        publisher.revokeAdminSessions(Arrays.asList(2L, null, 1L, 2L), ROLE_PERMISSION_CHANGED);

        ArgumentCaptor<UserSessionRevocationEvent> captor = ArgumentCaptor.forClass(UserSessionRevocationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().userIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(captor.getValue().userType()).isEqualTo(UserTypeEnum.ADMIN.getValue());
        assertThat(captor.getValue().reason()).isEqualTo(ROLE_PERMISSION_CHANGED);
    }

    @Test
    void revokeAdminSessions_emptyIds_doesNotPublish() {
        publisher.revokeAdminSessions(Collections.emptySet(), ROLE_PERMISSION_CHANGED);

        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void revokeAdminSession_publishesSingleUserEvent() {
        publisher.revokeAdminSession(1L, ROLE_PERMISSION_CHANGED);

        ArgumentCaptor<UserSessionRevocationEvent> captor = ArgumentCaptor.forClass(UserSessionRevocationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().userIds()).containsExactly(1L);
    }

    @Test
    void revokeAdminSessions_onlyNullIds_doesNotPublish() {
        publisher.revokeAdminSessions(Arrays.asList(null, null), ROLE_PERMISSION_CHANGED);

        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }
}
