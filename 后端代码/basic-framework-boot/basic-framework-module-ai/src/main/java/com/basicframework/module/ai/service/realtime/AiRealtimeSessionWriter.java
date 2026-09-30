package com.basicframework.module.ai.service.realtime;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_IDEMPOTENCY_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_SESSION_LIMIT_EXCEEDED;

import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeSessionDO;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeSessionMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话受理事务（X05）：幂等定位 + 并发上限 + 落库（同一事务内完成）。
 *
 * <p>为什么受理要独立成事务方法：并发上限的判定必须与插入在同一事务里，且判定读要**加锁**
 * （见 {@link AiRealtimeSessionMapper#countActiveBySubjectForUpdate}），否则并发受理会一起通过
 * 检查而超发；通道打开（网络动作）留在事务之外，避免把外部等待圈进数据库事务。
 */
@Component
@RequiredArgsConstructor
public class AiRealtimeSessionWriter {

    private final AiRealtimeSessionMapper sessionMapper;

    private final AiRealtimeParams params;

    /** 受理事务的返回值：会话行 + 是否复用了已受理的会话（幂等重放）。 */
    public record Reservation(AiRealtimeSessionDO session, boolean reused) {}

    /**
     * 受理（事务内）：同键同形状复用，同键不同形状拒绝（409），超并发上限拒绝（429）。
     *
     * <p>重复受理**不重新开通道、不重发票据**：幂等键的语义是"同一个请求"，票据明文只在第一次
     * 受理出现一次（重放拿不到新票据，只能续票）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Reservation reserve(AiRealtimeSessionDO candidate) {
        AiRealtimeSessionDO existing = sessionMapper.selectByRequestKey(
                candidate.getApplicationId(),
                candidate.getSubjectType(),
                candidate.getExternalUserId(),
                candidate.getRequestKey());
        if (existing != null) {
            if (!sameShape(existing, candidate)) {
                throw exception(AI_IDEMPOTENCY_CONFLICT);
            }
            return new Reservation(existing, true);
        }
        LocalDateTime now = LocalDateTime.now();
        if (sessionMapper.countActiveBySubjectForUpdate(
                        candidate.getApplicationId(), candidate.getSubjectType(), candidate.getExternalUserId(), now)
                >= params.MAX_CONCURRENT_SESSIONS_PER_SUBJECT) {
            throw exception(AI_REALTIME_SESSION_LIMIT_EXCEEDED);
        }
        if (sessionMapper.countActiveByApplication(candidate.getApplicationId(), now)
                >= params.MAX_CONCURRENT_SESSIONS_PER_APPLICATION) {
            throw exception(AI_REALTIME_SESSION_LIMIT_EXCEEDED);
        }
        sessionMapper.insert(candidate);
        return new Reservation(candidate, false);
    }

    /**
     * 并发插入撞唯一键时的兜底：唯一键代表"同一主体的同一请求"，按幂等重放返回既有会话；
     * 查不到（例如被并发逻辑删除）则把原始冲突抛出，不吞掉。
     */
    public AiRealtimeSessionDO reloadAfterDuplicate(AiRealtimeSessionDO candidate, DuplicateKeyException race) {
        AiRealtimeSessionDO existing = sessionMapper.selectByRequestKey(
                candidate.getApplicationId(),
                candidate.getSubjectType(),
                candidate.getExternalUserId(),
                candidate.getRequestKey());
        if (existing == null) {
            throw race;
        }
        return existing;
    }

    private static boolean sameShape(AiRealtimeSessionDO existing, AiRealtimeSessionDO candidate) {
        return existing.getEndpointId().equals(candidate.getEndpointId())
                && existing.getProtocol().equals(candidate.getProtocol())
                && existing.getAudioFormat().equals(candidate.getAudioFormat());
    }
}
