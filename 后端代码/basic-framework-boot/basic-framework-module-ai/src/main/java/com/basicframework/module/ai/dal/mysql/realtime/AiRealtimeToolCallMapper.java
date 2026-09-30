package com.basicframework.module.ai.dal.mysql.realtime;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeToolCallDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 实时会话工具调用 Mapper（X05）：唯一键去重 + 一次性执行权（条件更新单赢家）。
 *
 * <p>"重连不重复执行工具"由两条事实共同保证：
 * <ol>
 *   <li>（会话, 回合, 调用标识）唯一：模型重复提出同一调用只对应一行；</li>
 *   <li>执行权只从 {@code PROPOSED} 消费一次：重放/并发重连看到的是 EXECUTING/EXECUTED，
 *       返回既有结论而不再调用执行器。</li>
 * </ol>
 */
@Mapper
public interface AiRealtimeToolCallMapper extends BaseMapperX<AiRealtimeToolCallDO> {

    /** 最近工具调用（编号倒序取前 limit 条；调用方按需反转为升序）。 */
    default List<AiRealtimeToolCallDO> selectRecentBySession(Long sessionId, int limit) {
        return selectList(new LambdaQueryWrapperX<AiRealtimeToolCallDO>()
                .eq(AiRealtimeToolCallDO::getSessionId, sessionId)
                .orderByDesc(AiRealtimeToolCallDO::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    /** 登记工具调用请求（重复提出同一调用是空操作；返回 1=新增，2=重复）。 */
    @Insert("INSERT INTO ai_realtime_tool_call (session_id, turn_no, call_id, tool_code, tool_version_id, policy,"
            + " arguments_json, arguments_hash, status, result_code, version, creator, create_time, updater,"
            + " update_time)"
            + " VALUES (#{sessionId}, #{turnNo}, #{callId}, #{toolCode}, #{toolVersionId}, #{policy},"
            + " #{argumentsJson}, #{argumentsHash}, #{status}, #{resultCode}, 0, #{creator}, NOW(), #{creator},"
            + " NOW())"
            + " ON DUPLICATE KEY UPDATE id = id")
    int insertDeduped(
            @Param("sessionId") Long sessionId,
            @Param("turnNo") long turnNo,
            @Param("callId") String callId,
            @Param("toolCode") String toolCode,
            @Param("toolVersionId") Long toolVersionId,
            @Param("policy") String policy,
            @Param("argumentsJson") String argumentsJson,
            @Param("argumentsHash") String argumentsHash,
            @Param("status") String status,
            @Param("resultCode") String resultCode,
            @Param("creator") String creator);

    /** 消费执行权（单赢家）：只有 PROPOSED 能进入 EXECUTING。 */
    @Update("UPDATE ai_realtime_tool_call SET status = 'EXECUTING', version = version + 1"
            + " WHERE id = #{callId} AND deleted = b'0' AND status = 'PROPOSED'")
    int claimExecution(@Param("callId") Long callId);

    /** 写执行终态（EXECUTED/FAILED）：只有 EXECUTING 能落终态（幂等重放不会覆盖）。 */
    @Update("UPDATE ai_realtime_tool_call SET status = #{status}, result_code = #{resultCode},"
            + " executed_time = #{now}, version = version + 1"
            + " WHERE id = #{callId} AND deleted = b'0' AND status = 'EXECUTING'")
    int finishExecution(
            @Param("callId") Long callId,
            @Param("status") String status,
            @Param("resultCode") String resultCode,
            @Param("now") LocalDateTime now);

    /** 拒绝（终态，不执行任何网络动作）：只有未进入执行的状态能被拒绝。 */
    @Update("UPDATE ai_realtime_tool_call SET status = 'REJECTED', result_code = #{resultCode},"
            + " executed_time = #{now}, version = version + 1"
            + " WHERE id = #{callId} AND deleted = b'0' AND status IN ('PROPOSED','EXECUTING')")
    int reject(@Param("callId") Long callId, @Param("resultCode") String resultCode, @Param("now") LocalDateTime now);

    /** 回写执行判定（工具已发布版本与政策）：执行权被消费后写入，之后不再变化。 */
    @Update("UPDATE ai_realtime_tool_call SET tool_version_id = #{toolVersionId}, policy = #{policy},"
            + " version = version + 1"
            + " WHERE id = #{callId} AND deleted = b'0' AND tool_version_id IS NULL")
    int bindDecision(
            @Param("callId") Long callId, @Param("toolVersionId") Long toolVersionId, @Param("policy") String policy);
}
