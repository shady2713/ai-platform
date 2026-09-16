package com.basicframework.module.system.dal.mysql.permission;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.lang.Nullable;

@Mapper
public interface RoleMapper extends BaseMapperX<RoleDO> {

    default PageResult<RoleDO> selectPage(
            PageParam pageParam, String name, String code, Integer status, LocalDateTime[] createTime) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<RoleDO>()
                        .likeIfPresent(RoleDO::getName, name)
                        .likeIfPresent(RoleDO::getCode, code)
                        .eqIfPresent(RoleDO::getStatus, status)
                        .betweenIfPresent(BaseDO::getCreateTime, createTime)
                        .orderByAsc(RoleDO::getSort));
    }

    default RoleDO selectByName(String name) {
        return selectOne(RoleDO::getName, name);
    }

    default RoleDO selectByCode(String code) {
        return selectOne(RoleDO::getCode, code);
    }

    default List<RoleDO> selectListByStatus(@Nullable Collection<Integer> statuses) {
        return selectList(RoleDO::getStatus, statuses);
    }

    default List<RoleDO> selectListByDataScope(Integer dataScope) {
        return selectList(RoleDO::getDataScope, dataScope);
    }

    @InterceptorIgnore(dataPermission = "true")
    @Select(
            """
            SELECT COUNT(*)
            FROM system_role r
            JOIN JSON_TABLE(
                r.data_scope_dept_ids,
                '$[*]' COLUMNS(dept_id BIGINT PATH '$' NULL ON ERROR)
            ) scope_dept ON TRUE
            LEFT JOIN system_dept d ON d.id = scope_dept.dept_id AND d.deleted = b'0'
            WHERE r.deleted = b'0'
              AND r.data_scope = #{customDataScope}
              AND (scope_dept.dept_id IS NULL OR d.id IS NULL)
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    int selectOrphanDataScopeDeptCount(@Param("customDataScope") Integer customDataScope);
}
