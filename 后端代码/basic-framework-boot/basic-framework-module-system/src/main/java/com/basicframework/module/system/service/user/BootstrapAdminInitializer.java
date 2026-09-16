package com.basicframework.module.system.service.user;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.module.system.config.BootstrapAdminProperties;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.user.AdminUserMapper;
import com.basicframework.module.system.service.session.UserSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 只激活被迁移明确封存的种子账号；重启不会覆盖已经激活或自行改密的账号。 */
@Component
@RequiredArgsConstructor
public class BootstrapAdminInitializer implements ApplicationRunner {
    private static final long SEED_USER_ID = 1L;
    private static final String PENDING_PASSWORD = "!bootstrap-required";

    private final BootstrapAdminProperties properties;
    private final AdminUserMapper userMapper;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final UserSessionService sessionService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments arguments) {
        AdminUserDO user = userMapper.selectById(SEED_USER_ID);
        if (user == null || !PENDING_PASSWORD.equals(user.getPassword())) {
            return;
        }
        sessionService.removeSessionsByUser(SEED_USER_ID, UserTypeEnum.ADMIN.getValue());
        String password = properties.getPassword();
        if (!StringUtils.hasText(password)) {
            return;
        }
        passwordPolicy.validate(password, user.getUsername());
        userMapper.update(
                new AdminUserDO()
                        .setPassword(passwordEncoder.encode(password))
                        .setStatus(CommonStatusEnum.ENABLE.getStatus())
                        .setMustChangePassword(true),
                new LambdaUpdateWrapper<AdminUserDO>()
                        .eq(AdminUserDO::getId, SEED_USER_ID)
                        .eq(AdminUserDO::getPassword, PENDING_PASSWORD)
                        .eq(AdminUserDO::getStatus, CommonStatusEnum.DISABLE.getStatus()));
    }
}
