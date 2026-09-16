package com.basicframework.module.system.dal.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.system.dal.mysql.dept.UserPostMapper;
import com.basicframework.module.system.dal.mysql.permission.RoleMenuMapper;
import com.basicframework.module.system.dal.mysql.permission.UserRoleMapper;
import java.lang.reflect.Method;
import java.util.Collection;
import org.apache.ibatis.annotations.Delete;
import org.junit.jupiter.api.Test;

/**
 * 三张关联表（system_user_role、system_role_menu、system_user_post）删除方法的物理删除守卫测试
 *
 * 这三张表在生命周期台账中声明为 hard-delete，且 V3 迁移已加组合唯一约束；
 * 用反射钉死 @Delete 物理删语句和目标表，避免未来基础实体调整改变撤销语义。
 */
class RelationTablePhysicalDeleteTest {

    @Test
    void relationTableDeleteMethods_arePhysicalDelete() throws Exception {
        // system_user_role，唯一键 uk_user_id_role_id
        assertPhysicalDelete(
                UserRoleMapper.class.getMethod("deleteListByUserIdAndRoleIdIds", Long.class, Collection.class),
                "system_user_role");
        assertPhysicalDelete(UserRoleMapper.class.getMethod("deleteListByUserId", Long.class), "system_user_role");
        assertPhysicalDelete(UserRoleMapper.class.getMethod("deleteListByRoleId", Long.class), "system_user_role");
        // system_role_menu，唯一键 uk_role_id_menu_id
        assertPhysicalDelete(
                RoleMenuMapper.class.getMethod("deleteListByRoleIdAndMenuIds", Long.class, Collection.class),
                "system_role_menu");
        assertPhysicalDelete(RoleMenuMapper.class.getMethod("deleteListByMenuId", Long.class), "system_role_menu");
        assertPhysicalDelete(RoleMenuMapper.class.getMethod("deleteListByRoleId", Long.class), "system_role_menu");
        // system_user_post，唯一键 uk_user_id_post_id
        assertPhysicalDelete(
                UserPostMapper.class.getMethod("deleteByUserIdAndPostId", Long.class, Collection.class),
                "system_user_post");
        assertPhysicalDelete(UserPostMapper.class.getMethod("deleteByUserId", Long.class), "system_user_post");
    }

    private void assertPhysicalDelete(Method method, String table) {
        Delete delete = method.getAnnotation(Delete.class);
        assertThat(delete).as("%s 必须声明 @Delete 物理删除语句", method).isNotNull();
        assertThat(delete.value()[0])
                .as("%s 的 SQL 必须是针对 %s 的物理删除", method.getName(), table)
                .containsIgnoringCase("DELETE FROM " + table);
    }
}
