package com.basicframework.module.system.enums.session;

/** 用户会话撤销原因。 */
public enum UserSessionRevocationReasonEnum {
    USER_DISABLED,
    USER_DELETED,
    USER_INFO_CHANGED,
    PASSWORD_CHANGED,
    USER_ROLE_CHANGED,
    ROLE_PERMISSION_CHANGED
}
