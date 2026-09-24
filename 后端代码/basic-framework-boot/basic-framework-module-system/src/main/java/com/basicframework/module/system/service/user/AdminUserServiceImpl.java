package com.basicframework.module.system.service.user;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.util.collection.CollectionUtils.*;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;
import static com.basicframework.module.system.enums.LogRecordConstants.*;
import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.*;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ObjUtil;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.collection.CollectionUtils;
import com.basicframework.framework.common.util.validation.ValidationUtils;
import com.basicframework.framework.datapermission.core.annotation.DataPermission;
import com.basicframework.module.system.dal.dataobject.dept.UserPostDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.dept.UserPostMapper;
import com.basicframework.module.system.dal.mysql.user.AdminUserMapper;
import com.basicframework.module.system.dal.mysql.user.AdminUserQuery;
import com.basicframework.module.system.event.session.UserSessionRevocationPublisher;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.permission.PermissionService;
import com.basicframework.module.system.service.user.dto.UserImportDTO;
import com.basicframework.module.system.service.user.dto.UserImportResultDTO;
import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.service.impl.DiffParseFunction;
import com.mzt.logapi.starter.annotation.LogRecord;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 后台用户生命周期服务，统一维护账号约束、部门岗位关系及影响认证状态时的会话撤销。 */
@Service("adminUserService")
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private final AdminUserMapper userMapper;

    private final DeptService deptService;

    private final ObjectProvider<PermissionService> permissionServiceProvider;

    private final UserSessionRevocationPublisher sessionRevocationPublisher;

    private final UserPostMapper userPostMapper;

    private final AdminUserUniqueValidator userValidator;

    private final AdminUserPasswordManager passwordManager;

    private final PasswordPolicy passwordPolicy;

    private final UserImportHandler importHandler;

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_CREATE_SUB_TYPE,
            bizNo = "{{#user.id}}",
            success = SYSTEM_USER_CREATE_SUCCESS)
    public Long createUser(AdminUserDO user) {
        user.setUsername(ValidationUtils.normalizeUsername(user.getUsername()));
        normalizeWritableContactFields(user);
        userValidator.validateForCreateOrUpdate(
                null, user.getUsername(), user.getMobile(), user.getEmail(), user.getDeptId(), user.getPostIds());
        user.setStatus(ObjUtil.defaultIfNull(user.getStatus(), CommonStatusEnum.ENABLE.getStatus()));
        passwordPolicy.validate(user.getPassword(), user.getUsername());
        user.setPassword(passwordManager.encode(user.getPassword()));
        // 管理员分配的初始口令属于“已知口令”，首次登录必须修改
        user.setMustChangePassword(Boolean.TRUE);
        userMapper.insert(user);
        if (CollectionUtil.isNotEmpty(user.getPostIds())) {
            // 同 updateUserPost：逐条 insert 走当前事务的 SqlSession，避免 Db.saveBatch 另开连接后
            // 因外键校验在事务外等自己持有的 system_users 行锁。
            convertList(
                            user.getPostIds(),
                            postId -> new UserPostDO().setUserId(user.getId()).setPostId(postId))
                    .forEach(userPostMapper::insert);
        }

        LogRecordContext.putVariable("user", user);
        return user.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_UPDATE_SUB_TYPE,
            bizNo = "{{#updateObj.id}}",
            success = SYSTEM_USER_UPDATE_SUCCESS)
    public void updateUser(Long operatorUserId, AdminUserDO updateObj) {
        // 联系信息是身份证明边界（短信重置按手机号确权）：改写超管联系方式等效于接管账号
        getPermissionService()
                .validatePrivilegedUserMutation(operatorUserId, Collections.singletonList(updateObj.getId()));
        updateObj.setPassword(null); // 特殊：此处不更新密码
        updateObj.setMustChangePassword(null);
        // 特殊：此处不更新状态；禁用/启用只能走带特权校验的 update-status 专用接口
        updateObj.setStatus(null);
        updateObj.setUsername(ValidationUtils.normalizeUsername(updateObj.getUsername()));
        normalizeWritableContactFields(updateObj);
        updateObj.setPostIds(new HashSet<>(CollUtil.emptyIfNull(updateObj.getPostIds())));
        AdminUserDO oldUser = getUserForUpdate(updateObj.getId());
        userValidator.validateForCreateOrUpdate(
                updateObj.getId(),
                updateObj.getUsername(),
                updateObj.getMobile(),
                updateObj.getEmail(),
                updateObj.getDeptId(),
                updateObj.getPostIds());

        if (userMapper.updateManagedUser(updateObj) != 1) {
            throw exception(USER_NOT_EXISTS);
        }
        updateUserPost(updateObj);
        if (hasManagedSessionInfoChanged(oldUser, updateObj)) {
            sessionRevocationPublisher.revokeAdminSession(updateObj.getId(), USER_INFO_CHANGED);
        }

        // 避免前端未传递 avatar 字段时，操作日志 diff 误报为删除头像
        if (updateObj.getAvatar() == null) {
            updateObj.setAvatar(oldUser.getAvatar());
        }
        LogRecordContext.putVariable(DiffParseFunction.OLD_OBJECT, oldUser);
        LogRecordContext.putVariable("user", oldUser);
    }

    private void updateUserPost(AdminUserDO updateObj) {
        Long userId = updateObj.getId();
        Set<Long> dbPostIds = convertSet(userPostMapper.selectListByUserIdForUpdate(userId), UserPostDO::getPostId);
        Set<Long> postIds = CollUtil.emptyIfNull(updateObj.getPostIds());
        Collection<Long> createPostIds = CollUtil.subtract(postIds, dbPostIds);
        Collection<Long> deletePostIds = CollUtil.subtract(dbPostIds, postIds);
        if (!CollectionUtil.isEmpty(createPostIds)) {
            // 岗位关系是个位数：逐条 insert 走当前事务的 SqlSession。
            // BaseMapperX.insertBatch 走 Db.saveBatch 会另开 SqlSession/连接（autocommit），
            // 在已持 system_users 行锁的事务里，那条插入因外键校验要拿父行 S 锁 → 等自己 50 秒锁超时。
            convertList(
                            createPostIds,
                            postId -> new UserPostDO().setUserId(userId).setPostId(postId))
                    .forEach(userPostMapper::insert);
        }
        if (!CollectionUtil.isEmpty(deletePostIds)) {
            userPostMapper.deleteByUserIdAndPostId(userId, deletePostIds);
        }
    }

    @Override
    public void updateUserLogin(Long id, String loginIp) {
        userMapper.updateById(new AdminUserDO().setId(id).setLoginIp(loginIp).setLoginDate(LocalDateTime.now()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUserProfile(Long id, AdminUserDO user) {
        AdminUserDO oldUser = getUserForUpdate(id);
        normalizeWritableContactFields(user);
        userValidator.validateEmailUnique(id, user.getEmail());
        userValidator.validateMobileUnique(id, user.getMobile());
        if (userMapper.updateUserProfile(user.setId(id)) != 1) {
            throw exception(USER_NOT_EXISTS);
        }
        if (hasNicknameChanged(oldUser, user)) {
            sessionRevocationPublisher.revokeAdminSession(id, USER_INFO_CHANGED);
        }
    }

    private static boolean hasManagedSessionInfoChanged(AdminUserDO oldUser, AdminUserDO newUser) {
        // mobile 是短信重置链的确权锚点，email 用于找回与通知：被管理面改写后吊销目标全部会话
        return hasNicknameChanged(oldUser, newUser)
                || !Objects.equals(oldUser.getDeptId(), newUser.getDeptId())
                || !Objects.equals(oldUser.getMobile(), newUser.getMobile())
                || !Objects.equals(oldUser.getEmail(), newUser.getEmail());
    }

    private AdminUserDO getUserForUpdate(Long id) {
        AdminUserDO user = userMapper.selectByIdForUpdate(id);
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }
        return user;
    }

    private static boolean hasNicknameChanged(AdminUserDO oldUser, AdminUserDO newUser) {
        return newUser.getNickname() != null && !Objects.equals(oldUser.getNickname(), newUser.getNickname());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_UPDATE_OWN_PASSWORD_SUB_TYPE,
            bizNo = "{{#id}}",
            success = SYSTEM_USER_UPDATE_OWN_PASSWORD_SUCCESS)
    public void updateUserPassword(Long id, String oldPassword, String newPassword) {
        AdminUserDO user = passwordManager.changeOwnPassword(id, oldPassword, newPassword);
        LogRecordContext.putVariable("user", user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_UPDATE_PASSWORD_SUB_TYPE,
            bizNo = "{{#id}}",
            success = SYSTEM_USER_UPDATE_PASSWORD_SUCCESS)
    public void updateUserPassword(Long operatorUserId, Long id, String password) {
        // 重置密码等效于接管账号：目标持启用中超管角色时仅允许超管操作者执行；自操作无提权收益，放行
        getPermissionService().validatePrivilegedUserMutation(operatorUserId, Collections.singletonList(id));
        AdminUserDO user = passwordManager.resetByAdmin(id, password);
        LogRecordContext.putVariable("user", user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_UPDATE_OWN_PASSWORD_SUB_TYPE,
            bizNo = "{{#id}}",
            success = SYSTEM_USER_UPDATE_OWN_PASSWORD_SUCCESS)
    public void completePasswordChange(Long id, String newPassword) {
        AdminUserDO user = userValidator.validateUserExists(id);
        passwordManager.completeChange(user, user.getPassword(), newPassword, false);
        LogRecordContext.putVariable("user", user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_UPDATE_OWN_PASSWORD_SUB_TYPE,
            bizNo = "{{#id}}",
            success = SYSTEM_USER_UPDATE_OWN_PASSWORD_SUCCESS)
    public void completeExpiredPasswordChange(Long id, String expectedPassword, String newPassword) {
        AdminUserDO user = userValidator.validateUserExists(id);
        passwordManager.completeChange(user, expectedPassword, newPassword, true);
        LogRecordContext.putVariable("user", user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_UPDATE_STATUS_SUB_TYPE,
            bizNo = "{{#id}}",
            success = SYSTEM_USER_UPDATE_STATUS_SUCCESS)
    public void updateUserStatus(Long operatorUserId, Long id, Integer status) {
        AdminUserDO user = userValidator.validateUserExists(id);
        if (CommonStatusEnum.isDisable(status)) {
            validateAccountRemovalAllowed(operatorUserId, Collections.singletonList(id));
        } else {
            // 重新启用被处置的超管账号同样改变认证面，需同等特权校验（不涉及自操作风险）
            getPermissionService().validatePrivilegedUserMutation(operatorUserId, Collections.singletonList(id));
        }
        AdminUserDO updateObj = new AdminUserDO();
        updateObj.setId(id);
        updateObj.setStatus(status);
        userMapper.updateById(updateObj);

        if (CommonStatusEnum.isDisable(status)) {
            sessionRevocationPublisher.revokeAdminSession(id, USER_DISABLED);
        }

        LogRecordContext.putVariable("user", user);
        LogRecordContext.putVariable(
                "statusName",
                CommonStatusEnum.isEnable(status)
                        ? CommonStatusEnum.ENABLE.getName()
                        : CommonStatusEnum.DISABLE.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_DELETE_SUB_TYPE,
            bizNo = "{{#id}}",
            success = SYSTEM_USER_DELETE_SUCCESS)
    public void deleteUser(Long operatorUserId, Long id) {
        AdminUserDO user = userValidator.validateUserExists(id);
        validateAccountRemovalAllowed(operatorUserId, Collections.singletonList(id));

        deptService.processUserDeleted(id);
        userMapper.deleteById(id);
        getPermissionService().processUserDeleted(id);
        userPostMapper.deleteByUserId(id);
        sessionRevocationPublisher.revokeAdminSession(id, USER_DELETED);

        LogRecordContext.putVariable("user", user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUserList(Long operatorUserId, List<Long> ids) {
        validateUsersExist(ids);
        validateAccountRemovalAllowed(operatorUserId, ids);
        ids.forEach(deptService::processUserDeleted);
        userMapper.deleteByIds(ids);

        ids.forEach(id -> {
            getPermissionService().processUserDeleted(id);
            userPostMapper.deleteByUserId(id);
        });
        sessionRevocationPublisher.revokeAdminSessions(ids, USER_DELETED);
    }

    /**
     * 批量删除前的存在性校验：不复用 {@link #validateUserList} 的启用状态约束，
     * 已禁用账号同样允许删除清理。
     */
    private void validateUsersExist(List<Long> ids) {
        Map<Long, AdminUserDO> userMap = CollectionUtils.convertMap(userMapper.selectByIds(ids), AdminUserDO::getId);
        ids.forEach(id -> {
            if (!userMap.containsKey(id)) {
                throw exception(USER_NOT_EXISTS);
            }
        });
    }

    /**
     * 删除/禁用是账号生命周期的最高危操作：禁止操作当前登录账号，
     * 持启用中超管角色的目标仅允许超管操作者执行，避免管理面被降权清空。
     */
    private void validateAccountRemovalAllowed(Long operatorUserId, List<Long> ids) {
        if (ids.contains(operatorUserId)) {
            throw exception(USER_SELF_OPERATION_FORBIDDEN);
        }
        getPermissionService().validatePrivilegedUserMutation(operatorUserId, ids);
    }

    @Override
    public AdminUserDO getUserByUsername(String username) {
        return userMapper.selectByUsername(ValidationUtils.normalizeUsername(username));
    }

    @Override
    public AdminUserDO getUserByMobile(String mobile) {
        return userMapper.selectByMobile(ValidationUtils.normalizeMobile(mobile));
    }

    @Override
    public PageResult<AdminUserDO> getUserPage(PageParam pageParam, AdminUserQuery query, Long deptId, Long roleId) {
        Set<Long> userIds = null;
        if (roleId != null) {
            userIds = getPermissionService().getUserRoleIdListByRoleId(singleton(roleId));
            if (CollUtil.isEmpty(userIds)) {
                return PageResult.empty();
            }
        }

        return userMapper.selectPage(
                pageParam,
                new AdminUserQuery(
                        query.getUsername(),
                        query.getMobile(),
                        query.getStatus(),
                        query.getCreateTime(),
                        getDeptCondition(deptId),
                        userIds));
    }

    @Override
    public AdminUserDO getUser(Long id) {
        return userMapper.selectById(id);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    @DataPermission(enable = false)
    public AdminUserDO getUserForSession(Long id) {
        return userMapper.selectByIdForUpdate(id);
    }

    @Override
    public List<AdminUserDO> getUserListByDeptIds(Collection<Long> deptIds) {
        if (CollUtil.isEmpty(deptIds)) {
            return Collections.emptyList();
        }
        return userMapper.selectListByDeptIds(deptIds);
    }

    @Override
    public List<AdminUserDO> getUserListByPostIds(Collection<Long> postIds) {
        if (CollUtil.isEmpty(postIds)) {
            return Collections.emptyList();
        }
        Set<Long> userIds = convertSet(userPostMapper.selectListByPostIds(postIds), UserPostDO::getUserId);
        if (CollUtil.isEmpty(userIds)) {
            return Collections.emptyList();
        }
        return userMapper.selectByIds(userIds);
    }

    @Override
    public List<AdminUserDO> getUserList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return userMapper.selectByIds(ids);
    }

    @Override
    public void validateUserList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        List<AdminUserDO> users = userMapper.selectByIds(ids);
        Map<Long, AdminUserDO> userMap = CollectionUtils.convertMap(users, AdminUserDO::getId);
        ids.forEach(id -> {
            AdminUserDO user = userMap.get(id);
            if (user == null) {
                throw exception(USER_NOT_EXISTS);
            }
            if (!CommonStatusEnum.ENABLE.getStatus().equals(user.getStatus())) {
                throw exception(USER_IS_DISABLE, user.getNickname());
            }
        });
    }

    @Override
    public List<AdminUserDO> getUserListByNickname(String nickname) {
        return userMapper.selectListByNickname(ValidationUtils.normalizeNickname(nickname));
    }

    /**
     * 获得部门条件：指定部门及其子部门编号集合（含自身）；批量读取当前层级后计算子树
     */
    private Set<Long> getDeptCondition(Long deptId) {
        if (deptId == null) {
            return Collections.emptySet();
        }
        // 复制子树后加入自身，保持部门筛选语义
        Set<Long> deptIds = new HashSet<>(deptService.getChildDeptIdList(deptId));
        deptIds.add(deptId); // 包括自身
        return deptIds;
    }

    @Override
    @Transactional(rollbackFor = Exception.class) // 添加事务，异常则回滚所有导入
    public UserImportResultDTO importUserList(
            Long operatorUserId, List<UserImportDTO> importUsers, boolean isUpdateSupport) {
        return importHandler.importUserList(operatorUserId, importUsers, isUpdateSupport);
    }

    @Override
    public List<AdminUserDO> getUserListByStatus(Integer status) {
        return userMapper.selectListByStatus(status);
    }

    @Override
    public boolean isPasswordMatch(String rawPassword, String encodedPassword) {
        return passwordManager.isPasswordMatch(rawPassword, encodedPassword);
    }

    @Override
    public String upgradePasswordEncodingIfNeeded(Long id, String rawPassword, String expectedEncodedPassword) {
        return passwordManager.upgradeEncodingIfNeeded(id, rawPassword, expectedEncodedPassword);
    }

    private PermissionService getPermissionService() {
        return permissionServiceProvider.getObject();
    }

    private static void normalizeWritableContactFields(AdminUserDO user) {
        user.setNickname(ValidationUtils.normalizeNickname(user.getNickname()));
        user.setMobile(ValidationUtils.normalizeMobile(user.getMobile()));
        user.setEmail(ValidationUtils.normalizeEmail(user.getEmail()));
    }
}
