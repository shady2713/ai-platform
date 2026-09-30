package com.basicframework.module.ai.service.realtime;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_CALL_FAILED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_AUDIO_FORMAT_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_BACKPRESSURE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_MUTED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_REATTACH_BUDGET_EXCEEDED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_REATTACH_TIMEOUT_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_SESSION_CLOSED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_SESSION_DETACHED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_SESSION_EXPIRED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_SESSION_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_TICKET_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_TOOL_CALL_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_TURN_FUTURE_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_TURN_STALE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.realtime.RealtimeAudioFormat;
import com.basicframework.framework.ai.core.realtime.RealtimeAudioFrame;
import com.basicframework.framework.ai.core.realtime.RealtimeCloseReason;
import com.basicframework.framework.ai.core.realtime.RealtimeEvent;
import com.basicframework.framework.ai.core.realtime.RealtimeProtocol;
import com.basicframework.framework.ai.core.realtime.RealtimeSessionChannel;
import com.basicframework.framework.ai.core.realtime.RealtimeSessionOpenRequest;
import com.basicframework.framework.ai.core.realtime.RealtimeTurnFence;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeEventDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeSessionDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeToolCallDO;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeSessionMapper;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeToolCallMapper;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeAcceptDTO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeAudioPushDTO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeSessionViewDTO;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 实时语音会话实现（X05）：受理 → 推流/打断/关麦 → 断线重连/续票 → 关闭的完整垂直流程。
 *
 * <p>实现要点（与 ADR 0052 一致）：
 * <ul>
 *   <li><b>先验证再打开</b>：受理前先过 {@link AiRealtimeEndpointVerifier}（端点启用 + 适配器已注册 +
 *       真实探测确认），之后才落库并打开通道；通道打不开即把会话落终态（不留"看起来在线"的行）；</li>
 *   <li><b>所有状态迁移都是条件更新</b>：占字节、推回合、断线、重连、续票、关闭都带前置条件，
 *       影响 0 行即按当前事实给出稳定码（不重试、不猜测）；</li>
 *   <li><b>惰性关闭</b>：到期与断线超时只在读取/使用时物化（{@link AiRealtimeSessionLifecycle}），
 *       没有常驻扫描任务（X10 教训）；</li>
 *   <li><b>背压语义</b>：帧送进通道后由适配器回答"是否已消费"；未消费的字节继续占用有界缓冲，
 *       下一次推流就可能触顶——触顶即按 {@code audio-backpressure-exceeded} 结束会话。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AiRealtimeSessionServiceImpl implements AiRealtimeSessionService {

    private final AiConversationSubjectResolver subjectResolver;

    private final AiRealtimeParams params;

    private final AiRealtimeSessionTickets tickets;

    private final AiRealtimeEndpointVerifier endpointVerifier;

    private final AiRealtimeSessionWriter writer;

    private final AiRealtimeSessionLifecycle lifecycle;

    private final AiRealtimeChannels channels;

    private final AiRealtimeSessionViews views;

    private final AiRealtimeEventApplier eventApplier;

    private final AiRealtimeToolBridge toolBridge;

    private final AiRealtimeSessionMapper sessionMapper;

    private final AiRealtimeToolCallMapper toolCallMapper;

    @Override
    public AiRealtimeSessionViewDTO accept(AiRealtimeAcceptDTO request) {
        AiConversationSubject subject = requireSubject();
        if (request == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        String requestKey = params.requireRequestKey(request.getRequestKey());
        Long endpointId = params.requireEndpointId(request.getEndpointId());
        RealtimeProtocol protocol = params.requireProtocol(request.getProtocol());
        RealtimeAudioFormat audioFormat = params.requireAudioFormat(request.getAudioFormat());
        int sessionSeconds = params.normalizeSessionSeconds(request.getSessionSeconds());

        // 先验证（端点 + 适配器 + 真实探测确认），未通过就没有任何会话行与网络动作
        AiRealtimeVerifiedEndpoint verified = endpointVerifier.verify(endpointId, protocol, audioFormat);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresTime = now.plusSeconds(sessionSeconds);
        LocalDateTime ticketExpiresTime = earlier(now.plusSeconds(AiRealtimeParams.TICKET_TTL_SECONDS), expiresTime);
        String ticket = tickets.newTicket();
        AiRealtimeSessionDO candidate = new AiRealtimeSessionDO()
                .setSessionKey(tickets.newSessionKey())
                .setRequestKey(requestKey)
                .setApplicationId(subject.applicationId())
                .setSubjectType(subject.subjectTypeName())
                .setExternalUserId(subject.externalUserId())
                .setEndpointId(verified.endpointId())
                .setEndpointConfigRevision(verified.configRevision())
                .setEndpointCredentialRevision(verified.credentialRevision())
                .setModelRef(verified.modelRef())
                .setProtocol(verified.protocol().name())
                .setAudioFormat(verified.audioFormat().canonicalForm())
                .setStatus(AiRealtimeSessionDO.STATUS_OPEN)
                .setTurnNo(0L)
                .setDroppedStaleFrames(0)
                .setInputCapacityBytes(AiRealtimeParams.INPUT_CAPACITY_BYTES)
                .setBufferedBytes(0L)
                .setInputBytesTotal(0L)
                .setMuted(false)
                .setResumeAttempts(0)
                .setResumeDeadline(expiresTime)
                .setTicketDigest(tickets.digest(ticket).orElseThrow(() -> exception(AI_STATE_CONFLICT)))
                .setTicketRevision(1)
                .setTicketExpiresTime(ticketExpiresTime)
                .setExpiresTime(expiresTime)
                .setVersion(0);

        AiRealtimeSessionWriter.Reservation reservation;
        try {
            reservation = writer.reserve(candidate);
        } catch (DuplicateKeyException race) {
            // 并发受理撞唯一键：幂等重放（不重开通道、不重发票据）
            return views.toView(writer.reloadAfterDuplicate(candidate, race));
        }
        if (reservation.reused()) {
            return views.toView(reservation.session());
        }
        openChannel(reservation.session(), verified);
        return views.toView(reservation.session(), ticket);
    }

    @Override
    public AiRealtimeSessionViewDTO getSession(Long sessionId) {
        AiConversationSubject subject = requireSubject();
        AiRealtimeSessionDO session = lifecycle.refreshLazyState(lifecycle.loadForSubject(sessionId, subject));
        return views.toView(sessionMapper.selectById(session.getId()));
    }

    @Override
    public AiRealtimeSessionViewDTO pushAudio(Long sessionId, AiRealtimeAudioPushDTO frame) {
        AiConversationSubject subject = requireSubject();
        if (frame == null
                || frame.getTurnNo() == null
                || frame.getFrameSeq() == null
                || frame.getPayload() == null
                || frame.getPayload().length == 0) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiRealtimeSessionDO session = lifecycle.refreshLazyState(lifecycle.loadForSubject(sessionId, subject));
        requireOpenForMedia(session);
        RealtimeAudioFormat audioFormat =
                RealtimeAudioFormat.parse(session.getAudioFormat()).orElseThrow(() -> exception(AI_STATE_CONFLICT));
        if (frame.getPayload().length > audioFormat.maxFrameBytes()) {
            throw exception(AI_REALTIME_AUDIO_FORMAT_UNSUPPORTED);
        }
        RealtimeTurnFence fence = RealtimeTurnFence.of(
                session.getTurnNo() == null ? 0L : session.getTurnNo(),
                session.getDroppedStaleFrames() == null ? 0 : session.getDroppedStaleFrames());
        RealtimeTurnFence.Verdict verdict = fence.classify(frame.getTurnNo());
        if (verdict == RealtimeTurnFence.Verdict.STALE) {
            // 打断前的旧回合音频：丢弃、计数、明确拒绝（不静默接收，也不继续输出）
            sessionMapper.addDroppedStaleFrames(session.getId(), 1);
            eventApplier.record(
                    session.getId(),
                    AiRealtimeEventDO.TYPE_STALE_DROPPED,
                    frame.getTurnNo(),
                    frame.getFrameSeq(),
                    "",
                    frame.getPayload().length,
                    "STALE:CLIENT_FRAME",
                    "stale:CLIENT_FRAME:" + frame.getTurnNo() + ":" + frame.getFrameSeq());
            throw exception(AI_REALTIME_TURN_STALE_CONFLICT);
        }
        if (verdict == RealtimeTurnFence.Verdict.FUTURE) {
            throw exception(AI_REALTIME_TURN_FUTURE_INVALID);
        }
        RealtimeSessionChannel channel = channels.find(sessionId).orElse(null);
        if (channel == null) {
            // 本实例没有通道（重启或换实例）：按"已断线"处理，要求重连建立媒体面
            detachQuietly(session);
            throw exception(AI_REALTIME_SESSION_DETACHED_CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now();
        long byteCount = frame.getPayload().length;
        if (sessionMapper.reserveInputBytes(sessionId, byteCount, now) == 0) {
            throw reserveFailure(sessionId);
        }
        boolean consumed;
        try {
            consumed = channel.pushAudio(
                    new RealtimeAudioFrame(frame.getTurnNo(), frame.getFrameSeq(), frame.getPayload()));
        } catch (RuntimeException failure) {
            lifecycle.close(session, RealtimeCloseReason.ADAPTER_FAILED, stableFailureCode(failure));
            throw exception(AI_MODEL_CALL_FAILED);
        }
        if (consumed) {
            // 适配器已消费：释放占用；未消费则继续占用（背压来源）
            sessionMapper.drainInputBytes(sessionId, byteCount);
        }
        applyEvents(session, channel.pollEvents());
        return views.toView(sessionMapper.selectById(sessionId));
    }

    @Override
    public AiRealtimeSessionViewDTO interrupt(Long sessionId, Long turnNo) {
        AiConversationSubject subject = requireSubject();
        if (turnNo == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiRealtimeSessionDO session = lifecycle.refreshLazyState(lifecycle.loadForSubject(sessionId, subject));
        requireOpenForMedia(session);
        RealtimeTurnFence fence = RealtimeTurnFence.of(
                session.getTurnNo() == null ? 0L : session.getTurnNo(),
                session.getDroppedStaleFrames() == null ? 0 : session.getDroppedStaleFrames());
        RealtimeTurnFence.Verdict verdict = fence.classify(turnNo);
        if (verdict == RealtimeTurnFence.Verdict.STALE) {
            throw exception(AI_REALTIME_TURN_STALE_CONFLICT);
        }
        if (verdict == RealtimeTurnFence.Verdict.FUTURE) {
            throw exception(AI_REALTIME_TURN_FUTURE_INVALID);
        }
        long discardedBytes = session.getBufferedBytes() == null ? 0L : session.getBufferedBytes();
        LocalDateTime now = LocalDateTime.now();
        if (sessionMapper.advanceTurn(sessionId, turnNo, now) == 0) {
            // 已被别的请求推进/关闭/到期：按当前事实给出稳定码
            AiRealtimeSessionDO current = lifecycle.refreshLazyState(sessionMapper.selectById(sessionId));
            requireOpenForMedia(current);
            throw exception(AI_REALTIME_TURN_STALE_CONFLICT);
        }
        RealtimeSessionChannel channel = channels.find(sessionId).orElse(null);
        if (channel != null) {
            channel.interrupt(turnNo);
        }
        if (discardedBytes > 0) {
            // 被打断回合尚未被适配器取走的音频作废：显式记账（不允许静默丢弃）
            eventApplier.record(
                    sessionId,
                    AiRealtimeEventDO.TYPE_STALE_DROPPED,
                    turnNo,
                    0,
                    "",
                    (int) Math.min(Integer.MAX_VALUE, discardedBytes),
                    "INTERRUPT_DRAIN",
                    "stale:INTERRUPT_DRAIN:" + turnNo);
        }
        return views.toView(sessionMapper.selectById(sessionId));
    }

    @Override
    public AiRealtimeSessionViewDTO updateMuted(Long sessionId, boolean muted) {
        AiConversationSubject subject = requireSubject();
        AiRealtimeSessionDO session = lifecycle.refreshLazyState(lifecycle.loadForSubject(sessionId, subject));
        if (AiRealtimeSessionDO.STATUS_CLOSED.equals(session.getStatus())) {
            throw closedError(session);
        }
        if (sessionMapper.updateMuted(sessionId, muted) == 0) {
            throw closedError(lifecycle.refreshLazyState(sessionMapper.selectById(sessionId)));
        }
        return views.toView(sessionMapper.selectById(sessionId));
    }

    @Override
    public AiRealtimeSessionViewDTO detach(Long sessionId) {
        AiConversationSubject subject = requireSubject();
        AiRealtimeSessionDO session = lifecycle.refreshLazyState(lifecycle.loadForSubject(sessionId, subject));
        if (AiRealtimeSessionDO.STATUS_CLOSED.equals(session.getStatus())) {
            // 关闭是终态：断线是空操作（客户端可能与关闭并发）
            return views.toView(session);
        }
        if (AiRealtimeSessionDO.STATUS_DETACHED.equals(session.getStatus())) {
            // 重复断线不延长重连窗口（窗口从第一次断线开始计时）
            return views.toView(session);
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline =
                earlier(now.plusSeconds(AiRealtimeParams.REATTACH_WINDOW_SECONDS), session.getExpiresTime());
        if (sessionMapper.detach(sessionId, deadline, now) == 0) {
            AiRealtimeSessionDO current = lifecycle.refreshLazyState(sessionMapper.selectById(sessionId));
            if (AiRealtimeSessionDO.STATUS_CLOSED.equals(current.getStatus())
                    || AiRealtimeSessionDO.STATUS_DETACHED.equals(current.getStatus())) {
                return views.toView(current);
            }
            throw exception(AI_STATE_CONFLICT);
        }
        return views.toView(sessionMapper.selectById(sessionId));
    }

    @Override
    public AiRealtimeSessionViewDTO resume(Long sessionId, String ticket) {
        AiConversationSubject subject = requireSubject();
        AiRealtimeSessionDO session = lifecycle.refreshLazyState(lifecycle.loadForTicketOperation(sessionId, subject));
        if (AiRealtimeSessionDO.STATUS_CLOSED.equals(session.getStatus())) {
            throw closedError(session);
        }
        requireValidTicket(session, ticket);
        LocalDateTime now = LocalDateTime.now();
        if (session.getResumeDeadline() != null && !session.getResumeDeadline().isAfter(now)) {
            lifecycle.close(session, RealtimeCloseReason.REATTACH_TIMEOUT, "resume-deadline");
            throw exception(AI_REALTIME_REATTACH_TIMEOUT_CONFLICT);
        }
        if (session.getResumeAttempts() != null
                && session.getResumeAttempts() >= AiRealtimeParams.MAX_REATTACH_ATTEMPTS) {
            lifecycle.close(session, RealtimeCloseReason.REATTACH_BUDGET_EXCEEDED, "resume-budget");
            throw exception(AI_REALTIME_REATTACH_BUDGET_EXCEEDED);
        }
        if (sessionMapper.resume(sessionId, AiRealtimeParams.MAX_REATTACH_ATTEMPTS, now) == 0) {
            AiRealtimeSessionDO current = lifecycle.refreshLazyState(sessionMapper.selectById(sessionId));
            resumeFailure(current, now);
        }
        AiRealtimeSessionDO resumed = sessionMapper.selectById(sessionId);
        if (channels.find(sessionId).isEmpty()) {
            // 本实例没有通道：按受理时固定的配置/凭据重新建立媒体面（配置已变则拒绝续接）
            AiRealtimeVerifiedEndpoint verified = endpointVerifier.verifyPinned(resumed);
            openChannel(resumed, verified);
        }
        eventApplier.record(
                sessionId,
                AiRealtimeEventDO.TYPE_REATTACHED,
                resumed.getTurnNo() == null ? 0L : resumed.getTurnNo(),
                0,
                "",
                0,
                "attempt:" + resumed.getResumeAttempts(),
                "reattach:" + resumed.getResumeAttempts());
        return views.toView(sessionMapper.selectById(sessionId));
    }

    @Override
    public AiRealtimeSessionViewDTO renewTicket(Long sessionId, String ticket) {
        AiConversationSubject subject = requireSubject();
        AiRealtimeSessionDO session = lifecycle.refreshLazyState(lifecycle.loadForTicketOperation(sessionId, subject));
        if (AiRealtimeSessionDO.STATUS_CLOSED.equals(session.getStatus())) {
            throw closedError(session);
        }
        requireValidTicket(session, ticket);
        LocalDateTime now = LocalDateTime.now();
        String renewed = tickets.newTicket();
        LocalDateTime ticketExpiresTime =
                earlier(now.plusSeconds(AiRealtimeParams.TICKET_TTL_SECONDS), session.getExpiresTime());
        if (sessionMapper.renewTicket(
                        sessionId,
                        session.getTicketRevision() == null ? 1 : session.getTicketRevision(),
                        tickets.digest(renewed).orElseThrow(() -> exception(AI_STATE_CONFLICT)),
                        ticketExpiresTime,
                        now)
                == 0) {
            // 并发续票或状态已变：旧票据已被替换，要求客户端使用新票据（或按关闭/过期处理）
            AiRealtimeSessionDO current = lifecycle.refreshLazyState(sessionMapper.selectById(sessionId));
            if (current != null && AiRealtimeSessionDO.STATUS_CLOSED.equals(current.getStatus())) {
                throw closedError(current);
            }
            if (current != null && !ticketStillValid(current, now)) {
                throw exception(AI_REALTIME_TICKET_INVALID);
            }
            throw exception(AI_STATE_CONFLICT);
        }
        return views.toView(sessionMapper.selectById(sessionId), renewed);
    }

    @Override
    public AiRealtimeSessionViewDTO close(Long sessionId) {
        AiConversationSubject subject = requireSubject();
        AiRealtimeSessionDO session = lifecycle.refreshLazyState(lifecycle.loadForSubject(sessionId, subject));
        if (!AiRealtimeSessionDO.STATUS_CLOSED.equals(session.getStatus())) {
            lifecycle.close(session, RealtimeCloseReason.CLIENT_CLOSED, "client-closed");
        }
        return views.toView(sessionMapper.selectById(sessionId));
    }

    @Override
    public AiRealtimeSessionViewDTO executeToolCall(Long sessionId, Long toolCallId) {
        AiConversationSubject subject = requireSubject();
        AiRealtimeSessionDO session = lifecycle.refreshLazyState(lifecycle.loadForSubject(sessionId, subject));
        if (AiRealtimeSessionDO.STATUS_CLOSED.equals(session.getStatus())) {
            throw closedError(session);
        }
        if (toolCallId == null || toolCallId <= 0) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiRealtimeToolCallDO call = toolCallMapper.selectById(toolCallId);
        if (call == null || !sessionId.equals(call.getSessionId())) {
            throw exception(AI_REALTIME_TOOL_CALL_NOT_EXISTS);
        }
        toolBridge.execute(session, call);
        return views.toView(sessionMapper.selectById(sessionId));
    }

    /** 打开通道；失败即把会话落终态（不留"看起来在线"的行）并回上游失败码。 */
    private void openChannel(AiRealtimeSessionDO session, AiRealtimeVerifiedEndpoint verified) {
        try {
            RealtimeSessionChannel channel = verified.adapter()
                    .open(new RealtimeSessionOpenRequest(
                            session.getSessionKey(),
                            verified.snapshot(),
                            verified.protocol(),
                            verified.audioFormat(),
                            session.getInputCapacityBytes(),
                            session.getExpiresTime()));
            channels.register(session.getId(), channel);
        } catch (RuntimeException failure) {
            lifecycle.close(session, RealtimeCloseReason.OPEN_FAILED, stableFailureCode(failure));
            throw exception(AI_MODEL_CALL_FAILED);
        }
    }

    /** 消费适配器事件：结束事件/适配器违约会关闭会话；旧回合事件被丢弃并计数。 */
    private void applyEvents(AiRealtimeSessionDO session, List<RealtimeEvent> events) {
        AiRealtimeEventApplier.Outcome outcome = eventApplier.apply(session, events);
        if (outcome.shouldClose()) {
            lifecycle.close(session, outcome.closeReason(), outcome.detailCode());
        }
    }

    /** 占用失败的原因判定：按当前事实区分"超限（结束会话）/关麦/到期/关闭"。 */
    private RuntimeException reserveFailure(Long sessionId) {
        AiRealtimeSessionDO current = lifecycle.refreshLazyState(sessionMapper.selectById(sessionId));
        if (current == null) {
            return exception(AI_REALTIME_SESSION_NOT_EXISTS);
        }
        if (AiRealtimeSessionDO.STATUS_CLOSED.equals(current.getStatus())) {
            return closedError(current);
        }
        if (Boolean.TRUE.equals(current.getMuted())) {
            return exception(AI_REALTIME_MUTED_CONFLICT);
        }
        if (AiRealtimeSessionDO.STATUS_DETACHED.equals(current.getStatus())) {
            return exception(AI_REALTIME_SESSION_DETACHED_CONFLICT);
        }
        long buffered = current.getBufferedBytes() == null ? 0L : current.getBufferedBytes();
        long capacity = current.getInputCapacityBytes() == null ? 0L : current.getInputCapacityBytes();
        if (buffered >= capacity) {
            // 有界缓冲触顶：按稳定原因结束会话（绝不静默丢帧）
            lifecycle.close(current, RealtimeCloseReason.AUDIO_BACKPRESSURE_EXCEEDED, "capacity=" + capacity);
            return exception(AI_REALTIME_BACKPRESSURE_CONFLICT);
        }
        return exception(AI_STATE_CONFLICT);
    }

    /** 重连条件更新失败的原因判定（时限/次数/关闭/到期）。 */
    private void resumeFailure(AiRealtimeSessionDO current, LocalDateTime now) {
        if (current == null) {
            throw exception(AI_REALTIME_SESSION_NOT_EXISTS);
        }
        if (AiRealtimeSessionDO.STATUS_CLOSED.equals(current.getStatus())) {
            throw closedError(current);
        }
        if (current.getResumeDeadline() != null && !current.getResumeDeadline().isAfter(now)) {
            lifecycle.close(current, RealtimeCloseReason.REATTACH_TIMEOUT, "resume-deadline");
            throw exception(AI_REALTIME_REATTACH_TIMEOUT_CONFLICT);
        }
        if (current.getResumeAttempts() != null
                && current.getResumeAttempts() >= AiRealtimeParams.MAX_REATTACH_ATTEMPTS) {
            lifecycle.close(current, RealtimeCloseReason.REATTACH_BUDGET_EXCEEDED, "resume-budget");
            throw exception(AI_REALTIME_REATTACH_BUDGET_EXCEEDED);
        }
        if (current.getExpiresTime() != null && !current.getExpiresTime().isAfter(now)) {
            lifecycle.close(current, RealtimeCloseReason.SESSION_EXPIRED, "expired");
            throw exception(AI_REALTIME_SESSION_EXPIRED_CONFLICT);
        }
        throw exception(AI_STATE_CONFLICT);
    }

    /** 媒体面操作前置状态：关闭/断线都给出各自的稳定码（可操作的区别：重建 vs 重连）。 */
    private void requireOpenForMedia(AiRealtimeSessionDO session) {
        if (session == null) {
            throw exception(AI_REALTIME_SESSION_NOT_EXISTS);
        }
        if (AiRealtimeSessionDO.STATUS_CLOSED.equals(session.getStatus())) {
            throw closedError(session);
        }
        if (AiRealtimeSessionDO.STATUS_DETACHED.equals(session.getStatus())) {
            throw exception(AI_REALTIME_SESSION_DETACHED_CONFLICT);
        }
    }

    /** 关闭原因决定稳定错误码：到期是"重新受理"，其它关闭是"会话已结束"。 */
    private static RuntimeException closedError(AiRealtimeSessionDO session) {
        if (session != null && RealtimeCloseReason.SESSION_EXPIRED.code().equals(session.getCloseReason())) {
            return exception(AI_REALTIME_SESSION_EXPIRED_CONFLICT);
        }
        return exception(AI_REALTIME_SESSION_CLOSED_CONFLICT);
    }

    /** 票据校验：摘要匹配 + 票据未过期（摘要本身不落日志、不进错误消息）。 */
    private void requireValidTicket(AiRealtimeSessionDO session, String ticket) {
        if (!tickets.digestMatches(session.getTicketDigest(), ticket)) {
            throw exception(AI_REALTIME_TICKET_INVALID);
        }
        if (session.getTicketExpiresTime() == null
                || !session.getTicketExpiresTime().isAfter(LocalDateTime.now())) {
            // 到期票据不能再续票/重连：客户端应结束会话并重新受理（语义见 README）
            throw exception(AI_REALTIME_TICKET_INVALID);
        }
    }

    private boolean ticketStillValid(AiRealtimeSessionDO session, LocalDateTime now) {
        return session.getTicketExpiresTime() != null
                && session.getTicketExpiresTime().isAfter(now);
    }

    /** 无通道时按"断线"处理（只更新状态，不改写关闭原因）。 */
    private void detachQuietly(AiRealtimeSessionDO session) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline =
                earlier(now.plusSeconds(AiRealtimeParams.REATTACH_WINDOW_SECONDS), session.getExpiresTime());
        sessionMapper.detach(session.getId(), deadline, now);
    }

    private AiConversationSubject requireSubject() {
        return subjectResolver.resolveCurrent().orElseThrow(() -> exception(AI_RESOURCE_NOT_FOUND));
    }

    private static LocalDateTime earlier(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    private static String stableFailureCode(RuntimeException failure) {
        if (failure instanceof ModelException modelFailure) {
            return modelFailure.getReason().name();
        }
        return failure.getClass().getSimpleName();
    }
}
