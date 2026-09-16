package com.basicframework.module.system.dal.mysql.permission;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MenuMapper extends BaseMapperX<MenuDO> {

    default MenuDO selectByParentIdAndName(Long parentId, String name) {
        return selectOne(MenuDO::getParentId, parentId, MenuDO::getName, name);
    }

    default Long selectCountByParentId(Long parentId) {
        return selectCount(MenuDO::getParentId, parentId);
    }

    @Select("SELECT * FROM system_menu WHERE id = #{id} AND deleted = b'0' FOR SHARE")
    MenuDO selectByIdForShare(Long id);

    @Select("SELECT * FROM system_menu WHERE id = #{id} AND deleted = b'0' FOR UPDATE")
    MenuDO selectByIdForUpdate(Long id);

    @Select("SELECT id, parent_id, type FROM system_menu WHERE deleted = b'0'")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<MenuDO> selectListForIntegrityAudit();

    default List<MenuDO> selectList(String name, Integer status) {
        return selectList(new LambdaQueryWrapperX<MenuDO>()
                .likeIfPresent(MenuDO::getName, name)
                .eqIfPresent(MenuDO::getStatus, status));
    }

    default MenuDO selectByComponentName(String componentName) {
        return selectOne(MenuDO::getComponentName, componentName);
    }
}
