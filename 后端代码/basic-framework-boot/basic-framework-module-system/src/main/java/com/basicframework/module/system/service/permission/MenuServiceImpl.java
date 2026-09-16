package com.basicframework.module.system.service.permission;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.util.collection.CollectionUtils.convertMap;
import static com.basicframework.module.system.dal.dataobject.permission.MenuDO.ID_ROOT;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import com.basicframework.module.system.dal.mysql.permission.MenuMapper;
import com.basicframework.module.system.enums.permission.MenuTypeEnum;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 菜单 Service 实现
 *
 */
@Service
public class MenuServiceImpl implements MenuService {

    private final MenuMapper menuMapper;
    private final ObjectProvider<PermissionService> permissionServiceProvider;

    public MenuServiceImpl(MenuMapper menuMapper, ObjectProvider<PermissionService> permissionServiceProvider) {
        this.menuMapper = menuMapper;
        this.permissionServiceProvider = permissionServiceProvider;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createMenu(MenuDO menu) {
        // 校验父菜单存在
        validateParentMenu(menu.getParentId(), null);
        // 校验菜单（自己）
        validateMenuName(menu.getParentId(), menu.getName(), null);
        validateMenuComponentName(menu.getComponentName(), null);

        // 插入数据库
        initMenuProperty(menu);
        menuMapper.insert(menu);
        // 返回
        return menu.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMenu(MenuDO updateObj) {
        // 校验更新的菜单是否存在
        validateAndLockMenu(updateObj.getId());
        // 校验父菜单存在
        validateParentMenu(updateObj.getParentId(), updateObj.getId());
        validateMenuCanBecomeButton(updateObj);
        // 校验菜单（自己）
        validateMenuName(updateObj.getParentId(), updateObj.getName(), updateObj.getId());
        validateMenuComponentName(updateObj.getComponentName(), updateObj.getId());

        // 更新到数据库
        initMenuProperty(updateObj);
        menuMapper.updateById(updateObj);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMenu(Long id) {
        // 校验删除的菜单是否存在并锁定，和创建、改挂串行化
        validateAndLockMenu(id);
        // 校验是否还有子菜单
        if (menuMapper.selectCountByParentId(id) > 0) {
            throw exception(MENU_EXISTS_CHILDREN);
        }
        // 标记删除
        menuMapper.deleteById(id);
        // 删除授予给角色的权限
        getPermissionService().processMenuDeleted(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMenuList(List<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw exception(MENU_NOT_EXISTS);
        }
        List<Long> menuIds = ids.stream().distinct().sorted().toList();
        menuIds.forEach(this::validateAndLockMenu);
        // 校验是否还有子菜单
        menuIds.forEach(id -> {
            if (menuMapper.selectCountByParentId(id) > 0) {
                throw exception(MENU_EXISTS_CHILDREN);
            }
        });

        // 标记删除
        menuMapper.deleteByIds(menuIds);
        // 删除授予给角色的权限
        menuIds.forEach(id -> getPermissionService().processMenuDeleted(id));
    }

    @Override
    public List<MenuDO> getMenuList() {
        return menuMapper.selectList();
    }

    @Override
    public List<MenuDO> filterDisableMenus(List<MenuDO> menuList) {
        if (CollUtil.isEmpty(menuList)) {
            return Collections.emptyList();
        }
        Map<Long, MenuDO> menuMap = convertMap(menuList, MenuDO::getId);

        // 遍历 menu 菜单，查找不是禁用的菜单，添加到 enabledMenus 结果
        List<MenuDO> enabledMenus = new ArrayList<>();
        Set<Long> disabledMenuCache = new HashSet<>(); // 存下递归搜索过被禁用的菜单，防止重复的搜索
        for (MenuDO menu : menuList) {
            if (isMenuDisabled(menu, menuMap, disabledMenuCache, new HashSet<>())) {
                continue;
            }
            enabledMenus.add(menu);
        }
        return enabledMenus;
    }

    private boolean isMenuDisabled(
            MenuDO node, Map<Long, MenuDO> menuMap, Set<Long> disabledMenuCache, Set<Long> visitingMenuIds) {
        // 如果已经判定是禁用的节点，直接结束
        if (disabledMenuCache.contains(node.getId())) {
            return true;
        }

        // 1. 先判断自身是否禁用
        if (!CommonStatusEnum.ENABLE.getStatus().equals(node.getStatus())) {
            disabledMenuCache.add(node.getId());
            return true;
        }

        // 数据被绕过服务篡改成循环时按禁用处理，避免权限信息接口递归溢出
        if (!visitingMenuIds.add(node.getId())) {
            disabledMenuCache.addAll(visitingMenuIds);
            return true;
        }

        try {
            // 2. 遍历到 parentId 为根节点，则无需判断
            Long parentId = node.getParentId();
            if (ObjUtil.equal(parentId, ID_ROOT)) {
                return false;
            }

            // 3. 继续遍历 parent 节点；孤儿和循环均按禁用处理
            MenuDO parent = menuMap.get(parentId);
            if (parent == null || isMenuDisabled(parent, menuMap, disabledMenuCache, visitingMenuIds)) {
                disabledMenuCache.add(node.getId());
                return true;
            }
            return false;
        } finally {
            visitingMenuIds.remove(node.getId());
        }
    }

    @Override
    public List<MenuDO> getMenuList(String name, Integer status) {
        return menuMapper.selectList(name, status);
    }

    @Override
    public List<Long> getMenuIdListByPermission(String permission) {
        if (StrUtil.isBlank(permission)) {
            return Collections.emptyList();
        }
        return filterDisableMenus(menuMapper.selectList()).stream()
                .filter(menu -> permission.equals(menu.getPermission()))
                .map(MenuDO::getId)
                .toList();
    }

    @Override
    public MenuDO getMenu(Long id) {
        return menuMapper.selectById(id);
    }

    @Override
    public List<MenuDO> getMenuList(Collection<Long> ids) {
        // 当 ids 为空时，返回一个空的实例对象
        if (CollUtil.isEmpty(ids)) {
            return Lists.newArrayList();
        }
        return menuMapper.selectByIds(ids);
    }

    /**
     * 校验父菜单是否合法
     * <p>
     * 1. 不能设置自己为父菜单
     * 2. 父菜单不存在
     * 3. 父菜单必须是目录或菜单类型
     *
     * @param parentId 父菜单编号
     * @param childId  当前菜单编号
     */
    @VisibleForTesting
    void validateParentMenu(Long parentId, Long childId) {
        if (parentId == null || ID_ROOT.equals(parentId)) {
            return;
        }
        // 不能设置自己为父菜单
        if (parentId.equals(childId)) {
            throw exception(MENU_PARENT_ERROR);
        }
        Set<Long> visitedParentIds = new HashSet<>();
        while (!ID_ROOT.equals(parentId)) {
            if (Objects.equals(childId, parentId)) {
                throw exception(MENU_PARENT_IS_CHILD);
            }
            if (!visitedParentIds.add(parentId)) {
                throw exception(MENU_PARENT_CYCLE);
            }
            MenuDO parentMenu = menuMapper.selectByIdForShare(parentId);
            if (parentMenu == null) {
                throw exception(MENU_PARENT_NOT_EXISTS);
            }
            if (!isParentCapable(parentMenu)) {
                throw exception(MENU_PARENT_NOT_DIR_OR_MENU);
            }
            parentId = parentMenu.getParentId();
        }
    }

    private void validateAndLockMenu(Long id) {
        if (id == null || menuMapper.selectByIdForUpdate(id) == null) {
            throw exception(MENU_NOT_EXISTS);
        }
    }

    private void validateMenuCanBecomeButton(MenuDO menu) {
        if (!MenuTypeEnum.BUTTON.getType().equals(menu.getType())) {
            return;
        }
        if (menuMapper.selectCountByParentId(menu.getId()) > 0) {
            throw exception(MENU_BUTTON_EXISTS_CHILDREN);
        }
    }

    private static boolean isParentCapable(MenuDO menu) {
        return MenuTypeEnum.DIR.getType().equals(menu.getType())
                || MenuTypeEnum.MENU.getType().equals(menu.getType());
    }

    /**
     * 校验菜单是否合法
     * <p>
     * 1. 校验相同父菜单编号下，是否存在相同的菜单名
     *
     * @param name     菜单名字
     * @param parentId 父菜单编号
     * @param id       菜单编号
     */
    @VisibleForTesting
    void validateMenuName(Long parentId, String name, Long id) {
        MenuDO menu = menuMapper.selectByParentIdAndName(parentId, name);
        if (menu == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的菜单
        if (id == null) {
            throw exception(MENU_NAME_DUPLICATE);
        }
        if (!menu.getId().equals(id)) {
            throw exception(MENU_NAME_DUPLICATE);
        }
    }

    /**
     * 校验菜单组件名是否合法
     *
     * @param componentName 组件名
     * @param id            菜单编号
     */
    @VisibleForTesting
    void validateMenuComponentName(String componentName, Long id) {
        if (StrUtil.isBlank(componentName)) {
            return;
        }
        MenuDO menu = menuMapper.selectByComponentName(componentName);
        if (menu == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的菜单
        if (id == null) {
            throw exception(MENU_COMPONENT_NAME_DUPLICATE);
        }
        if (!menu.getId().equals(id)) {
            throw exception(MENU_COMPONENT_NAME_DUPLICATE);
        }
    }

    private PermissionService getPermissionService() {
        return permissionServiceProvider.getObject();
    }

    /**
     * 初始化菜单的通用属性。
     * <p>
     * 例如说，只有目录或者菜单类型的菜单，才设置 icon
     *
     * @param menu 菜单
     */
    private void initMenuProperty(MenuDO menu) {
        // 菜单为按钮类型时，无需 component、icon、path 属性，进行置空
        if (MenuTypeEnum.BUTTON.getType().equals(menu.getType())) {
            menu.setComponent("");
            menu.setComponentName("");
            menu.setIcon("");
            menu.setPath("");
        }
    }
}
