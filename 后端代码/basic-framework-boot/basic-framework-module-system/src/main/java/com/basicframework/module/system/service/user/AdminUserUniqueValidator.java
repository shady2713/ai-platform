package com.basicframework.module.system.service.user;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_EMAIL_EXISTS;
import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_MOBILE_EXISTS;
import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_NOT_EXISTS;
import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_USERNAME_EXISTS;

import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.common.util.collection.CollectionUtils;
import com.basicframework.framework.common.util.validation.ValidationUtils;
import com.basicframework.framework.datapermission.core.util.DataPermissionUtils;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.user.AdminUserMapper;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.dept.PostService;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 用户唯一性与存在性校验，供创建、更新与导入流程共用。
 *
 * 唯一性查询在忽略数据权限的上下文中执行，避免因无数据权限查不到数据导致误判。
 */
@Component
public class AdminUserUniqueValidator {

    private final AdminUserMapper userMapper;

    private final DeptService deptService;

    private final PostService postService;

    public AdminUserUniqueValidator(AdminUserMapper userMapper, DeptService deptService, PostService postService) {
        this.userMapper = userMapper;
        this.deptService = deptService;
        this.postService = postService;
    }

    /**
     * 校验用户在创建/更新场景下的合法性，返回已存在的用户（新建时为 null）。
     *
     * @param id       用户编号，新建为 null
     * @param username 用户名（已规范化）
     * @param mobile   手机号
     * @param email    邮箱
     * @param deptId   部门编号
     * @param postIds  岗位编号集合
     * @return 更新场景下的既有用户，新建返回 null
     */
    public AdminUserDO validateForCreateOrUpdate(
            Long id, String username, String mobile, String email, Long deptId, Set<Long> postIds) {
        // 关闭数据权限，避免因为没有数据权限，查询不到数据，进而导致唯一校验不正确
        return DataPermissionUtils.executeIgnore(() -> {
            AdminUserDO user = validateUserExists(id);
            validateUsernameUnique(id, username);
            validateMobileUnique(id, mobile);
            validateEmailUnique(id, email);
            deptService.validateDeptList(CollectionUtils.singleton(deptId));
            postService.validatePostList(postIds);
            return user;
        });
    }

    AdminUserDO validateUserExists(Long id) {
        if (id == null) {
            return null;
        }
        AdminUserDO user = userMapper.selectById(id);
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }
        return user;
    }

    void validateUsernameUnique(Long id, String username) {
        String normalizedUsername = ValidationUtils.normalizeUsername(username);
        if (StrUtil.isBlank(normalizedUsername)) {
            return;
        }
        AdminUserDO user = userMapper.selectByUsername(normalizedUsername);
        if (user == null || user.getId().equals(id)) {
            return;
        }
        throw exception(USER_USERNAME_EXISTS);
    }

    void validateEmailUnique(Long id, String email) {
        String normalizedEmail = ValidationUtils.normalizeEmail(email);
        if (StrUtil.isBlank(normalizedEmail)) {
            return;
        }
        AdminUserDO user = userMapper.selectByEmail(normalizedEmail);
        if (user == null || user.getId().equals(id)) {
            return;
        }
        throw exception(USER_EMAIL_EXISTS);
    }

    void validateMobileUnique(Long id, String mobile) {
        String normalizedMobile = ValidationUtils.normalizeMobile(mobile);
        if (StrUtil.isBlank(normalizedMobile)) {
            return;
        }
        AdminUserDO user = userMapper.selectByMobile(normalizedMobile);
        if (user == null || user.getId().equals(id)) {
            return;
        }
        throw exception(USER_MOBILE_EXISTS);
    }
}
