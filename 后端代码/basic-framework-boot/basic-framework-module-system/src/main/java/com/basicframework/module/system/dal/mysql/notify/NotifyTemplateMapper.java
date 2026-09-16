package com.basicframework.module.system.dal.mysql.notify;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.system.dal.dataobject.notify.NotifyTemplateDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NotifyTemplateMapper extends BaseMapperX<NotifyTemplateDO> {

    default NotifyTemplateDO selectByCode(String code) {
        return selectOne(NotifyTemplateDO::getCode, code);
    }

    default PageResult<NotifyTemplateDO> selectPage(PageParam pageParam, NotifyTemplateQuery query) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<NotifyTemplateDO>()
                        .likeIfPresent(NotifyTemplateDO::getCode, query.getCode())
                        .likeIfPresent(NotifyTemplateDO::getName, query.getName())
                        .eqIfPresent(NotifyTemplateDO::getType, query.getType())
                        .eqIfPresent(NotifyTemplateDO::getStatus, query.getStatus())
                        .betweenIfPresent(NotifyTemplateDO::getCreateTime, query.getCreateTime())
                        .orderByDesc(NotifyTemplateDO::getId));
    }
}
