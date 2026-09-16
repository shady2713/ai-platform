package com.basicframework.module.system.event.session;

import static org.mockito.Mockito.verify;

import com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum;
import com.basicframework.module.system.service.metrics.SecuritySignalMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionRevocationMetricsListenerTest {

    @InjectMocks
    private SessionRevocationMetricsListener listener;

    @Mock
    private SecuritySignalMetrics securitySignalMetrics;

    @Test
    void onEvent_countsEveryRevokedSessionWithItsReason() {
        listener.onEvent(new SessionRevocationCompletedEvent(UserSessionRevocationReasonEnum.PASSWORD_CHANGED, 3));

        verify(securitySignalMetrics).recordSessionRevocation(UserSessionRevocationReasonEnum.PASSWORD_CHANGED, 3);
    }
}
