package com.basicframework.module.system.service.user;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_PASSWORD_SAME_AS_OLD;
import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_NOT_EXISTS;
import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_PASSWORD_FAILED;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.user.AdminUserMapper;
import com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum;
import com.basicframework.module.system.event.session.UserSessionRevocationPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 用户密码核心操作：校验旧密码、条件化改密与哈希编码升级。
 *
 * 事务边界与操作日志由调用方（{@link AdminUserServiceImpl}）持有；
 * 会话撤销语义：任何成功的密码变更都撤销该用户的既有会话。
 */
@Component
@RequiredArgsConstructor
public class AdminUserPasswordManager {

    private final AdminUserMapper userMapper;

    private final PasswordEncoder passwordEncoder;

    private final PasswordPolicy passwordPolicy;

    private final UserSessionRevocationPublisher sessionRevocationPublisher;

    /**
     * 自助改密：校验旧密码通过后写入新密码，并清除“已知口令”标记。
     */
    public AdminUserDO changeOwnPassword(Long id, String oldPassword, String newPassword) {
        AdminUserDO user = validateOldPassword(id, oldPassword);
        if (isPasswordMatch(newPassword, user.getPassword())) {
            throw exception(AUTH_PASSWORD_SAME_AS_OLD);
        }
        return completeChange(user, user.getPassword(), newPassword, false);
    }

    /**
     * 管理员重置密码：新口令属于“已知口令”，首次登录必须修改。
     */
    public AdminUserDO resetByAdmin(Long id, String password) {
        AdminUserDO user = userMapper.selectById(id);
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }
        passwordPolicy.validate(password, user.getUsername());
        AdminUserDO updateObj = new AdminUserDO();
        updateObj.setId(id);
        updateObj.setPassword(encode(password));
        updateObj.setMustChangePassword(Boolean.TRUE);
        if (userMapper.updateById(updateObj) != 1) {
            throw exception(USER_NOT_EXISTS);
        }
        sessionRevocationPublisher.revokeAdminSession(id, UserSessionRevocationReasonEnum.PASSWORD_CHANGED);
        return user;
    }

    /**
     * 完成改密：要求账号启用、新密码过策略，且数据库当前哈希仍等于认证时读到的值。
     */
    public AdminUserDO completeChange(
            AdminUserDO user, String expectedPassword, String newPassword, boolean requireExpired) {
        if (!CommonStatusEnum.ENABLE.getStatus().equals(user.getStatus())) {
            throw exception(USER_PASSWORD_FAILED);
        }
        passwordPolicy.validate(newPassword, user.getUsername());
        if (userMapper.completePasswordChange(user.getId(), expectedPassword, encode(newPassword), requireExpired)
                != 1) {
            throw exception(USER_PASSWORD_FAILED);
        }
        sessionRevocationPublisher.revokeAdminSession(user.getId(), UserSessionRevocationReasonEnum.PASSWORD_CHANGED);
        return user;
    }

    /**
     * 校验旧密码，用户不存在或旧密码不匹配时抛出统一失败码，避免账号枚举。
     */
    AdminUserDO validateOldPassword(Long id, String oldPassword) {
        AdminUserDO user = userMapper.selectById(id);
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }
        if (!isPasswordMatch(oldPassword, user.getPassword())) {
            throw exception(USER_PASSWORD_FAILED);
        }
        return user;
    }

    public boolean isPasswordMatch(String rawPassword, String encodedPassword) {
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }

    /**
     * 哈希编码升级：仅当编码强度需要升级时，以旧哈希未被并发修改为条件写入升级后的哈希。
     */
    public String upgradeEncodingIfNeeded(Long id, String rawPassword, String expectedEncodedPassword) {
        if (!passwordEncoder.upgradeEncoding(expectedEncodedPassword)) {
            return expectedEncodedPassword;
        }
        String upgradedPassword = encode(rawPassword);
        if (userMapper.updatePasswordIfUnchanged(id, expectedEncodedPassword, upgradedPassword) != 1) {
            throw exception(USER_PASSWORD_FAILED);
        }
        return upgradedPassword;
    }

    /**
     * BCrypt 编码新口令；仅限用户创建/改密链路复用。
     */
    public String encode(String password) {
        return passwordEncoder.encode(password);
    }
}
