package com.basicframework.module.system.service.permission;

import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import java.util.Collection;
import java.util.List;

/**
 * 菜单 Service 接口
 *
 */
public interface MenuService {

    /**
     * 创建菜单
     *
     * @param menu 菜单信息
     * @return 创建出来的菜单编号
     */
    Long createMenu(MenuDO menu);

    /**
     * 更新菜单
     *
     * @param updateObj 菜单信息
     */
    void updateMenu(MenuDO updateObj);

    /**
     * 删除菜单
     *
     * @param id 菜单编号
     */
    void deleteMenu(Long id);

    /**
     * 批量删除菜单
     *
     * @param ids 菜单编号数组
     */
    void deleteMenuList(List<Long> ids);

    /**
     * 获得所有菜单列表
     *
     * @return 菜单列表
     */
    List<MenuDO> getMenuList();

    /**
     * 过滤掉关闭的菜单及其子菜单
     *
     * @param list 菜单列表
     * @return 过滤后的菜单列表
     */
    List<MenuDO> filterDisableMenus(List<MenuDO> list);

    /**
     * 筛选菜单列表
     *
     * @param name   菜单名称，模糊匹配
     * @param status 展示状态
     * @return 菜单列表
     */
    List<MenuDO> getMenuList(String name, Integer status);

    /**
     * 获得权限对应的菜单编号数组
     *
     * @param permission 权限标识
     * @return 数组
     */
    List<Long> getMenuIdListByPermission(String permission);

    /**
     * 获得菜单
     *
     * @param id 菜单编号
     * @return 菜单
     */
    MenuDO getMenu(Long id);

    /**
     * 获得菜单数组
     *
     * @param ids 菜单编号数组
     * @return 菜单数组
     */
    List<MenuDO> getMenuList(Collection<Long> ids);
}
