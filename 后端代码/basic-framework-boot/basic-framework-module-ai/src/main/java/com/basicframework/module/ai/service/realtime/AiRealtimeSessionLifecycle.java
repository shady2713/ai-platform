package com.basicframework.module.ai.service.realtime;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_SESSION_NOT_EXISTS;

import com.basicframework.framework.ai.core.realtime.RealtimeCloseReason;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeEventDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeSessionDO;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeSessionMapper;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 会话生命周期判定（X05）：归属、惰性到期、断线超时与关闭。
 *
 * <p>为什么关闭是**惰性**的（读取/使用时判定，不做常驻扫描）：X10 的教训是常驻无界扫描把
 * NFR-04 的 accept p95 从 ≈160ms 打到 904ms。到期、断线超时都只在"有人碰这条会话"时物化，
 * 而"没人碰"的会话本来就不消耗任何在线资源（并发上限按 {@code expires_time > now} 计数）。
 *
 * <p>两种归属失败语义不同（按"是否出示票据"区分）：
 * <ul>
 *   <li>只带 MEMBER 会话的调用：不匹配按**不存在**拒绝，且**不关闭**会话——否则猜编号的人
 *       就能终止别人的会话（会话编号可枚举，属于拒绝服务面）；</li>
 *   <li>出示票据的调用（重连/续票）：票据是只有会话持有者才有的凭据，主体与票据同时指向
 *       一条会话而 MEMBER 身份不同——这就是平台唯一可观测的"切用户"信号，立即关闭会话
 *       （{@code identity-switched}），身份不可跨会话复用。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AiRealtimeSessionLifecycle {

    private final AiRealtimeSessionMapper sessionMapper;

    private final AiRealtimeEventApplier eventApplier;

    private final AiRealtimeChannels channels;

    /** 按 MEMBER 主体读取会话：不存在/不是本人一律 404（不产生副作用）。 */
    public AiRealtimeSessionDO loadForSubject(Long sessionId, AiConversationSubject subject) {
        AiRealtimeSessionDO session = requireSession(sessionId);
        if (!subject.sameAs(session.getApplicationId(), session.getSubjectType(), session.getExternalUserId())) {
            throw exception(AI_REALTIME_SESSION_NOT_EXISTS);
        }
        return session;
    }

    /** 按票据类操作读取会话：主体不匹配即"切用户"，关闭会话后按不存在拒绝。 */
    public AiRealtimeSessionDO loadForTicketOperation(Long sessionId, AiConversationSubject subject) {
        AiRealtimeSessionDO session = requireSession(sessionId);
        if (!subject.sameAs(session.getApplicationId(), session.getSubjectType(), session.getExternalUserId())) {
            close(session, RealtimeCloseReason.IDENTITY_SWITCHED, "identity-switched");
            throw exception(AI_REALTIME_SESSION_NOT_EXISTS);
        }
        return session;
    }

    /**
     * 惰性物化到期与断线超时：会话已过期 → 关闭（{@code session-expired}）；
     * 断线超过重连时限 → 关闭（{@code reattach-timeout}）。
     */
    public AiRealtimeSessionDO refreshLazyState(AiRealtimeSessionDO session) {
        if (session == null || AiRealtimeSessionDO.STATUS_CLOSED.equals(session.getStatus())) {
            return session;
        }
        LocalDateTime now = LocalDateTime.now();
        if (session.getExpiresTime() != null && !session.getExpiresTime().isAfter(now)) {
            close(session, RealtimeCloseReason.SESSION_EXPIRED, "expired");
            return sessionMapper.selectById(session.getId());
        }
        if (AiRealtimeSessionDO.STATUS_DETACHED.equals(session.getStatus())
                && session.getResumeDeadline() != null
                && !session.getResumeDeadline().isAfter(now)) {
            close(session, RealtimeCloseReason.REATTACH_TIMEOUT, "resume-deadline");
            return sessionMapper.selectById(session.getId());
        }
        return session;
    }

    /**
     * 关闭会话（幂等）：条件更新只让第一个赢家写终态并留痕，之后重复关闭不产生新事件与副作用。
     *
     * <p>通道关闭放在状态落库之后：数据库终态是权威事实，通道是本实例的传输资源。
     */
    public void close(AiRealtimeSessionDO session, RealtimeCloseReason reason, String detailCode) {
        if (session == null || session.getId() == null) {
            return;
        }
        if (sessionMapper.close(session.getId(), reason.code()) > 0) {
            eventApplier.record(
                    session.getId(),
                    AiRealtimeEventDO.TYPE_CLOSED,
                    session.getTurnNo() == null ? 0L : session.getTurnNo(),
                    0,
                    "",
                    0,
                    reason.code() + (detailCode == null || detailCode.isBlank() ? "" : ":" + detailCode),
                    "closed:" + reason.code());
        }
        channels.close(session.getId(), reason);
    }

    private AiRealtimeSessionDO requireSession(Long sessionId) {
        if (sessionId == null || sessionId <= 0) {
            throw exception(AI_REALTIME_SESSION_NOT_EXISTS);
        }
        AiRealtimeSessionDO session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw exception(AI_REALTIME_SESSION_NOT_EXISTS);
        }
        return session;
    }
}
