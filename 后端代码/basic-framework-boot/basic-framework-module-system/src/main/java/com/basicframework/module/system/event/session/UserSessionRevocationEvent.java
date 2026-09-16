package com.basicframework.module.system.event.session;

import com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum;
import java.util.Set;

/** 用户会话撤销事件。 */
public record UserSessionRevocationEvent(Set<Long> userIds, Integer userType, UserSessionRevocationReasonEnum reason) {}
