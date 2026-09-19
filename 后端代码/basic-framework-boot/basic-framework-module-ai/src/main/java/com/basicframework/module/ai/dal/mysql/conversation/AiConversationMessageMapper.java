package com.basicframework.module.ai.dal.mysql.conversation;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationMessageDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话消息 Mapper（O01）：读取一律带主体条件与序号区间，正文不参与任何统计或日志。
 */
@Mapper
public interface AiConversationMessageMapper extends BaseMapperX<AiConversationMessageDO> {

    /** 会话内最大序号（无消息时为 null）。 */
    default Integer selectMaxSequence(Long conversationId) {
        AiConversationMessageDO latest = selectOne(new LambdaQueryWrapperX<AiConversationMessageDO>()
                .eq(AiConversationMessageDO::getConversationId, conversationId)
                .orderByDesc(AiConversationMessageDO::getSequenceNo)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getSequenceNo();
    }

    /** 按序号区间取消息（升序；用于分页与"最后 N 条"）。 */
    default List<AiConversationMessageDO> selectBySequence(Long conversationId, Integer afterSequence, int limit) {
        return selectList(new LambdaQueryWrapperX<AiConversationMessageDO>()
                .eq(AiConversationMessageDO::getConversationId, conversationId)
                .eq(AiConversationMessageDO::getStatus, AiConversationMessageDO.STATUS_ACTIVE)
                .gtIfPresent(AiConversationMessageDO::getSequenceNo, afterSequence)
                .orderByAsc(AiConversationMessageDO::getSequenceNo)
                .last("LIMIT " + limit));
    }

    /** 会话内全部消息（升序；会话删除时逐条关闭访问）。 */
    default List<AiConversationMessageDO> selectAll(Long conversationId) {
        return selectList(new LambdaQueryWrapperX<AiConversationMessageDO>()
                .eq(AiConversationMessageDO::getConversationId, conversationId)
                .orderByAsc(AiConversationMessageDO::getSequenceNo));
    }

    /** 关闭整个会话的消息访问（删除先关闭访问；不物理删除正文）。 */
    default int releaseAll(Long conversationId) {
        return update(
                null,
                new LambdaUpdateWrapper<AiConversationMessageDO>()
                        .eq(AiConversationMessageDO::getConversationId, conversationId)
                        .eq(AiConversationMessageDO::getStatus, AiConversationMessageDO.STATUS_ACTIVE)
                        .set(AiConversationMessageDO::getStatus, AiConversationMessageDO.STATUS_DELETED)
                        .setSql("version = version + 1"));
    }
}
