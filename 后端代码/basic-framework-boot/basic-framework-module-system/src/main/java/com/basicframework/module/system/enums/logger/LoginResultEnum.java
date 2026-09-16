package com.basicframework.module.system.enums.logger;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 登录结果的枚举类
 */
@Getter
@AllArgsConstructor
public enum LoginResultEnum {
    SUCCESS(0), // 成功
    BAD_CREDENTIALS(10), // 账号或密码不正确
    USER_DISABLED(20), // 用户被禁用
    CAPTCHA_NOT_FOUND(30), // 图片验证码不存在
    CAPTCHA_CODE_ERROR(31), // 图片验证码不正确
    ACCOUNT_LOCKED(50), // 账号登录失败次数过多，已临时锁定
    PASSWORD_EXPIRED(60), // 密码已过期，须先修改密码
    ;

    /**
     * 结果
     */
    private final Integer result;
}
