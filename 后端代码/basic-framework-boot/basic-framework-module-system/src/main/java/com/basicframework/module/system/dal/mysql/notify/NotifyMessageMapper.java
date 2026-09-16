package com.basicframework.module.system.dal.mysql.notify;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.framework.mybatis.core.query.QueryWrapperX;
import com.basicframework.module.system.dal.dataobject.notify.NotifyMessageDO;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NotifyMessageMapper extends BaseMapperX<NotifyMessageDO> {

    @Delete(
            "DELETE FROM system_notify_message WHERE read_status = b'1' AND create_time < #{createTime} ORDER BY id LIMIT #{limit}")
    int deleteReadByCreateTimeLt(@Param("createTime") LocalDateTime createTime, @Param("limit") int limit);

    default PageResult<NotifyMessageDO> selectPage(PageParam pageParam, NotifyMessageQuery query) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<NotifyMessageDO>()
                        .eqIfPresent(NotifyMessageDO::getUserId, query.getUserId())
                        .eqIfPresent(NotifyMessageDO::getUserType, query.getUserType())
                        .likeIfPresent(NotifyMessageDO::getTemplateCode, query.getTemplateCode())
                        .eqIfPresent(NotifyMessageDO::getTemplateType, query.getTemplateType())
                        .betweenIfPresent(NotifyMessageDO::getCreateTime, query.getCreateTime())
                        .orderByDesc(NotifyMessageDO::getId));
    }

    default PageResult<NotifyMessageDO> selectPage(
            PageParam pageParam, Boolean readStatus, LocalDateTime[] createTime, Long userId, Integer userType) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<NotifyMessageDO>()
                        .eqIfPresent(NotifyMessageDO::getReadStatus, readStatus)
                        .betweenIfPresent(NotifyMessageDO::getCreateTime, createTime)
                        .eq(NotifyMessageDO::getUserId, userId)
                        .eq(NotifyMessageDO::getUserType, userType)
                        .orderByDesc(NotifyMessageDO::getId));
    }

    default int updateListRead(Collection<Long> ids, Long userId, Integer userType) {
        return update(
                new NotifyMessageDO().setReadStatus(true).setReadTime(LocalDateTime.now()),
                new LambdaQueryWrapperX<NotifyMessageDO>()
                        .in(NotifyMessageDO::getId, ids)
                        .eq(NotifyMessageDO::getUserId, userId)
                        .eq(NotifyMessageDO::getUserType, userType)
                        .eq(NotifyMessageDO::getReadStatus, false));
    }

    default int updateListRead(Long userId, Integer userType) {
        return update(
                new NotifyMessageDO().setReadStatus(true).setReadTime(LocalDateTime.now()),
                new LambdaQueryWrapperX<NotifyMessageDO>()
                        .eq(NotifyMessageDO::getUserId, userId)
                        .eq(NotifyMessageDO::getUserType, userType)
                        .eq(NotifyMessageDO::getReadStatus, false));
    }

    default List<NotifyMessageDO> selectUnreadListByUserIdAndUserType(Long userId, Integer userType, Integer size) {
        return selectList(new QueryWrapperX<NotifyMessageDO>() // 由于要使用 limitN 语句，所以只能用 QueryWrapperX
                .eq("user_id", userId)
                .eq("user_type", userType)
                .eq("read_status", false)
                .orderByDesc("id")
                .limitN(size));
    }

    default Long selectUnreadCountByUserIdAndUserType(Long userId, Integer userType) {
        return selectCount(new LambdaQueryWrapperX<NotifyMessageDO>()
                .eq(NotifyMessageDO::getReadStatus, false)
                .eq(NotifyMessageDO::getUserId, userId)
                .eq(NotifyMessageDO::getUserType, userType));
    }
}
