package com.basicframework.module.system.service.user;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_IMPORT_LIST_IS_EMPTY;
import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_NOT_EXISTS;
import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_USERNAME_EXISTS;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.common.util.validation.ValidationUtils;
import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.user.AdminUserMapper;
import com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum;
import com.basicframework.module.system.event.session.UserSessionRevocationPublisher;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.permission.PermissionService;
import com.basicframework.module.system.service.user.dto.UserImportDTO;
import com.basicframework.module.system.service.user.dto.UserImportResultDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Excel 批量导入用户的处理器。
 *
 * 导入的新用户为禁用状态 + CSPRNG 随机初始密码（明文不落库），
 * 需管理员重置密码并启用后激活；已存在的用户按 isUpdateSupport 决定更新或失败。
 */
@Component
@RequiredArgsConstructor
public class UserImportHandler {

    /**
     * 导入用户的初始密码随机源：CSPRNG
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 导入用户的初始密码熵长度：16 字节（128 bit），编码为 32 位小写十六进制字符串
     */
    private static final int IMPORT_PASSWORD_BYTES = 16;

    private final AdminUserMapper userMapper;

    private final AdminUserUniqueValidator userValidator;

    private final DeptService deptService;

    private final PasswordEncoder passwordEncoder;

    private final Validator validator;

    private final UserSessionRevocationPublisher sessionRevocationPublisher;

    // 延迟解析以打破 PermissionService -> AdminUserService -> UserImportHandler 的构造环
    private final ObjectProvider<PermissionService> permissionServiceProvider;

    /**
     * 批量导入用户，逐行处理并在事务回滚语义下收集每行的成功/失败结果。
     *
     * @param operatorUserId 操作者用户编号：更新命中启用中超管角色的账号时，仅允许超管操作者
     * @param importUsers     待导入的用户列表
     * @param isUpdateSupport 用户名已存在时是否更新
     * @return 导入结果
     */
    public UserImportResultDTO importUserList(
            Long operatorUserId, List<UserImportDTO> importUsers, boolean isUpdateSupport) {
        if (CollUtil.isEmpty(importUsers)) {
            throw exception(USER_IMPORT_LIST_IS_EMPTY);
        }
        ImportSession session =
                new ImportSession(new ArrayList<>(), new ArrayList<>(), new LinkedHashMap<>(), new AtomicInteger(1));
        importUsers.forEach(importUser -> processImportRow(session, operatorUserId, importUser, isUpdateSupport));
        return new UserImportResultDTO(session.created(), session.updated(), session.failures());
    }

    private void processImportRow(
            ImportSession session, Long operatorUserId, UserImportDTO importUser, boolean isUpdateSupport) {
        int rowNumber = session.index().getAndIncrement();
        normalizeImportRow(importUser);
        String failure = validateImportRow(importUser);
        if (failure != null) {
            session.failures().put(StrUtil.blankToDefault(importUser.getUsername(), "第 " + rowNumber + " 行"), failure);
            return;
        }
        Long deptId = resolveDeptId(session, importUser);
        if (deptId == null && StrUtil.isNotBlank(importUser.getDeptName())) {
            return;
        }
        AdminUserDO existingUser = userMapper.selectByUsername(importUser.getUsername());
        if (existingUser != null && !isUpdateSupport) {
            session.failures().put(importUser.getUsername(), USER_USERNAME_EXISTS.getMsg());
            return;
        }
        try {
            userValidator.validateForCreateOrUpdate(
                    existingUser != null ? existingUser.getId() : null,
                    null,
                    importUser.getMobile(),
                    importUser.getEmail(),
                    deptId,
                    null);
        } catch (ServiceException ex) {
            session.failures().put(importUser.getUsername(), ex.getPublicMessage());
            return;
        }
        saveImportRow(session, operatorUserId, importUser, deptId, existingUser);
    }

    private void saveImportRow(
            ImportSession session, Long operatorUserId, UserImportDTO importUser, Long deptId, AdminUserDO existUser) {
        if (existUser == null) {
            createImportUser(session, importUser, deptId);
            return;
        }
        // 更新既有账号可能改写手机号等身份证明字段：命中超管账号时要求超管操作者，按行计失败而非中断整批
        try {
            permissionServiceProvider
                    .getObject()
                    .validatePrivilegedUserMutation(operatorUserId, Collections.singletonList(existUser.getId()));
        } catch (ServiceException ex) {
            session.failures().put(importUser.getUsername(), ex.getPublicMessage());
            return;
        }
        AdminUserDO updateUser = BeanUtils.toBean(importUser, AdminUserDO.class);
        updateUser.setId(existUser.getId());
        // 状态列只能经 update-status 专用链路变更，导入忽略 Excel 中的账号状态
        updateUser.setStatus(null);
        // 导入是稀疏更新：空部门及联系信息保留旧值，解绑由完整表单接口承担。
        updateUser.setDeptId(deptId);
        if (userMapper.updateById(updateUser) != 1) {
            throw exception(USER_NOT_EXISTS);
        }
        sessionRevocationPublisher.revokeAdminSession(
                existUser.getId(), UserSessionRevocationReasonEnum.USER_INFO_CHANGED);
        session.updated().add(importUser.getUsername());
    }

    /**
     * 新用户强制禁用状态 + 随机密码（忽略 Excel 中的账号状态列），
     * 由管理员通过“重置密码”功能分配新密码并启用后，账号才可登录
     */
    private void createImportUser(ImportSession session, UserImportDTO importUser, Long deptId) {
        userMapper.insert(BeanUtils.toBean(importUser, AdminUserDO.class)
                .setDeptId(deptId)
                .setPassword(passwordEncoder.encode(generateImportPassword()))
                .setMustChangePassword(Boolean.TRUE)
                .setStatus(CommonStatusEnum.DISABLE.getStatus())
                .setPostIds(new HashSet<>())); // 空岗位编号数组
        session.created().add(importUser.getUsername());
    }

    private void normalizeImportRow(UserImportDTO importUser) {
        importUser.setUsername(ValidationUtils.normalizeUsername(importUser.getUsername()));
        importUser.setNickname(ValidationUtils.normalizeNickname(importUser.getNickname()));
        importUser.setMobile(ValidationUtils.normalizeMobile(importUser.getMobile()));
        importUser.setEmail(ValidationUtils.normalizeEmail(importUser.getEmail()));
    }

    private String validateImportRow(UserImportDTO importUser) {
        try {
            ValidationUtils.validate(validator, importUser);
            return null;
        } catch (ConstraintViolationException ex) {
            return firstConstraintViolationMessage(ex);
        }
    }

    /**
     * 按部门名称解析部门编号；名称不为空但部门不存在时记录失败并返回 null
     */
    private Long resolveDeptId(ImportSession session, UserImportDTO importUser) {
        if (StrUtil.isBlank(importUser.getDeptName())) {
            return null;
        }
        DeptDO dept = deptService.getDeptByName(importUser.getDeptName());
        if (dept == null) {
            session.failures().put(importUser.getUsername(), "部门名称不存在");
            return null;
        }
        return dept.getId();
    }

    static String firstConstraintViolationMessage(ConstraintViolationException exception) {
        return exception.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse("用户信息校验失败");
    }

    /**
     * 使用 CSPRNG 生成导入用户的初始密码：16 字节（128 bit）随机数，
     * 编码为 32 位小写十六进制字符串。生成方式与用户会话令牌一致。
     *
     * @return 32 位小写十六进制随机密码
     */
    private static String generateImportPassword() {
        byte[] bytes = new byte[IMPORT_PASSWORD_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return HexUtil.encodeHexStr(bytes);
    }

    /**
     * 单次导入的行间累积结果；仅存活于一次 importUserList 调用，不可跨调用复用。
     */
    private record ImportSession(
            List<String> created, List<String> updated, Map<String, String> failures, AtomicInteger index) {}
}
