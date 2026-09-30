package com.basicframework.module.ai.service.realtime;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_SESSION_NOT_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.realtime.RealtimeCloseReason;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeEventDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeSessionDO;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeSessionMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 会话生命周期判定（X05 单测）：归属失败的两套语义（普通访问不关闭、票据访问按切用户关闭）、
 * 惰性到期/断线超时物化与幂等关闭。
 */
@ExtendWith(MockitoExtension.class)
class AiRealtimeSessionLifecycleTest {

    private static final Long SESSION_ID = 42L;

    private static final AiConversationSubject OWNER = new AiConversationSubject(7L, AiSubjectType.USER, "u-1");

    @Mock
    private AiRealtimeSessionMapper sessionMapper;

    @Mock
    private AiRealtimeEventApplier eventApplier;

    @Mock
    private AiRealtimeChannels channels;

    @InjectMocks
    private AiRealtimeSessionLifecycle lifecycle;

    @Test
    void plainAccessByAnotherSubjectIsNotFoundWithoutClosingTheSession() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(openSession("u-2"));

        assertCode(() -> lifecycle.loadForSubject(SESSION_ID, OWNER));

        verify(eventApplier, never())
                .record(any(), any(), any(Long.class), any(Long.class), any(), any(Integer.class), any(), any());
        verify(channels, never()).close(any(), any());
    }

    @Test
    void ticketAccessByAnotherSubjectClosesTheSessionAsIdentitySwitched() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(openSession("u-2"));
        when(sessionMapper.close(SESSION_ID, RealtimeCloseReason.IDENTITY_SWITCHED.code()))
                .thenReturn(1);

        assertCode(() -> lifecycle.loadForTicketOperation(SESSION_ID, OWNER));

        verify(eventApplier)
                .record(
                        eq(SESSION_ID),
                        eq(AiRealtimeEventDO.TYPE_CLOSED),
                        eq(0L),
                        eq(0L),
                        eq(""),
                        eq(0),
                        eq("identity-switched:identity-switched"),
                        eq("closed:identity-switched"));
        verify(channels).close(SESSION_ID, RealtimeCloseReason.IDENTITY_SWITCHED);
    }

    @Test
    void unknownAndInvalidSessionIdsAreNotFound() {
        assertCode(() -> lifecycle.loadForSubject(null, OWNER));
        assertCode(() -> lifecycle.loadForSubject(0L, OWNER));
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(null);
        assertCode(() -> lifecycle.loadForSubject(SESSION_ID, OWNER));
    }

    @Test
    void expiredSessionIsMaterialisedOnRead() {
        AiRealtimeSessionDO expired =
                openSession("u-1").setExpiresTime(LocalDateTime.now().minusSeconds(1));
        when(sessionMapper.close(SESSION_ID, RealtimeCloseReason.SESSION_EXPIRED.code()))
                .thenReturn(1);
        AiRealtimeSessionDO closed = openSession("u-1")
                .setStatus(AiRealtimeSessionDO.STATUS_CLOSED)
                .setCloseReason(RealtimeCloseReason.SESSION_EXPIRED.code());
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(closed);

        assertThat(lifecycle.refreshLazyState(expired).getStatus()).isEqualTo(AiRealtimeSessionDO.STATUS_CLOSED);
        verify(eventApplier)
                .record(
                        eq(SESSION_ID),
                        eq(AiRealtimeEventDO.TYPE_CLOSED),
                        eq(0L),
                        eq(0L),
                        eq(""),
                        eq(0),
                        eq("session-expired:expired"),
                        eq("closed:session-expired"));
    }

    @Test
    void detachedSessionPastDeadlineIsMaterialisedOnRead() {
        AiRealtimeSessionDO detached = openSession("u-1")
                .setStatus(AiRealtimeSessionDO.STATUS_DETACHED)
                .setResumeDeadline(LocalDateTime.now().minusSeconds(1));
        when(sessionMapper.close(SESSION_ID, RealtimeCloseReason.REATTACH_TIMEOUT.code()))
                .thenReturn(1);
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(detached);

        lifecycle.refreshLazyState(detached);

        verify(eventApplier)
                .record(
                        eq(SESSION_ID),
                        eq(AiRealtimeEventDO.TYPE_CLOSED),
                        eq(0L),
                        eq(0L),
                        eq(""),
                        eq(0),
                        eq("reattach-timeout:resume-deadline"),
                        eq("closed:reattach-timeout"));
    }

    @Test
    void liveAndAlreadyClosedSessionsAreReturnedUnchanged() {
        AiRealtimeSessionDO open = openSession("u-1");
        assertThat(lifecycle.refreshLazyState(open)).isSameAs(open);

        AiRealtimeSessionDO closed = openSession("u-1")
                .setStatus(AiRealtimeSessionDO.STATUS_CLOSED)
                .setCloseReason(RealtimeCloseReason.CLIENT_CLOSED.code());
        assertThat(lifecycle.refreshLazyState(closed)).isSameAs(closed);
        assertThat(lifecycle.refreshLazyState(null)).isNull();
        verify(sessionMapper, never()).close(any(), any());
    }

    @Test
    void closeIsIdempotentAndAlwaysReleasesTheChannel() {
        AiRealtimeSessionDO open = openSession("u-1");
        when(sessionMapper.close(SESSION_ID, RealtimeCloseReason.CLIENT_CLOSED.code()))
                .thenReturn(0);

        lifecycle.close(open, RealtimeCloseReason.CLIENT_CLOSED, "client-closed");

        verify(eventApplier, never())
                .record(any(), any(), any(Long.class), any(Long.class), any(), any(Integer.class), any(), any());
        verify(channels).close(SESSION_ID, RealtimeCloseReason.CLIENT_CLOSED);

        lifecycle.close(null, RealtimeCloseReason.CLIENT_CLOSED, null);
        verify(channels, never()).close(null, RealtimeCloseReason.CLIENT_CLOSED);
    }

    private static AiRealtimeSessionDO openSession(String externalUserId) {
        return new AiRealtimeSessionDO()
                .setId(SESSION_ID)
                .setApplicationId(7L)
                .setSubjectType("USER")
                .setExternalUserId(externalUserId)
                .setStatus(AiRealtimeSessionDO.STATUS_OPEN)
                .setTurnNo(0L)
                .setExpiresTime(LocalDateTime.now().plusMinutes(5))
                .setResumeDeadline(LocalDateTime.now().plusMinutes(5));
    }

    private static void assertCode(ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(AI_REALTIME_SESSION_NOT_EXISTS.getCode()));
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
