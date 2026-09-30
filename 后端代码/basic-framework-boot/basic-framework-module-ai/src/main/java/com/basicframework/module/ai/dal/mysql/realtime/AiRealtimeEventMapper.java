package com.basicframework.module.ai.dal.mysql.realtime;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeEventDO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 实时会话事件 Mapper（X05）：只追加，按唯一去重键保证重放不产生重复行。
 *
 * <p>为什么用 {@code ON DUPLICATE KEY UPDATE id = id} 而不是先查后插：重放/并发重连会把同一事件
 * 送两次，先查后插在并发下仍可能撞唯一键并让整个请求失败；这里让重复写变成空操作，幂等且不报错。
 */
@Mapper
public interface AiRealtimeEventMapper extends BaseMapperX<AiRealtimeEventDO> {

    /** 最近事件（编号倒序取前 limit 条；调用方按需反转为升序）。 */
    default List<AiRealtimeEventDO> selectRecentBySession(Long sessionId, int limit) {
        return selectList(new LambdaQueryWrapperX<AiRealtimeEventDO>()
                .eq(AiRealtimeEventDO::getSessionId, sessionId)
                .orderByDesc(AiRealtimeEventDO::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    /** 追加事件（去重键冲突即空操作；返回 1=新增，2=重复被忽略）。 */
    @Insert("INSERT INTO ai_realtime_event (session_id, event_type, turn_no, event_seq, text_content, byte_count,"
            + " detail_code, dedup_key, creator, create_time)"
            + " VALUES (#{sessionId}, #{eventType}, #{turnNo}, #{eventSeq}, #{textContent}, #{byteCount},"
            + " #{detailCode}, #{dedupKey}, #{creator}, NOW())"
            + " ON DUPLICATE KEY UPDATE id = id")
    int insertDeduped(
            @Param("sessionId") Long sessionId,
            @Param("eventType") String eventType,
            @Param("turnNo") long turnNo,
            @Param("eventSeq") long eventSeq,
            @Param("textContent") String textContent,
            @Param("byteCount") int byteCount,
            @Param("detailCode") String detailCode,
            @Param("dedupKey") String dedupKey,
            @Param("creator") String creator);
}
