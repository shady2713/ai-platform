package com.basicframework.module.system.dal.mysql.permission;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.module.system.dal.dataobject.permission.RoleMenuDO;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RoleMenuMapper extends BaseMapperX<RoleMenuDO> {

    default List<RoleMenuDO> selectListByRoleId(Long roleId) {
        return selectList(RoleMenuDO::getRoleId, roleId);
    }

    default List<RoleMenuDO> selectListByRoleId(Collection<Long> roleIds) {
        return selectList(RoleMenuDO::getRoleId, roleIds);
    }

    default List<RoleMenuDO> selectListByMenuId(Long menuId) {
        return selectList(RoleMenuDO::getMenuId, menuId);
    }

    // 以下删除方法均为物理删除：关联表配合 V3 唯一约束 uk_role_id_menu_id，
    // 生命周期台账声明为 hard-delete；显式 SQL 同时钉死删除条件，避免基础实体调整改变授权撤销语义
    @Delete(
            "<script>DELETE FROM system_role_menu WHERE role_id = #{roleId} AND menu_id IN "
                    + "<foreach collection='menuIds' item='menuId' open='(' separator=',' close=')'>#{menuId}</foreach></script>")
    void deleteListByRoleIdAndMenuIds(@Param("roleId") Long roleId, @Param("menuIds") Collection<Long> menuIds);

    @Delete("DELETE FROM system_role_menu WHERE menu_id = #{menuId}")
    void deleteListByMenuId(@Param("menuId") Long menuId);

    @Delete("DELETE FROM system_role_menu WHERE role_id = #{roleId}")
    void deleteListByRoleId(@Param("roleId") Long roleId);
}
