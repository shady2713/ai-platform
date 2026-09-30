package com.basicframework.module.ai.service.realtime;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_IDEMPOTENCY_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_SESSION_LIMIT_EXCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeSessionDO;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeSessionMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 会话受理事务（X05 单测）：幂等复用/冲突、并发上限（主体与应用两道）与并发唯一键兜底。
 */
@ExtendWith(MockitoExtension.class)
class AiRealtimeSessionWriterTest {

    private static final Long APPLICATION_ID = 7L;

    @Mock
    private AiRealtimeSessionMapper sessionMapper;

    @Spy
    private AiRealtimeParams params = new AiRealtimeParams();

    @InjectMocks
    private AiRealtimeSessionWriter writer;

    @Test
    void sameRequestShapeIsReusedAndDifferentShapeConflicts() {
        when(sessionMapper.selectByRequestKey(APPLICATION_ID, "USER", "u-1", "rt_request_0001"))
                .thenReturn(session().setId(42L));

        AiRealtimeSessionWriter.Reservation reservation = writer.reserve(session());

        assertThat(reservation.reused()).isTrue();
        assertThat(reservation.session().getId()).isEqualTo(42L);
        verify(sessionMapper, never()).insert(any(AiRealtimeSessionDO.class));

        // 同键不同协议不是"同一个请求"：按幂等冲突拒绝（不静默换成旧会话）
        assertCode(() -> writer.reserve(session().setProtocol("WEBRTC")), AI_IDEMPOTENCY_CONFLICT);
    }

    @Test
    void differentEndpointOrProtocolOrFormatIsIdempotencyConflict() {
        when(sessionMapper.selectByRequestKey(APPLICATION_ID, "USER", "u-1", "rt_request_0001"))
                .thenReturn(session());
        when(sessionMapper.selectByRequestKey(APPLICATION_ID, "USER", "u-1", "rt_request_0002"))
                .thenReturn(session());

        assertCode(() -> writer.reserve(session().setEndpointId(11L)), AI_IDEMPOTENCY_CONFLICT);
        assertCode(
                () -> writer.reserve(session().setRequestKey("rt_request_0002").setAudioFormat("audio/ogg@16000:1:20")),
                AI_IDEMPOTENCY_CONFLICT);
    }

    @Test
    void subjectAndApplicationLimitsAreEnforcedWithLockingRead() {
        when(sessionMapper.countActiveBySubjectForUpdate(eq(APPLICATION_ID), eq("USER"), eq("u-1"), any()))
                .thenReturn((long) AiRealtimeParams.MAX_CONCURRENT_SESSIONS_PER_SUBJECT);
        assertCode(() -> writer.reserve(session()), AI_REALTIME_SESSION_LIMIT_EXCEEDED);
        verify(sessionMapper, never()).insert(any(AiRealtimeSessionDO.class));

        when(sessionMapper.countActiveBySubjectForUpdate(eq(APPLICATION_ID), eq("USER"), eq("u-1"), any()))
                .thenReturn(0L);
        when(sessionMapper.countActiveByApplication(eq(APPLICATION_ID), any()))
                .thenReturn((long) AiRealtimeParams.MAX_CONCURRENT_SESSIONS_PER_APPLICATION);
        assertCode(() -> writer.reserve(session()), AI_REALTIME_SESSION_LIMIT_EXCEEDED);
        verify(sessionMapper, never()).insert(any(AiRealtimeSessionDO.class));
    }

    @Test
    void reservationInsertsWhenCapacityIsAvailable() {
        when(sessionMapper.countActiveBySubjectForUpdate(any(), any(), any(), any()))
                .thenReturn(1L);
        when(sessionMapper.countActiveByApplication(any(), any())).thenReturn(3L);

        AiRealtimeSessionWriter.Reservation reservation = writer.reserve(session());

        assertThat(reservation.reused()).isFalse();
        ArgumentCaptor<AiRealtimeSessionDO> captor = ArgumentCaptor.forClass(AiRealtimeSessionDO.class);
        verify(sessionMapper).insert(captor.capture());
        assertThat(captor.getValue().getRequestKey()).isEqualTo("rt_request_0001");
    }

    @Test
    void duplicateKeyRaceFallsBackToTheExistingRowOrRethrows() {
        when(sessionMapper.selectByRequestKey(APPLICATION_ID, "USER", "u-1", "rt_request_0001"))
                .thenReturn(session().setId(42L));
        DuplicateKeyException race = new DuplicateKeyException("uk_ai_realtime_session_request");

        assertThat(writer.reloadAfterDuplicate(session(), race).getId()).isEqualTo(42L);

        when(sessionMapper.selectByRequestKey(APPLICATION_ID, "USER", "u-1", "rt_request_0001"))
                .thenReturn(null);
        assertThatThrownBy(() -> writer.reloadAfterDuplicate(session(), race)).isSameAs(race);
    }

    private static AiRealtimeSessionDO session() {
        return new AiRealtimeSessionDO()
                .setSessionKey("rts_test")
                .setRequestKey("rt_request_0001")
                .setApplicationId(APPLICATION_ID)
                .setSubjectType("USER")
                .setExternalUserId("u-1")
                .setEndpointId(9L)
                .setEndpointConfigRevision(3)
                .setProtocol("WEBSOCKET")
                .setAudioFormat("audio/pcm@16000:1:20")
                .setStatus(AiRealtimeSessionDO.STATUS_OPEN)
                .setInputCapacityBytes(AiRealtimeParams.INPUT_CAPACITY_BYTES)
                .setExpiresTime(LocalDateTime.now().plusMinutes(5));
    }

    private static void assertCode(ThrowingOperation operation, ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(expected.getCode()));
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
