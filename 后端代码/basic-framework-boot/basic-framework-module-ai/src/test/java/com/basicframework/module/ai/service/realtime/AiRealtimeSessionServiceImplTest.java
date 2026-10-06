package com.basicframework.module.ai.service.realtime;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_IDEMPOTENCY_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_CALL_FAILED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_AUDIO_FORMAT_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_BACKPRESSURE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_ENDPOINT_CONFIG_CHANGED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_MUTED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_REATTACH_BUDGET_EXCEEDED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_REATTACH_TIMEOUT_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_SESSION_CLOSED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_SESSION_DETACHED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_SESSION_EXPIRED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_SESSION_LIMIT_EXCEEDED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_SESSION_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_TICKET_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_TOOL_CALL_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_TURN_FUTURE_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_TURN_STALE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.realtime.RealtimeAdapter;
import com.basicframework.framework.ai.core.realtime.RealtimeAudioFormat;
import com.basicframework.framework.ai.core.realtime.RealtimeCloseReason;
import com.basicframework.framework.ai.core.realtime.RealtimeEvent;
import com.basicframework.framework.ai.core.realtime.RealtimeProtocol;
import com.basicframework.framework.ai.core.realtime.RealtimeSessionChannel;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeEventDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeSessionDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeToolCallDO;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeSessionMapper;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeToolCallMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeAcceptDTO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeAudioPushDTO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeSessionViewDTO;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

/**
 * 实时语音会话服务（X05 单测）：受理/推流/打断/关麦/断线/重连/续票/关闭/工具执行的语义与稳定码。
 *
 * <p>依赖替身策略：状态机与持久层用替身（单测不连库），纯逻辑协作者（参数守卫、票据摘要）
 * 用真实实例——它们的正确性由 {@code AiRealtimeParamsTest}/{@code AiRealtimeSessionTicketsTest} 独立覆盖，
 * 这里要验证的是"服务在什么条件下给出哪个稳定码"。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiRealtimeSessionServiceImplTest {

    private static final Long SESSION_ID = 42L;

    private static final AiConversationSubject SUBJECT = new AiConversationSubject(7L, AiSubjectType.USER, "u-1");

    private static final RealtimeAudioFormat PCM_20MS =
            new RealtimeAudioFormat(RealtimeAudioFormat.MIME_PCM, 16000, 1, 20);

    private static final String TICKET = "ticket-plain-0123456789";

    @Mock
    private AiConversationSubjectResolver subjectResolver;

    @Mock
    private AiRealtimeEndpointVerifier endpointVerifier;

    @Mock
    private AiRealtimeSessionWriter writer;

    @Mock
    private AiRealtimeSessionLifecycle lifecycle;

    @Mock
    private AiRealtimeChannels channels;

    @Mock
    private AiRealtimeSessionViews views;

    @Mock
    private AiRealtimeEventApplier eventApplier;

    @Mock
    private AiRealtimeToolBridge toolBridge;

    @Mock
    private AiRealtimeSessionMapper sessionMapper;

    @Mock
    private AiRealtimeToolCallMapper toolCallMapper;

    @Mock
    private RealtimeAdapter adapter;

    @Mock
    private RealtimeSessionChannel channel;

    @Spy
    private AiRealtimeParams params = new AiRealtimeParams();

    @Spy
    private AiRealtimeSessionTickets tickets = new AiRealtimeSessionTickets();

    @InjectMocks
    private AiRealtimeSessionServiceImpl service;

    private AiRealtimeSessionViewDTO view;

    @BeforeEach
    void setUp() {
        view = new AiRealtimeSessionViewDTO().setId(SESSION_ID).setStatus(AiRealtimeSessionDO.STATUS_OPEN);
    }

    // ==================== 受理 ====================

    @Test
    void acceptPinsEndpointProtocolAndReturnsTicketOnce() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.of(SUBJECT));
        when(endpointVerifier.verify(9L, RealtimeProtocol.WEBSOCKET, PCM_20MS)).thenReturn(verified());
        when(writer.reserve(any())).thenAnswer(invocation -> {
            AiRealtimeSessionDO reserved = invocation.getArgument(0);
            reserved.setId(SESSION_ID);
            return new AiRealtimeSessionWriter.Reservation(reserved, false);
        });
        when(views.toView(any(AiRealtimeSessionDO.class), any())).thenReturn(view);
        stubChannelOpen();

        AiRealtimeSessionViewDTO result = service.accept(new AiRealtimeAcceptDTO()
                .setRequestKey("rt_request_0001")
                .setEndpointId(9L)
                .setProtocol("websocket")
                .setAudioFormat("audio/pcm@16000:1:20")
                .setSessionSeconds(120));

        ArgumentCaptor<AiRealtimeSessionDO> captor = ArgumentCaptor.forClass(AiRealtimeSessionDO.class);
        verify(writer).reserve(captor.capture());
        AiRealtimeSessionDO candidate = captor.getValue();
        assertThat(candidate.getApplicationId()).isEqualTo(7L);
        assertThat(candidate.getSubjectType()).isEqualTo("USER");
        assertThat(candidate.getExternalUserId()).isEqualTo("u-1");
        assertThat(candidate.getEndpointConfigRevision()).isEqualTo(3);
        assertThat(candidate.getEndpointCredentialRevision()).isEqualTo(2);
        assertThat(candidate.getProtocol()).isEqualTo("WEBSOCKET");
        assertThat(candidate.getAudioFormat()).isEqualTo("audio/pcm@16000:1:20");
        assertThat(candidate.getInputCapacityBytes()).isEqualTo(AiRealtimeParams.INPUT_CAPACITY_BYTES);
        assertThat(candidate.getStatus()).isEqualTo(AiRealtimeSessionDO.STATUS_OPEN);
        assertThat(candidate.getTicketDigest()).isNotBlank().isNotEqualTo(candidate.getSessionKey());
        assertThat(candidate.getTicketExpiresTime()).isBeforeOrEqualTo(candidate.getExpiresTime());
        assertThat(result).isSameAs(view);
        verify(channels).register(eq(SESSION_ID), any(RealtimeSessionChannel.class));
    }

    @Test
    void acceptReplayReturnsExistingSessionWithoutNewTicketOrChannel() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.of(SUBJECT));
        when(endpointVerifier.verify(anyLong(), any(), any())).thenReturn(verified());
        when(writer.reserve(any())).thenReturn(new AiRealtimeSessionWriter.Reservation(openSession(), true));
        when(views.toView(any(AiRealtimeSessionDO.class))).thenReturn(view);

        AiRealtimeSessionViewDTO result = service.accept(acceptRequest());

        assertThat(result.getTicket()).isNull();
        verify(channels, never()).register(any(), any());
    }

    @Test
    void acceptRejectsIdempotencyConflictLimitAndUntrustedSubject() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.of(SUBJECT));
        when(endpointVerifier.verify(anyLong(), any(), any())).thenReturn(verified());
        doThrow(exceptionOf(AI_IDEMPOTENCY_CONFLICT)).when(writer).reserve(any());
        assertCode(() -> service.accept(acceptRequest()), AI_IDEMPOTENCY_CONFLICT);

        doThrow(exceptionOf(AI_REALTIME_SESSION_LIMIT_EXCEEDED)).when(writer).reserve(any());
        assertCode(() -> service.accept(acceptRequest()), AI_REALTIME_SESSION_LIMIT_EXCEEDED);

        when(subjectResolver.resolveCurrent()).thenReturn(Optional.empty());
        assertCode(() -> service.accept(acceptRequest()), AI_RESOURCE_NOT_FOUND);
    }

    @Test
    void acceptRejectsInvalidRequestShapeBeforeVerification() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.of(SUBJECT));

        assertCode(() -> service.accept(null), AI_REQUEST_INVALID);
        assertCode(() -> service.accept(acceptRequest().setRequestKey("bad")), AI_REQUEST_INVALID);
        assertCode(() -> service.accept(acceptRequest().setProtocol("RTSP")), AI_REQUEST_INVALID);
        assertCode(
                () -> service.accept(acceptRequest().setAudioFormat("audio/mpeg@16000:1:20")),
                AI_REALTIME_AUDIO_FORMAT_UNSUPPORTED);
        assertCode(() -> service.accept(acceptRequest().setSessionSeconds(5)), AI_REQUEST_INVALID);
        verify(endpointVerifier, never()).verify(any(), any(), any());
    }

    @Test
    void acceptClosesSessionWhenChannelCannotOpen() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.of(SUBJECT));
        when(endpointVerifier.verify(anyLong(), any(), any())).thenReturn(verified());
        when(writer.reserve(any())).thenAnswer(invocation -> {
            AiRealtimeSessionDO reserved = invocation.getArgument(0);
            reserved.setId(SESSION_ID);
            return new AiRealtimeSessionWriter.Reservation(reserved, false);
        });
        doThrow(new IllegalStateException("upstream down")).when(adapter).open(any());

        assertCode(() -> service.accept(acceptRequest()), AI_MODEL_CALL_FAILED);
        verify(lifecycle).close(any(AiRealtimeSessionDO.class), eq(RealtimeCloseReason.OPEN_FAILED), any());
    }

    @Test
    void acceptTreatsDuplicateKeyRaceAsIdempotentReplay() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.of(SUBJECT));
        when(endpointVerifier.verify(anyLong(), any(), any())).thenReturn(verified());
        when(writer.reserve(any())).thenThrow(new DuplicateKeyException("uk_ai_realtime_session_request"));
        when(writer.reloadAfterDuplicate(any(), any())).thenReturn(openSession());
        when(views.toView(any(AiRealtimeSessionDO.class))).thenReturn(view);

        assertThat(service.accept(acceptRequest())).isSameAs(view);
    }

    // ==================== 推流与背压 ====================

    @Test
    void pushAudioReservesAndDrainsWhenAdapterConsumesFrame() {
        AiRealtimeSessionDO session = prepareOwnedOpenSession();
        when(channels.find(SESSION_ID)).thenReturn(Optional.of(channel));
        when(sessionMapper.reserveInputBytes(eq(SESSION_ID), eq(640L), any())).thenReturn(1);
        when(channel.pushAudio(any())).thenReturn(true);
        when(channel.pollEvents()).thenReturn(List.of());
        when(eventApplier.apply(any(), any())).thenReturn(AiRealtimeEventApplier.Outcome.none());
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(session);
        when(views.toView(session)).thenReturn(view);

        assertThat(service.pushAudio(SESSION_ID, frame(0L, 0L))).isSameAs(view);
        verify(sessionMapper).drainInputBytes(SESSION_ID, 640L);
    }

    @Test
    void pushAudioKeepsOccupancyWhenAdapterQueuesFrame() {
        AiRealtimeSessionDO session = prepareOwnedOpenSession();
        when(channels.find(SESSION_ID)).thenReturn(Optional.of(channel));
        when(sessionMapper.reserveInputBytes(eq(SESSION_ID), eq(640L), any())).thenReturn(1);
        when(channel.pushAudio(any())).thenReturn(false);
        when(channel.pollEvents()).thenReturn(List.of());
        when(eventApplier.apply(any(), any())).thenReturn(AiRealtimeEventApplier.Outcome.none());
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(session);
        when(views.toView(session)).thenReturn(view);

        service.pushAudio(SESSION_ID, frame(0L, 1L));

        verify(sessionMapper, never()).drainInputBytes(any(), anyLong());
    }

    @Test
    void pushAudioOverflowClosesSessionWithStableReason() {
        AiRealtimeSessionDO full = openSession().setBufferedBytes(AiRealtimeParams.INPUT_CAPACITY_BYTES);
        prepareOwnedOpenSession(full);
        when(channels.find(SESSION_ID)).thenReturn(Optional.of(channel));
        when(sessionMapper.reserveInputBytes(eq(SESSION_ID), eq(640L), any())).thenReturn(0);
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(full);

        assertCode(() -> service.pushAudio(SESSION_ID, frame(0L, 0L)), AI_REALTIME_BACKPRESSURE_CONFLICT);
        verify(lifecycle).close(eq(full), eq(RealtimeCloseReason.AUDIO_BACKPRESSURE_EXCEEDED), any());
    }

    @Test
    void pushAudioRejectsMutedSessionAtReservation() {
        AiRealtimeSessionDO muted = openSession().setMuted(true);
        prepareOwnedOpenSession(muted);
        when(channels.find(SESSION_ID)).thenReturn(Optional.of(channel));
        when(sessionMapper.reserveInputBytes(eq(SESSION_ID), eq(640L), any())).thenReturn(0);
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(muted);

        assertCode(() -> service.pushAudio(SESSION_ID, frame(0L, 0L)), AI_REALTIME_MUTED_CONFLICT);
    }

    @Test
    void pushAudioDropsStaleFramesAndCountsThem() {
        prepareOwnedOpenSession(openSession().setTurnNo(2L).setDroppedStaleFrames(1));

        assertCode(() -> service.pushAudio(SESSION_ID, frame(1L, 0L)), AI_REALTIME_TURN_STALE_CONFLICT);
        verify(sessionMapper).addDroppedStaleFrames(SESSION_ID, 1);
        verify(eventApplier)
                .record(
                        eq(SESSION_ID),
                        eq(AiRealtimeEventDO.TYPE_STALE_DROPPED),
                        eq(1L),
                        eq(0L),
                        eq(""),
                        eq(640),
                        eq("STALE:CLIENT_FRAME"),
                        eq("stale:CLIENT_FRAME:1:0"));
    }

    @Test
    void pushAudioRejectsFutureTurnClosedExpiredAndDetachedSessions() {
        prepareOwnedOpenSession(openSession().setTurnNo(2L));
        assertCode(() -> service.pushAudio(SESSION_ID, frame(5L, 0L)), AI_REALTIME_TURN_FUTURE_INVALID);

        prepareOwnedOpenSession(closedSession(RealtimeCloseReason.CLIENT_CLOSED));
        assertCode(() -> service.pushAudio(SESSION_ID, frame(0L, 0L)), AI_REALTIME_SESSION_CLOSED_CONFLICT);

        prepareOwnedOpenSession(closedSession(RealtimeCloseReason.SESSION_EXPIRED));
        assertCode(() -> service.pushAudio(SESSION_ID, frame(0L, 0L)), AI_REALTIME_SESSION_EXPIRED_CONFLICT);

        prepareOwnedOpenSession(openSession().setStatus(AiRealtimeSessionDO.STATUS_DETACHED));
        assertCode(() -> service.pushAudio(SESSION_ID, frame(0L, 0L)), AI_REALTIME_SESSION_DETACHED_CONFLICT);
    }

    @Test
    void pushAudioRejectsOversizedFrameAndMarksDetachedWhenChannelIsGone() {
        prepareOwnedOpenSession();
        assertCode(
                () -> service.pushAudio(
                        SESSION_ID,
                        new AiRealtimeAudioPushDTO()
                                .setTurnNo(0L)
                                .setFrameSeq(0L)
                                .setPayload(new byte[641])),
                AI_REALTIME_AUDIO_FORMAT_UNSUPPORTED);

        prepareOwnedOpenSession();
        when(channels.find(SESSION_ID)).thenReturn(Optional.empty());
        assertCode(() -> service.pushAudio(SESSION_ID, frame(0L, 0L)), AI_REALTIME_SESSION_DETACHED_CONFLICT);
        verify(sessionMapper).detach(eq(SESSION_ID), any(), any());
    }

    @Test
    void pushAudioClosesSessionWhenAdapterFailsOrEnds() {
        AiRealtimeSessionDO session = prepareOwnedOpenSession();
        when(channels.find(SESSION_ID)).thenReturn(Optional.of(channel));
        when(sessionMapper.reserveInputBytes(eq(SESSION_ID), eq(640L), any())).thenReturn(1);
        doThrow(new IllegalStateException("transport reset")).when(channel).pushAudio(any());

        assertCode(() -> service.pushAudio(SESSION_ID, frame(0L, 0L)), AI_MODEL_CALL_FAILED);
        // 落库的必须是**稳定 token**，不是异常类名：类名会随重构变化，
        // 一旦写进会话行，历史行就变成查不出来的孤儿。
        verify(lifecycle)
                .close(
                        eq(session),
                        eq(RealtimeCloseReason.ADAPTER_FAILED),
                        eq(AiRealtimeSessionServiceImpl.UNATTRIBUTED_FAILURE_CODE));

        AiRealtimeSessionDO ended = prepareOwnedOpenSession();
        when(channels.find(SESSION_ID)).thenReturn(Optional.of(channel));
        when(sessionMapper.reserveInputBytes(eq(SESSION_ID), eq(640L), any())).thenReturn(1);
        doReturn(true).when(channel).pushAudio(any());
        when(channel.pollEvents())
                .thenReturn(List.of(new RealtimeEvent.Ended(0L, RealtimeCloseReason.ADAPTER_ENDED, "bye")));
        when(eventApplier.apply(any(), any()))
                .thenReturn(new AiRealtimeEventApplier.Outcome(RealtimeCloseReason.ADAPTER_ENDED, "bye"));
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(ended);
        when(views.toView(ended)).thenReturn(view);

        service.pushAudio(SESSION_ID, frame(0L, 2L));

        verify(lifecycle).close(eq(ended), eq(RealtimeCloseReason.ADAPTER_ENDED), eq("bye"));
    }

    @Test
    void pushAudioRejectsIncompleteFrames() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.of(SUBJECT));
        assertCode(() -> service.pushAudio(SESSION_ID, null), AI_REQUEST_INVALID);
        assertCode(() -> service.pushAudio(SESSION_ID, new AiRealtimeAudioPushDTO().setTurnNo(0L)), AI_REQUEST_INVALID);
        assertCode(
                () -> service.pushAudio(
                        SESSION_ID,
                        new AiRealtimeAudioPushDTO()
                                .setTurnNo(0L)
                                .setFrameSeq(0L)
                                .setPayload(new byte[0])),
                AI_REQUEST_INVALID);
    }

    // ==================== 打断 / 关麦 / 断线 ====================

    @Test
    void interruptAdvancesTurnAndRecordsDiscardedInput() {
        AiRealtimeSessionDO session = prepareOwnedOpenSession(openSession().setBufferedBytes(1280L));
        when(sessionMapper.advanceTurn(eq(SESSION_ID), eq(0L), any())).thenReturn(1);
        when(channels.find(SESSION_ID)).thenReturn(Optional.of(channel));
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(session);
        when(views.toView(session)).thenReturn(view);

        assertThat(service.interrupt(SESSION_ID, 0L)).isSameAs(view);
        verify(channel).interrupt(0L);
        verify(eventApplier)
                .record(
                        eq(SESSION_ID),
                        eq(AiRealtimeEventDO.TYPE_STALE_DROPPED),
                        eq(0L),
                        eq(0L),
                        eq(""),
                        eq(1280),
                        eq("INTERRUPT_DRAIN"),
                        eq("stale:INTERRUPT_DRAIN:0"));
    }

    @Test
    void interruptRejectsStaleAndRacingTurns() {
        prepareOwnedOpenSession(openSession().setTurnNo(3L));
        assertCode(() -> service.interrupt(SESSION_ID, 2L), AI_REALTIME_TURN_STALE_CONFLICT);

        prepareOwnedOpenSession(openSession().setTurnNo(3L));
        assertCode(() -> service.interrupt(SESSION_ID, 9L), AI_REALTIME_TURN_FUTURE_INVALID);

        prepareOwnedOpenSession();
        when(sessionMapper.advanceTurn(eq(SESSION_ID), eq(0L), any())).thenReturn(0);
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(openSession());
        when(lifecycle.refreshLazyState(any())).thenReturn(openSession());
        assertCode(() -> service.interrupt(SESSION_ID, 0L), AI_REALTIME_TURN_STALE_CONFLICT);

        prepareOwnedOpenSession();
        assertCode(() -> service.interrupt(SESSION_ID, null), AI_REQUEST_INVALID);
    }

    @Test
    void muteUpdatesFlagAndDetachStartsBoundedWindowOnlyOnce() {
        AiRealtimeSessionDO session = prepareOwnedOpenSession();
        when(sessionMapper.updateMuted(SESSION_ID, true)).thenReturn(1);
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(session);
        when(views.toView(session)).thenReturn(view);
        assertThat(service.updateMuted(SESSION_ID, true)).isSameAs(view);

        prepareOwnedOpenSession();
        when(sessionMapper.detach(eq(SESSION_ID), any(), any())).thenReturn(1);
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(openSession());
        service.detach(SESSION_ID);
        ArgumentCaptor<LocalDateTime> deadline = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(sessionMapper).detach(eq(SESSION_ID), deadline.capture(), any());
        assertThat(deadline.getValue()).isAfter(LocalDateTime.now());

        AiRealtimeSessionDO detached = openSession().setId(77L).setStatus(AiRealtimeSessionDO.STATUS_DETACHED);
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.of(SUBJECT));
        when(lifecycle.loadForSubject(77L, SUBJECT)).thenReturn(detached);
        when(lifecycle.refreshLazyState(detached)).thenReturn(detached);
        when(views.toView(detached)).thenReturn(view);
        assertThat(service.detach(77L)).isSameAs(view);
        verify(sessionMapper, never()).detach(eq(77L), any(), any());
    }

    @Test
    void muteOnClosedSessionIsRejected() {
        prepareOwnedOpenSession(closedSession(RealtimeCloseReason.CLIENT_CLOSED));
        assertCode(() -> service.updateMuted(SESSION_ID, false), AI_REALTIME_SESSION_CLOSED_CONFLICT);
    }

    // ==================== 重连与续票 ====================

    @Test
    void resumeRejectsUnknownAndExpiredTickets() {
        prepareTicketSession(openSession());
        assertCode(() -> service.resume(SESSION_ID, "wrong-ticket"), AI_REALTIME_TICKET_INVALID);

        prepareTicketSession(
                openSession().setTicketExpiresTime(LocalDateTime.now().minusSeconds(1)));
        assertCode(() -> service.resume(SESSION_ID, TICKET), AI_REALTIME_TICKET_INVALID);
    }

    @Test
    void resumeClosesSessionWhenWindowOrBudgetIsExhausted() {
        prepareTicketSession(openSession().setResumeDeadline(LocalDateTime.now().minusSeconds(1)));
        assertCode(() -> service.resume(SESSION_ID, TICKET), AI_REALTIME_REATTACH_TIMEOUT_CONFLICT);
        verify(lifecycle).close(any(AiRealtimeSessionDO.class), eq(RealtimeCloseReason.REATTACH_TIMEOUT), any());

        prepareTicketSession(openSession().setResumeAttempts(AiRealtimeParams.MAX_REATTACH_ATTEMPTS));
        assertCode(() -> service.resume(SESSION_ID, TICKET), AI_REALTIME_REATTACH_BUDGET_EXCEEDED);
        verify(lifecycle)
                .close(any(AiRealtimeSessionDO.class), eq(RealtimeCloseReason.REATTACH_BUDGET_EXCEEDED), any());
    }

    @Test
    void resumeRestoresStateAndReopensChannelWithoutReexecutingTools() {
        AiRealtimeSessionDO resumed = openSession().setResumeAttempts(1);
        prepareTicketSession(openSession());
        when(sessionMapper.resume(eq(SESSION_ID), anyInt(), any())).thenReturn(1);
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(resumed);
        when(channels.find(SESSION_ID)).thenReturn(Optional.empty());
        when(endpointVerifier.verifyPinned(resumed)).thenReturn(verified());
        when(views.toView(resumed)).thenReturn(view);
        stubChannelOpen();

        assertThat(service.resume(SESSION_ID, TICKET)).isSameAs(view);
        verify(channels).register(eq(SESSION_ID), any(RealtimeSessionChannel.class));
        verify(eventApplier)
                .record(
                        eq(SESSION_ID),
                        eq(AiRealtimeEventDO.TYPE_REATTACHED),
                        anyLong(),
                        eq(0L),
                        eq(""),
                        eq(0),
                        eq("attempt:1"),
                        eq("reattach:1"));
        verify(toolBridge, never()).execute(any(), any());
    }

    @Test
    void resumeRejectsWhenPinnedEndpointConfigurationChanged() {
        prepareTicketSession(openSession());
        when(sessionMapper.resume(eq(SESSION_ID), anyInt(), any())).thenReturn(1);
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(openSession());
        when(channels.find(SESSION_ID)).thenReturn(Optional.empty());
        when(endpointVerifier.verifyPinned(any())).thenThrow(exceptionOf(AI_REALTIME_ENDPOINT_CONFIG_CHANGED_CONFLICT));

        assertCode(() -> service.resume(SESSION_ID, TICKET), AI_REALTIME_ENDPOINT_CONFIG_CHANGED_CONFLICT);
    }

    @Test
    void resumeFailurePathClosesOnDeadline() {
        prepareTicketSession(openSession());
        when(sessionMapper.resume(eq(SESSION_ID), anyInt(), any())).thenReturn(0);
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(openSession());
        when(lifecycle.refreshLazyState(any()))
                .thenReturn(openSession().setResumeDeadline(LocalDateTime.now().minusSeconds(1)));

        assertCode(() -> service.resume(SESSION_ID, TICKET), AI_REALTIME_REATTACH_TIMEOUT_CONFLICT);
    }

    @Test
    void renewTicketReplacesDigestAndReturnsPlaintextOnce() {
        prepareTicketSession(openSession());
        when(sessionMapper.renewTicket(eq(SESSION_ID), eq(1), any(), any(), any()))
                .thenReturn(1);
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(openSession());
        when(views.toView(any(AiRealtimeSessionDO.class), any())).thenReturn(view);

        assertThat(service.renewTicket(SESSION_ID, TICKET)).isSameAs(view);
        ArgumentCaptor<String> newDigest = ArgumentCaptor.forClass(String.class);
        verify(sessionMapper).renewTicket(eq(SESSION_ID), eq(1), newDigest.capture(), any(), any());
        assertThat(newDigest.getValue()).isNotBlank();
    }

    @Test
    void renewTicketRejectsExpiredTicketAndConcurrentReplacement() {
        prepareTicketSession(
                openSession().setTicketExpiresTime(LocalDateTime.now().minusSeconds(1)));
        assertCode(() -> service.renewTicket(SESSION_ID, TICKET), AI_REALTIME_TICKET_INVALID);

        prepareTicketSession(openSession());
        when(sessionMapper.renewTicket(eq(SESSION_ID), eq(1), any(), any(), any()))
                .thenReturn(0);
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(openSession());
        when(lifecycle.refreshLazyState(any())).thenReturn(openSession());
        assertCode(() -> service.renewTicket(SESSION_ID, TICKET), AI_STATE_CONFLICT);
    }

    // ==================== 关闭 / 工具 / 归属 ====================

    @Test
    void closeIsIdempotentAndExecuteToolCallValidatesOwnership() {
        prepareOwnedOpenSession();
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(openSession());
        when(views.toView(any(AiRealtimeSessionDO.class))).thenReturn(view);
        service.close(SESSION_ID);
        verify(lifecycle).close(any(AiRealtimeSessionDO.class), eq(RealtimeCloseReason.CLIENT_CLOSED), any());

        AiRealtimeSessionDO closed = closedSession(RealtimeCloseReason.CLIENT_CLOSED);
        prepareOwnedOpenSession(closed);
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(closed);
        when(views.toView(closed)).thenReturn(view);
        assertThat(service.close(SESSION_ID)).isSameAs(view);
        verify(lifecycle, never()).close(eq(closed), any(), any());
    }

    @Test
    void executeToolCallRejectsUnknownCallAndDelegatesToControlledBridge() {
        prepareOwnedOpenSession();
        when(toolCallMapper.selectById(9L)).thenReturn(null);
        assertCode(() -> service.executeToolCall(SESSION_ID, 9L), AI_REALTIME_TOOL_CALL_NOT_EXISTS);

        prepareOwnedOpenSession();
        AiRealtimeToolCallDO foreign = new AiRealtimeToolCallDO().setId(9L).setSessionId(999L);
        when(toolCallMapper.selectById(9L)).thenReturn(foreign);
        assertCode(() -> service.executeToolCall(SESSION_ID, 9L), AI_REALTIME_TOOL_CALL_NOT_EXISTS);

        AiRealtimeSessionDO session = prepareOwnedOpenSession();
        AiRealtimeToolCallDO call = new AiRealtimeToolCallDO().setId(9L).setSessionId(SESSION_ID);
        when(toolCallMapper.selectById(9L)).thenReturn(call);
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(session);
        when(views.toView(session)).thenReturn(view);
        assertThat(service.executeToolCall(SESSION_ID, 9L)).isSameAs(view);
        verify(toolBridge).execute(session, call);

        prepareOwnedOpenSession();
        assertCode(() -> service.executeToolCall(SESSION_ID, 0L), AI_REQUEST_INVALID);
    }

    @Test
    void executeToolCallOnClosedSessionIsRejected() {
        prepareOwnedOpenSession(closedSession(RealtimeCloseReason.CLIENT_CLOSED));
        assertCode(() -> service.executeToolCall(SESSION_ID, 9L), AI_REALTIME_SESSION_CLOSED_CONFLICT);
    }

    @Test
    void getSessionMaterialisesLazyState() {
        AiRealtimeSessionDO expired = closedSession(RealtimeCloseReason.SESSION_EXPIRED);
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.of(SUBJECT));
        when(lifecycle.loadForSubject(SESSION_ID, SUBJECT)).thenReturn(openSession());
        when(lifecycle.refreshLazyState(any())).thenReturn(expired);
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(expired);
        when(views.toView(expired)).thenReturn(view);

        assertThat(service.getSession(SESSION_ID)).isSameAs(view);
        verify(lifecycle).refreshLazyState(any());
    }

    @Test
    void getSessionOnForeignSessionIsNotFound() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.of(SUBJECT));
        when(lifecycle.loadForSubject(SESSION_ID, SUBJECT)).thenThrow(exceptionOf(AI_REALTIME_SESSION_NOT_EXISTS));

        assertCode(() -> service.getSession(SESSION_ID), AI_REALTIME_SESSION_NOT_EXISTS);
    }

    // ==================== 夹具 ====================

    private void stubChannelOpen() {
        when(adapter.open(any())).thenReturn(channel);
    }

    private AiRealtimeSessionDO prepareOwnedOpenSession() {
        return prepareOwnedOpenSession(openSession());
    }

    private AiRealtimeSessionDO prepareOwnedOpenSession(AiRealtimeSessionDO owned) {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.of(SUBJECT));
        when(lifecycle.loadForSubject(SESSION_ID, SUBJECT)).thenReturn(owned);
        when(lifecycle.refreshLazyState(owned)).thenReturn(owned);
        return owned;
    }

    private void prepareTicketSession(AiRealtimeSessionDO owned) {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.of(SUBJECT));
        when(lifecycle.loadForTicketOperation(SESSION_ID, SUBJECT)).thenReturn(owned);
        when(lifecycle.refreshLazyState(owned)).thenReturn(owned);
    }

    private AiRealtimeVerifiedEndpoint verified() {
        ModelEndpointSnapshot snapshot = new ModelEndpointSnapshot(
                9L, 3, 2, "local-double", "https://example.invalid", "realtime-1", Set.of(), "***");
        return new AiRealtimeVerifiedEndpoint(
                9L, 3, 2, "realtime-1", RealtimeProtocol.WEBSOCKET, PCM_20MS, snapshot, adapter);
    }

    private static AiRealtimeAcceptDTO acceptRequest() {
        return new AiRealtimeAcceptDTO()
                .setRequestKey("rt_request_0001")
                .setEndpointId(9L)
                .setProtocol("WEBSOCKET")
                .setAudioFormat("audio/pcm@16000:1:20");
    }

    private static AiRealtimeAudioPushDTO frame(long turnNo, long frameSeq) {
        return new AiRealtimeAudioPushDTO()
                .setTurnNo(turnNo)
                .setFrameSeq(frameSeq)
                .setPayload("x".repeat(640).getBytes(StandardCharsets.UTF_8));
    }

    private static AiRealtimeSessionDO openSession() {
        return new AiRealtimeSessionDO()
                .setId(SESSION_ID)
                .setSessionKey("rts_test")
                .setRequestKey("rt_request_0001")
                .setApplicationId(7L)
                .setSubjectType("USER")
                .setExternalUserId("u-1")
                .setEndpointId(9L)
                .setEndpointConfigRevision(3)
                .setEndpointCredentialRevision(2)
                .setProtocol("WEBSOCKET")
                .setAudioFormat("audio/pcm@16000:1:20")
                .setStatus(AiRealtimeSessionDO.STATUS_OPEN)
                .setTurnNo(0L)
                .setDroppedStaleFrames(0)
                .setInputCapacityBytes(AiRealtimeParams.INPUT_CAPACITY_BYTES)
                .setBufferedBytes(0L)
                .setInputBytesTotal(0L)
                .setMuted(false)
                .setResumeAttempts(0)
                .setResumeDeadline(LocalDateTime.now().plusMinutes(5))
                .setTicketDigest(new AiRealtimeSessionTickets().digest(TICKET).orElseThrow())
                .setTicketRevision(1)
                .setTicketExpiresTime(LocalDateTime.now().plusMinutes(1))
                .setExpiresTime(LocalDateTime.now().plusMinutes(5))
                .setVersion(0);
    }

    private static AiRealtimeSessionDO closedSession(RealtimeCloseReason reason) {
        return openSession().setStatus(AiRealtimeSessionDO.STATUS_CLOSED).setCloseReason(reason.code());
    }

    private static ServiceException exceptionOf(ErrorCode code) {
        return new ServiceException(code);
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
