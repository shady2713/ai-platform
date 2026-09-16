package com.basicframework.module.infra.dal.mysql.config;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.infra.dal.dataobject.config.ConfigDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConfigMapper extends BaseMapperX<ConfigDO> {

    default ConfigDO selectByKey(String key) {
        return selectOne(ConfigDO::getConfigKey, key);
    }

    default PageResult<ConfigDO> selectPage(
            PageParam pageParam, String name, String key, Integer type, LocalDateTime[] createTime) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<ConfigDO>()
                        .likeIfPresent(ConfigDO::getName, name)
                        .likeIfPresent(ConfigDO::getConfigKey, key)
                        .eqIfPresent(ConfigDO::getType, type)
                        .betweenIfPresent(ConfigDO::getCreateTime, createTime)
                        .orderByDesc(ConfigDO::getId));
    }
}
