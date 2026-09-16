package com.basicframework.module.system.service.permission;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 角色 Service 接口
 *
 */
public interface RoleService {

    /**
     * 创建角色
     *
     * @param role 创建角色信息
     * @param type 角色类型
     * @return 角色编号
     */
    Long createRole(RoleDO role, Integer type);

    /**
     * 更新角色
     *
     * @param updateObj 更新角色信息
     */
    void updateRole(RoleDO updateObj);

    /**
     * 删除角色
     *
     * @param id 角色编号
     */
    void deleteRole(Long id);

    /**
     * 批量删除角色
     *
     * @param ids 角色编号数组
     */
    void deleteRoleList(List<Long> ids);

    /**
     * 设置角色的数据权限
     *
     * @param id 角色编号
     * @param dataScope 数据范围
     * @param dataScopeDeptIds 部门编号数组
     */
    void updateRoleDataScope(Long id, Integer dataScope, Set<Long> dataScopeDeptIds);

    /**
     * 获得角色
     *
     * @param id 角色编号
     * @return 角色
     */
    RoleDO getRole(Long id);

    /**
     * 获得角色列表
     *
     * @param ids 角色编号数组
     * @return 角色列表
     */
    List<RoleDO> getRoleList(Collection<Long> ids);

    /**
     * 获得角色列表
     *
     * @param statuses 筛选的状态
     * @return 角色列表
     */
    List<RoleDO> getRoleListByStatus(Collection<Integer> statuses);

    /**
     * 获得所有角色列表
     *
     * @return 角色列表
     */
    List<RoleDO> getRoleList();

    /**
     * 获得角色分页
     *
     * @param pageParam  分页参数
     * @param name       角色名称，模糊匹配
     * @param code       角色标识，模糊匹配
     * @param status     展示状态
     * @param createTime 创建时间区间
     * @return 角色分页结果
     */
    PageResult<RoleDO> getRolePage(
            PageParam pageParam, String name, String code, Integer status, LocalDateTime[] createTime);

    /**
     * 判断角色编号数组中，是否有管理员
     *
     * @param ids 角色编号数组
     * @return 是否有管理员
     */
    boolean hasAnySuperAdmin(Collection<Long> ids);

    /**
     * 校验角色们是否有效。如下情况，视为无效：
     * 1. 角色编号不存在
     * 2. 角色被禁用
     *
     * @param ids 角色编号数组
     */
    void validateRoleList(Collection<Long> ids);
}
