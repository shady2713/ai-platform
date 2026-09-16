package com.basicframework.module.system.dal.mysql.notice;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.system.dal.dataobject.notice.NoticeDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NoticeMapper extends BaseMapperX<NoticeDO> {

    default PageResult<NoticeDO> selectPage(
            PageParam pageParam, String title, Integer status, LocalDateTime[] createTime) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<NoticeDO>()
                        .likeIfPresent(NoticeDO::getTitle, title)
                        .eqIfPresent(NoticeDO::getStatus, status)
                        .betweenIfPresent(NoticeDO::getCreateTime, createTime)
                        .orderByDesc(NoticeDO::getId));
    }
}
