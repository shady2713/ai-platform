package com.basicframework.module.ai.dal.mysql.event;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.event.AiRunEventDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 运行事件 Mapper（O05）：序号分配、按序号重放与保留期清理。 */
@Mapper
public interface AiRunEventMapper extends BaseMapperX<AiRunEventDO> {

    /** 按序号区间取事件（升序；重放与订阅共用）。 */
    default List<AiRunEventDO> selectAfterSeq(Long runId, Integer afterSeq, int limit) {
        return selectList(new LambdaQueryWrapperX<AiRunEventDO>()
                .eq(AiRunEventDO::getRunId, runId)
                .gtIfPresent(AiRunEventDO::getSeq, afterSeq)
                .orderByAsc(AiRunEventDO::getSeq)
                .last("LIMIT " + limit));
    }

    /** 运行内最早的一条事件（判断重放窗口是否仍可用）。 */
    default AiRunEventDO selectFirst(Long runId) {
        return selectOne(new LambdaQueryWrapperX<AiRunEventDO>()
                .eq(AiRunEventDO::getRunId, runId)
                .orderByAsc(AiRunEventDO::getSeq)
                .last("LIMIT 1"));
    }

    /**
     * 并发安全分配事件序号：在运行行的行锁内递增并回读。
     *
     * <p>同一条 UPDATE 完成"读—改—写"，多个执行/订阅线程不会拿到同一个序号；
     * 序号与运行状态在同一事务提交，因此订阅者看到的顺序与库中状态一致。
     */
    @Update("UPDATE ai_run SET event_seq = event_seq + 1 WHERE id = #{runId} AND deleted = 0")
    int allocateSeq(@Param("runId") Long runId);

    /** 回读本次分配的序号。 */
    @Select("SELECT event_seq FROM ai_run WHERE id = #{runId}")
    int currentSeq(@Param("runId") Long runId);
}
