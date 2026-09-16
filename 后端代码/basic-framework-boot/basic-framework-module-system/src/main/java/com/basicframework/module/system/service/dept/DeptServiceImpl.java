package com.basicframework.module.system.service.dept;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.util.collection.CollectionUtils.convertSet;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.datapermission.core.annotation.DataPermission;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.mysql.dept.DeptMapper;
import com.basicframework.module.system.dal.mysql.permission.RoleMapper;
import com.basicframework.module.system.dal.mysql.user.AdminUserMapper;
import com.basicframework.module.system.enums.permission.DataScopeEnum;
import com.google.common.annotations.VisibleForTesting;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/**
 * 部门 Service 实现类
 *
 */
@Service
@Validated
public class DeptServiceImpl implements DeptService {

    private final DeptMapper deptMapper;

    private final AdminUserMapper userMapper;

    private final RoleMapper roleMapper;

    public DeptServiceImpl(DeptMapper deptMapper, AdminUserMapper userMapper, RoleMapper roleMapper) {
        this.deptMapper = deptMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDept(DeptDO dept) {
        if (dept.getParentId() == null) {
            dept.setParentId(DeptDO.PARENT_ID_ROOT);
        }
        // 校验父部门的有效性
        validateParentDept(null, dept.getParentId());
        validateLeaderUser(dept.getLeaderUserId());
        // 校验部门名的唯一性
        validateDeptNameUnique(null, dept.getParentId(), dept.getName());

        // 插入部门
        deptMapper.insert(dept);
        return dept.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDept(DeptDO updateObj) {
        if (updateObj.getParentId() == null) {
            updateObj.setParentId(DeptDO.PARENT_ID_ROOT);
        }
        // 校验自己存在
        validateAndLockDept(updateObj.getId());
        // 校验父部门的有效性
        validateParentDept(updateObj.getId(), updateObj.getParentId());
        validateLeaderUser(updateObj.getLeaderUserId());
        // 校验部门名的唯一性
        validateDeptNameUnique(updateObj.getId(), updateObj.getParentId(), updateObj.getName());

        // 更新部门
        deptMapper.updateById(updateObj);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDept(Long id) {
        // 校验是否存在
        validateAndLockDept(id);
        // 校验是否有子部门
        if (deptMapper.selectCountByParentId(id) > 0) {
            throw exception(DEPT_EXISTS_CHILDREN);
        }
        validateDeptsNoUsers(Collections.singletonList(id));
        validateDeptsNoRoleDataScopes(Collections.singleton(id));
        // 删除部门
        deptMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDeptList(List<Long> ids) {
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw exception(DEPT_NOT_EXISTS);
        }
        List<Long> deptIds = ids.stream().distinct().sorted().toList();
        // 校验部门存在
        deptIds.forEach(this::validateAndLockDept);
        // 校验是否有子部门（排除本次也要删除的部门）
        for (Long id : deptIds) {
            if (deptMapper.selectCount(new LambdaQueryWrapperX<DeptDO>()
                            .eq(DeptDO::getParentId, id)
                            .notIn(DeptDO::getId, deptIds))
                    > 0) {
                throw exception(DEPT_EXISTS_CHILDREN);
            }
        }
        validateDeptsNoUsers(deptIds);
        validateDeptsNoRoleDataScopes(new HashSet<>(deptIds));

        // 批量删除部门
        deptMapper.deleteByIds(deptIds);
    }

    private void validateAndLockDept(Long id) {
        if (id == null || deptMapper.selectByIdForUpdate(id) == null) {
            throw exception(DEPT_NOT_EXISTS);
        }
    }

    private void validateDeptsNoUsers(Collection<Long> ids) {
        if (userMapper.selectCountByDeptIds(ids) > 0) {
            throw exception(DEPT_EXISTS_USER);
        }
    }

    private void validateDeptsNoRoleDataScopes(Set<Long> ids) {
        boolean referenced = roleMapper.selectListByDataScope(DataScopeEnum.DEPT_CUSTOM.getScope()).stream()
                .map(RoleDO::getDataScopeDeptIds)
                .filter(Objects::nonNull)
                .anyMatch(roleDeptIds -> !Collections.disjoint(roleDeptIds, ids));
        if (referenced) {
            throw exception(DEPT_EXISTS_ROLE_DATA_SCOPE);
        }
    }

    @VisibleForTesting
    void validateDeptExists(Long id) {
        if (id == null) {
            return;
        }
        DeptDO dept = deptMapper.selectById(id);
        if (dept == null) {
            throw exception(DEPT_NOT_EXISTS);
        }
    }

    @VisibleForTesting
    void validateParentDept(Long id, Long parentId) {
        if (parentId == null || DeptDO.PARENT_ID_ROOT.equals(parentId)) {
            return;
        }
        // 1. 不能设置自己为父部门
        if (Objects.equals(id, parentId)) {
            throw exception(DEPT_PARENT_ERROR);
        }
        // 2. 共享锁定父链，使引用写入与父部门删除、改挂串行化
        Set<Long> visitedParentIds = new HashSet<>();
        while (!DeptDO.PARENT_ID_ROOT.equals(parentId)) {
            if (Objects.equals(id, parentId)) {
                throw exception(DEPT_PARENT_IS_CHILD);
            }
            if (!visitedParentIds.add(parentId)) {
                throw exception(DEPT_PARENT_CYCLE);
            }
            DeptDO parentDept = deptMapper.selectByIdForShare(parentId);
            if (parentDept == null) {
                throw exception(DEPT_PARENT_NOT_EXISTS);
            }
            parentId = parentDept.getParentId();
        }
    }

    @VisibleForTesting
    void validateDeptNameUnique(Long id, Long parentId, String name) {
        DeptDO dept = deptMapper.selectByParentIdAndName(parentId, name);
        if (dept == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的部门
        if (id == null) {
            throw exception(DEPT_NAME_DUPLICATE);
        }
        if (ObjectUtil.notEqual(dept.getId(), id)) {
            throw exception(DEPT_NAME_DUPLICATE);
        }
    }

    @Override
    public DeptDO getDept(Long id) {
        return deptMapper.selectById(id);
    }

    @Override
    public List<DeptDO> getDeptList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return deptMapper.selectByIds(ids);
    }

    @Override
    public List<DeptDO> getDeptList(String name, Integer status) {
        List<DeptDO> list = deptMapper.selectList(name, status);
        list.sort(Comparator.comparing(DeptDO::getSort));
        return list;
    }

    @Override
    public List<DeptDO> getChildDeptList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        Map<Long, List<DeptDO>> childrenByParent = new HashMap<>();
        for (DeptDO dept : deptMapper.selectList()) {
            childrenByParent
                    .computeIfAbsent(dept.getParentId(), ignored -> new ArrayList<>())
                    .add(dept);
        }
        List<DeptDO> children = new ArrayList<>();
        Set<Long> visited = new HashSet<>(ids);
        Deque<Long> pending =
                new ArrayDeque<>(ids.stream().filter(Objects::nonNull).toList());
        while (!pending.isEmpty()) {
            for (DeptDO dept : childrenByParent.getOrDefault(pending.removeFirst(), Collections.emptyList())) {
                if (visited.add(dept.getId())) {
                    children.add(dept);
                    pending.addLast(dept.getId());
                }
            }
        }
        return children;
    }

    @Override
    public List<DeptDO> getDeptListByLeaderUserId(Long id) {
        return deptMapper.selectListByLeaderUserId(id);
    }

    @Override
    public void processUserDeleted(Long userId) {
        deptMapper.clearLeaderUserId(userId);
    }

    private void validateLeaderUser(Long leaderUserId) {
        if (leaderUserId != null && userMapper.selectById(leaderUserId) == null) {
            throw exception(USER_NOT_EXISTS);
        }
    }

    @Override
    @DataPermission(enable = false) // 计算授权范围时读取完整层级，避免递归触发数据权限
    public Set<Long> getChildDeptIdList(Long id) {
        if (id == null) {
            return Collections.emptySet();
        }
        List<DeptDO> children = getChildDeptList(id);
        return convertSet(children, DeptDO::getId);
    }

    @Override
    public void validateDeptList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        // 获得科室信息
        Map<Long, DeptDO> deptMap = getDeptMap(ids);
        // 校验
        ids.forEach(id -> {
            DeptDO dept = deptMap.get(id);
            if (dept == null) {
                throw exception(DEPT_NOT_EXISTS);
            }
            if (!CommonStatusEnum.ENABLE.getStatus().equals(dept.getStatus())) {
                throw exception(DEPT_NOT_ENABLE, dept.getName());
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void validateDeptListForReferenceWrite(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw exception(DEPT_NOT_EXISTS);
        }
        for (Long id : ids.stream().distinct().sorted().toList()) {
            DeptDO dept = deptMapper.selectByIdForShare(id);
            if (dept == null) {
                throw exception(DEPT_NOT_EXISTS);
            }
            if (!CommonStatusEnum.ENABLE.getStatus().equals(dept.getStatus())) {
                throw exception(DEPT_NOT_ENABLE, dept.getName());
            }
        }
    }

    @Override
    public DeptDO getDeptByName(String name) {
        if (StrUtil.isBlank(name)) {
            return null;
        }
        return deptMapper.selectFirstOne(DeptDO::getName, name);
    }
}
