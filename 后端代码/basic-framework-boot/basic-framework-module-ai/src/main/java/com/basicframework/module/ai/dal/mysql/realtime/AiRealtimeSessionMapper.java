package com.basicframework.module.ai.dal.mysql.realtime;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeSessionDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 实时语音会话 Mapper（X05）：定位、并发上限计数与所有在线状态的**条件更新**。
 *
 * <p>为什么状态迁移全部用条件更新而不是"读-判断-写"：会话会被并发的请求命中
 * （推流、打断、重连、关闭），读改写会超发重连次数、放过已到期会话或让打断后的旧回合继续写。
 * 每条 SQL 都把前置条件写进 WHERE：影响 0 行即"前置事实已变"，调用方按稳定原因拒绝或关闭，
 * 不重试、不猜测。
 */
@Mapper
public interface AiRealtimeSessionMapper extends BaseMapperX<AiRealtimeSessionDO> {

    /** 受理幂等定位：同一主体在同一应用下的同一 request_key 只受理一次（存活行唯一）。 */
    default AiRealtimeSessionDO selectByRequestKey(
            Long applicationId, String subjectType, String externalUserId, String requestKey) {
        return selectOne(new LambdaQueryWrapperX<AiRealtimeSessionDO>()
                .eq(AiRealtimeSessionDO::getApplicationId, applicationId)
                .eq(AiRealtimeSessionDO::getSubjectType, subjectType)
                .eq(AiRealtimeSessionDO::getExternalUserId, externalUserId)
                .eq(AiRealtimeSessionDO::getRequestKey, requestKey));
    }

    /** 同一应用的有效并发会话数（未关闭且未到期）。 */
    default long countActiveByApplication(Long applicationId, LocalDateTime now) {
        return selectCount(new LambdaQueryWrapperX<AiRealtimeSessionDO>()
                .eq(AiRealtimeSessionDO::getApplicationId, applicationId)
                .ne(AiRealtimeSessionDO::getStatus, AiRealtimeSessionDO.STATUS_CLOSED)
                .gt(AiRealtimeSessionDO::getExpiresTime, now));
    }

    /**
     * 同一主体有效并发会话数（**加锁读**，受理事务内使用）。
     *
     * <p>为什么受理必须加锁读：两个并发受理都读到"还有空位"就会一起插入而超发；
     * 加锁读按主体索引（application_id, subject_type, external_user_id, status, expires_time）
     * 取得该主体的间隙锁，使并发受理串行化（与 Q07 并发占位的修复同一口径）。
     */
    default long countActiveBySubjectForUpdate(
            Long applicationId, String subjectType, String externalUserId, LocalDateTime now) {
        return selectCount(new LambdaQueryWrapperX<AiRealtimeSessionDO>()
                .eq(AiRealtimeSessionDO::getApplicationId, applicationId)
                .eq(AiRealtimeSessionDO::getSubjectType, subjectType)
                .eq(AiRealtimeSessionDO::getExternalUserId, externalUserId)
                .ne(AiRealtimeSessionDO::getStatus, AiRealtimeSessionDO.STATUS_CLOSED)
                .gt(AiRealtimeSessionDO::getExpiresTime, now)
                .last("FOR UPDATE"));
    }

    /** 状态迁移：丢弃过期帧/事件的计数递增（视图与审计共用）。 */
    @Update("UPDATE ai_realtime_session SET dropped_stale_frames = dropped_stale_frames + #{count},"
            + " version = version + 1 WHERE id = #{sessionId} AND deleted = b'0'")
    int addDroppedStaleFrames(@Param("sessionId") Long sessionId, @Param("count") int count);

    /**
     * 有界缓冲占用（背压权威判定）：只有仍在线、未关麦、未到期且**加上本帧不超上限**时才占用。
     *
     * <p>影响 0 行有四种可能（不在线/已关麦/已到期/超限），调用方按当前事实区分：
     * 超限按 {@code audio-backpressure-exceeded} 结束会话，其余按稳定状态冲突拒绝。
     */
    @Update("UPDATE ai_realtime_session SET buffered_bytes = buffered_bytes + #{byteCount},"
            + " input_bytes_total = input_bytes_total + #{byteCount}, version = version + 1"
            + " WHERE id = #{sessionId} AND deleted = b'0' AND status = 'OPEN' AND muted = b'0'"
            + " AND expires_time > #{now} AND buffered_bytes + #{byteCount} <= input_capacity_bytes")
    int reserveInputBytes(
            @Param("sessionId") Long sessionId, @Param("byteCount") long byteCount, @Param("now") LocalDateTime now);

    /** 释放已消费输入字节（适配器已取走；不会产生负占用）。 */
    @Update("UPDATE ai_realtime_session SET buffered_bytes = GREATEST(buffered_bytes - #{byteCount}, 0),"
            + " version = version + 1 WHERE id = #{sessionId} AND deleted = b'0'")
    int drainInputBytes(@Param("sessionId") Long sessionId, @Param("byteCount") long byteCount);

    /**
     * 打断（回合栅栏推进）：回合号必须等于调用方看到的回合（否则 0 行——已被别的请求打断）。
     *
     * <p>同时清空待处理输入：被打断回合尚未被适配器取走的音频一律作废（由调用方记 STALE 事件）。
     */
    @Update("UPDATE ai_realtime_session SET turn_no = turn_no + 1, buffered_bytes = 0, version = version + 1"
            + " WHERE id = #{sessionId} AND deleted = b'0' AND turn_no = #{expectedTurnNo}"
            + " AND status IN ('OPEN','DETACHED') AND expires_time > #{now}")
    int advanceTurn(
            @Param("sessionId") Long sessionId,
            @Param("expectedTurnNo") long expectedTurnNo,
            @Param("now") LocalDateTime now);

    /** 标记断线（媒体通道断开）：开始重连时限计时（不晚于会话绝对到期）。 */
    @Update("UPDATE ai_realtime_session SET status = 'DETACHED', resume_deadline = #{deadline},"
            + " version = version + 1 WHERE id = #{sessionId} AND deleted = b'0' AND status = 'OPEN'"
            + " AND expires_time > #{now}")
    int detach(
            @Param("sessionId") Long sessionId,
            @Param("deadline") LocalDateTime deadline,
            @Param("now") LocalDateTime now);

    /**
     * 重连（有界）：次数未耗尽、时限未过、会话未到期才能回到在线。
     *
     * <p>重连不改协议、音频格式、端点与到期时间：这些是受理时固定的事实（换协议=新建会话）。
     */
    @Update("UPDATE ai_realtime_session SET status = 'OPEN', resume_attempts = resume_attempts + 1,"
            + " resume_deadline = expires_time, version = version + 1"
            + " WHERE id = #{sessionId} AND deleted = b'0' AND status IN ('OPEN','DETACHED')"
            + " AND resume_attempts < #{maxAttempts} AND resume_deadline > #{now} AND expires_time > #{now}")
    int resume(
            @Param("sessionId") Long sessionId, @Param("maxAttempts") int maxAttempts, @Param("now") LocalDateTime now);

    /** 关闭（终态，幂等）：已经是 CLOSED 时影响 0 行，调用方按既有原因返回。 */
    @Update("UPDATE ai_realtime_session SET status = 'CLOSED', close_reason = #{reasonCode}, buffered_bytes = 0,"
            + " version = version + 1 WHERE id = #{sessionId} AND deleted = b'0' AND status <> 'CLOSED'")
    int close(@Param("sessionId") Long sessionId, @Param("reasonCode") String reasonCode);

    /** 关麦/开麦：关麦期间不接受上行音频（关闭中的会话不可改）。 */
    @Update("UPDATE ai_realtime_session SET muted = #{muted}, version = version + 1"
            + " WHERE id = #{sessionId} AND deleted = b'0' AND status <> 'CLOSED'")
    int updateMuted(@Param("sessionId") Long sessionId, @Param("muted") boolean muted);

    /**
     * 续票（CAS）：票据未过期、会话未关闭未到期、票据代次未变才能换发新票据。
     *
     * <p>新票据到期时间不晚于会话绝对到期时间（会话寿命不因续票延长）。
     */
    @Update("UPDATE ai_realtime_session SET ticket_digest = #{newDigest},"
            + " ticket_revision = ticket_revision + 1, ticket_expires_time = #{newExpires}, version = version + 1"
            + " WHERE id = #{sessionId} AND deleted = b'0' AND ticket_revision = #{expectedRevision}"
            + " AND status IN ('OPEN','DETACHED') AND ticket_expires_time > #{now} AND expires_time > #{now}")
    int renewTicket(
            @Param("sessionId") Long sessionId,
            @Param("expectedRevision") int expectedRevision,
            @Param("newDigest") String newDigest,
            @Param("newExpires") LocalDateTime newExpires,
            @Param("now") LocalDateTime now);
}
