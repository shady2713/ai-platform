package com.basicframework.module.system.dal.mysql.logger;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.system.dal.dataobject.logger.LoginLogDO;
import com.basicframework.module.system.enums.logger.LoginResultEnum;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LoginLogMapper extends BaseMapperX<LoginLogDO> {

    @Delete("DELETE FROM system_login_log WHERE create_time < #{createTime} ORDER BY id LIMIT #{limit}")
    int deleteByCreateTimeLt(@Param("createTime") LocalDateTime createTime, @Param("limit") int limit);

    default PageResult<LoginLogDO> selectPage(
            PageParam pageParam, String userIp, String username, LocalDateTime[] createTime, Boolean status) {
        LambdaQueryWrapperX<LoginLogDO> query = new LambdaQueryWrapperX<LoginLogDO>()
                .likeIfPresent(LoginLogDO::getUserIp, userIp)
                .likeIfPresent(LoginLogDO::getUsername, username)
                .betweenIfPresent(LoginLogDO::getCreateTime, createTime);
        if (Boolean.TRUE.equals(status)) {
            query.eq(LoginLogDO::getResult, LoginResultEnum.SUCCESS.getResult());
        } else if (Boolean.FALSE.equals(status)) {
            query.gt(LoginLogDO::getResult, LoginResultEnum.SUCCESS.getResult());
        }
        query.orderByDesc(LoginLogDO::getId); // 降序
        return selectPage(pageParam, query);
    }
}
