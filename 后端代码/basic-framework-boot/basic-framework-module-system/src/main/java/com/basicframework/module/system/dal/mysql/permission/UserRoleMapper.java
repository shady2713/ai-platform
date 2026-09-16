package com.basicframework.module.system.dal.mysql.permission;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.module.system.dal.dataobject.permission.UserRoleDO;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserRoleMapper extends BaseMapperX<UserRoleDO> {

    default List<UserRoleDO> selectListByUserId(Long userId) {
        return selectList(UserRoleDO::getUserId, userId);
    }

    // 以下删除方法均为物理删除：关联表配合 V3 唯一约束 uk_user_id_role_id，
    // 生命周期台账声明为 hard-delete；显式 SQL 同时钉死删除条件，避免基础实体调整改变授权撤销语义
    @Delete(
            "<script>DELETE FROM system_user_role WHERE user_id = #{userId} AND role_id IN "
                    + "<foreach collection='roleIds' item='roleId' open='(' separator=',' close=')'>#{roleId}</foreach></script>")
    void deleteListByUserIdAndRoleIdIds(@Param("userId") Long userId, @Param("roleIds") Collection<Long> roleIds);

    @Delete("DELETE FROM system_user_role WHERE user_id = #{userId}")
    void deleteListByUserId(@Param("userId") Long userId);

    @Delete("DELETE FROM system_user_role WHERE role_id = #{roleId}")
    void deleteListByRoleId(@Param("roleId") Long roleId);

    default List<UserRoleDO> selectListByRoleIds(Collection<Long> roleIds) {
        return selectList(UserRoleDO::getRoleId, roleIds);
    }
}
