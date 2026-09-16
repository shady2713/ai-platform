package com.basicframework.module.system.service.permission;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.util.collection.CollectionUtils.convertMap;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;
import static com.basicframework.module.system.enums.LogRecordConstants.*;
import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.ROLE_PERMISSION_CHANGED;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.ObjectUtil;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.collection.CollectionUtils;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.mysql.permission.RoleMapper;
import com.basicframework.module.system.dal.mysql.permission.UserRoleMapper;
import com.basicframework.module.system.enums.permission.DataScopeEnum;
import com.basicframework.module.system.enums.permission.RoleCodeEnum;
import com.basicframework.module.system.enums.permission.RoleTypeEnum;
import com.basicframework.module.system.event.session.UserSessionRevocationPublisher;
import com.basicframework.module.system.service.dept.DeptService;
import com.google.common.annotations.VisibleForTesting;
import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.service.impl.DiffParseFunction;
import com.mzt.logapi.starter.annotation.LogRecord;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 角色 Service 实现类
 *
 */
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final ObjectProvider<PermissionService> permissionServiceProvider;

    private final RoleMapper roleMapper;

    private final UserRoleMapper userRoleMapper;

    private final DeptService deptService;

    private final UserSessionRevocationPublisher sessionRevocationPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_ROLE_TYPE,
            subType = SYSTEM_ROLE_CREATE_SUB_TYPE,
            bizNo = "{{#role.id}}",
            success = SYSTEM_ROLE_CREATE_SUCCESS)
    public Long createRole(RoleDO role, Integer type) {
        // 1. 校验角色
        validateRoleDuplicate(role.getName(), role.getCode(), null);

        // 2. 插入到数据库
        role.setType(ObjectUtil.defaultIfNull(type, RoleTypeEnum.CUSTOM.getType()))
                .setStatus(ObjUtil.defaultIfNull(role.getStatus(), CommonStatusEnum.ENABLE.getStatus()))
                .setDataScope(DataScopeEnum.SELF.getScope());
        roleMapper.insert(role);

        // 3. 记录操作日志上下文
        LogRecordContext.putVariable("role", role);
        return role.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_ROLE_TYPE,
            subType = SYSTEM_ROLE_UPDATE_SUB_TYPE,
            bizNo = "{{#updateObj.id}}",
            success = SYSTEM_ROLE_UPDATE_SUCCESS)
    public void updateRole(RoleDO updateObj) {
        // 1.1 校验是否可以更新
        RoleDO role = validateRoleForUpdate(updateObj.getId());
        // 1.2 校验角色的唯一字段是否重复
        validateRoleDuplicate(updateObj.getName(), updateObj.getCode(), updateObj.getId());

        // 1.3 校验是否被用户引用，不允许禁用
        if (CommonStatusEnum.isEnable(role.getStatus()) && CommonStatusEnum.isDisable(updateObj.getStatus())) {
            if (CollUtil.isNotEmpty(userRoleMapper.selectListByRoleIds(Collections.singletonList(updateObj.getId())))) {
                throw exception(ROLE_IS_REFERENCED, role.getName());
            }
        }

        // 2. 更新到数据库
        roleMapper.updateById(updateObj);
        revokeAffectedUserSessions(updateObj.getId());

        // 3. 记录操作日志上下文
        LogRecordContext.putVariable(DiffParseFunction.OLD_OBJECT, role);
        LogRecordContext.putVariable("role", role);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRoleDataScope(Long id, Integer dataScope, Set<Long> dataScopeDeptIds) {
        // 校验是否可以更新
        validateRoleForUpdate(id);
        DataScopeEnum dataScopeEnum = DataScopeEnum.fromScope(dataScope);
        if (dataScopeEnum == null) {
            throw exception(ROLE_DATA_SCOPE_INVALID, dataScope);
        }

        Set<Long> normalizedDeptIds = Collections.emptySet();
        if (dataScopeEnum == DataScopeEnum.DEPT_CUSTOM) {
            Collection<Long> requestedDeptIds = dataScopeDeptIds == null ? Collections.emptySet() : dataScopeDeptIds;
            deptService.validateDeptListForReferenceWrite(requestedDeptIds);
            normalizedDeptIds =
                    new LinkedHashSet<>(requestedDeptIds.stream().sorted().toList());
        }

        // 更新数据范围
        RoleDO updateObject = new RoleDO();
        updateObject.setId(id);
        updateObject.setDataScope(dataScope);
        updateObject.setDataScopeDeptIds(normalizedDeptIds);
        roleMapper.updateById(updateObject);
        revokeAffectedUserSessions(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_ROLE_TYPE,
            subType = SYSTEM_ROLE_DELETE_SUB_TYPE,
            bizNo = "{{#id}}",
            success = SYSTEM_ROLE_DELETE_SUCCESS)
    public void deleteRole(Long id) {
        // 1. 校验是否可以更新
        RoleDO role = validateRoleForUpdate(id);

        // 2.1 标记删除
        roleMapper.deleteById(id);
        // 2.2 删除相关数据
        getPermissionService().processRoleDeleted(id);

        // 3. 记录操作日志上下文
        LogRecordContext.putVariable("role", role);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRoleList(List<Long> ids) {
        // 1. 校验是否可以删除
        ids.forEach(this::validateRoleForUpdate);

        // 2.1 标记删除
        roleMapper.deleteByIds(ids);
        // 2.2 删除相关数据
        ids.forEach(id -> getPermissionService().processRoleDeleted(id));
    }

    /**
     * 校验角色的唯一字段是否重复
     *
     * 1. 是否存在相同名字的角色
     * 2. 是否存在相同编码的角色
     *
     * @param name 角色名字
     * @param code 角色额编码
     * @param id 角色编号
     */
    @VisibleForTesting
    void validateRoleDuplicate(String name, String code, Long id) {
        // 0. 超级管理员，不允许创建
        if (RoleCodeEnum.isSuperAdmin(code)) {
            throw exception(ROLE_ADMIN_CODE_ERROR, code);
        }
        // 1. 该 name 名字被其它角色所使用
        RoleDO role = roleMapper.selectByName(name);
        if (role != null && !role.getId().equals(id)) {
            throw exception(ROLE_NAME_DUPLICATE, name);
        }
        // 2. 是否存在相同编码的角色
        if (!StringUtils.hasText(code)) {
            return;
        }
        // 该 code 编码被其它角色所使用
        role = roleMapper.selectByCode(code);
        if (role != null && !role.getId().equals(id)) {
            throw exception(ROLE_CODE_DUPLICATE, code);
        }
    }

    /**
     * 校验角色是否可以被更新
     *
     * @param id 角色编号
     */
    @VisibleForTesting
    RoleDO validateRoleForUpdate(Long id) {
        RoleDO role = roleMapper.selectById(id);
        if (role == null) {
            throw exception(ROLE_NOT_EXISTS);
        }
        // 内置角色，不允许删除
        if (RoleTypeEnum.SYSTEM.getType().equals(role.getType())) {
            throw exception(ROLE_CAN_NOT_UPDATE_SYSTEM_TYPE_ROLE);
        }
        return role;
    }

    private void revokeAffectedUserSessions(Long roleId) {
        Set<Long> userIds = CollectionUtils.convertSet(
                userRoleMapper.selectListByRoleIds(Collections.singleton(roleId)), userRole -> userRole.getUserId());
        sessionRevocationPublisher.revokeAdminSessions(userIds, ROLE_PERMISSION_CHANGED);
    }

    @Override
    public RoleDO getRole(Long id) {
        return roleMapper.selectById(id);
    }

    @Override
    public List<RoleDO> getRoleListByStatus(Collection<Integer> statuses) {
        return roleMapper.selectListByStatus(statuses);
    }

    @Override
    public List<RoleDO> getRoleList() {
        return roleMapper.selectList();
    }

    @Override
    public List<RoleDO> getRoleList(Collection<Long> ids) {
        if (CollectionUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return roleMapper.selectByIds(ids);
    }

    @Override
    public PageResult<RoleDO> getRolePage(
            PageParam pageParam, String name, String code, Integer status, LocalDateTime[] createTime) {
        return roleMapper.selectPage(pageParam, name, code, status, createTime);
    }

    @Override
    public boolean hasAnySuperAdmin(Collection<Long> ids) {
        if (CollectionUtil.isEmpty(ids)) {
            return false;
        }
        return getRoleList(ids).stream().anyMatch(role -> RoleCodeEnum.isSuperAdmin(role.getCode()));
    }

    @Override
    public void validateRoleList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        // 获得角色信息
        List<RoleDO> roles = roleMapper.selectByIds(ids);
        Map<Long, RoleDO> roleMap = convertMap(roles, RoleDO::getId);
        // 校验
        ids.forEach(id -> {
            RoleDO role = roleMap.get(id);
            if (role == null) {
                throw exception(ROLE_NOT_EXISTS);
            }
            if (!CommonStatusEnum.ENABLE.getStatus().equals(role.getStatus())) {
                throw exception(ROLE_IS_DISABLE, role.getName());
            }
        });
    }

    private PermissionService getPermissionService() {
        return permissionServiceProvider.getObject();
    }
}
