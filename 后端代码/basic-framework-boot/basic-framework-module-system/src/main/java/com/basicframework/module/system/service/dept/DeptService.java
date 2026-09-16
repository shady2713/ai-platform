package com.basicframework.module.system.service.dept;

import com.basicframework.framework.common.util.collection.CollectionUtils;
import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import java.util.*;

/**
 * 部门 Service 接口
 *
 */
public interface DeptService {

    /**
     * 创建部门
     *
     * @param dept 部门信息
     * @return 部门编号
     */
    Long createDept(DeptDO dept);

    /**
     * 更新部门
     *
     * @param updateObj 部门信息
     */
    void updateDept(DeptDO updateObj);

    /**
     * 删除部门
     *
     * @param id 部门编号
     */
    void deleteDept(Long id);

    /**
     * 批量删除部门
     *
     * @param ids 部门编号数组
     */
    void deleteDeptList(List<Long> ids);

    /**
     * 获得部门信息
     *
     * @param id 部门编号
     * @return 部门信息
     */
    DeptDO getDept(Long id);

    /**
     * 获得部门信息数组
     *
     * @param ids 部门编号数组
     * @return 部门信息数组
     */
    List<DeptDO> getDeptList(Collection<Long> ids);

    /**
     * 筛选部门列表
     *
     * @param name   部门名称，模糊匹配
     * @param status 展示状态
     * @return 部门列表
     */
    List<DeptDO> getDeptList(String name, Integer status);

    /**
     * 获得指定编号的部门 Map
     *
     * @param ids 部门编号数组
     * @return 部门 Map
     */
    default Map<Long, DeptDO> getDeptMap(Collection<Long> ids) {
        List<DeptDO> list = getDeptList(ids);
        return CollectionUtils.convertMap(list, DeptDO::getId);
    }

    /**
     * 获得指定部门的所有子部门
     *
     * @param id 部门编号
     * @return 子部门列表
     */
    default List<DeptDO> getChildDeptList(Long id) {
        return getChildDeptList(Collections.singleton(id));
    }

    /**
     * 获得指定部门的所有子部门
     *
     * @param ids 部门编号数组
     * @return 子部门列表
     */
    List<DeptDO> getChildDeptList(Collection<Long> ids);

    /**
     * 获得指定领导者的部门列表
     *
     * @param id 领导者编号
     * @return 部门列表
     */
    List<DeptDO> getDeptListByLeaderUserId(Long id);

    /**
     * 用户删除前解除其部门负责人引用。
     *
     * @param userId 用户编号
     */
    void processUserDeleted(Long userId);

    /**
     * 获得所有子部门编号
     *
     * @param id 父部门编号
     * @return 子部门列表
     */
    Set<Long> getChildDeptIdList(Long id);

    /**
     * 校验部门们是否有效。如下情况，视为无效：
     * 1. 部门编号不存在
     * 2. 部门被禁用
     *
     * @param ids 角色编号数组
     */
    void validateDeptList(Collection<Long> ids);

    /**
     * 校验部门有效并在当前事务内持有共享锁。
     *
     * <p>仅供即将持久化部门逻辑引用的写路径使用；调用方必须在同一事务内完成引用写入。
     *
     * @param ids 部门编号数组
     */
    void validateDeptListForReferenceWrite(Collection<Long> ids);

    /**
     * 根据部门名称获得部门信息
     *
     * @param name 部门名称
     * @return 部门信息
     */
    DeptDO getDeptByName(String name);
}
