package com.basicframework.module.ai.dal.mysql.conversation;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话 Mapper（O01）：所有查询都带主体条件，越权访问在 SQL 层就不可能命中。
 */
@Mapper
public interface AiConversationMapper extends BaseMapperX<AiConversationDO> {

    /** 按业务键定位（同一应用+主体内唯一；软删除的行不参与）。 */
    default AiConversationDO selectByKey(
            Long applicationId, String subjectType, String externalUserId, String conversationKey) {
        return selectOne(new LambdaQueryWrapperX<AiConversationDO>()
                .eq(AiConversationDO::getApplicationId, applicationId)
                .eq(AiConversationDO::getSubjectType, subjectType)
                .eq(AiConversationDO::getExternalUserId, externalUserId == null ? "" : externalUserId)
                .eq(AiConversationDO::getConversationKey, conversationKey));
    }

    /** 按编号 + 主体定位：不是本人的会话等同于不存在。 */
    default AiConversationDO selectOwned(Long id, Long applicationId, String subjectType, String externalUserId) {
        return selectOne(new LambdaQueryWrapperX<AiConversationDO>()
                .eq(AiConversationDO::getId, id)
                .eq(AiConversationDO::getApplicationId, applicationId)
                .eq(AiConversationDO::getSubjectType, subjectType)
                .eq(AiConversationDO::getExternalUserId, externalUserId == null ? "" : externalUserId));
    }

    /** 当前主体的会话分页（按编号倒序：新增会话不影响已翻页结果）。 */
    default PageResult<AiConversationDO> selectPageBySubject(
            PageParam pageParam, Long applicationId, String subjectType, String externalUserId) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiConversationDO>()
                        .eq(AiConversationDO::getApplicationId, applicationId)
                        .eq(AiConversationDO::getSubjectType, subjectType)
                        .eq(AiConversationDO::getExternalUserId, externalUserId == null ? "" : externalUserId)
                        .orderByDesc(AiConversationDO::getId));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiConversationDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiConversationDO>()
                        .eq(AiConversationDO::getId, update.getId())
                        .eq(AiConversationDO::getVersion, expectedVersion));
    }
}
