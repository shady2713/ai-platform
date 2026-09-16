package com.basicframework.module.system.event.session;

import com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum;

/** 会话删除的数据库结果；只有提交成功后才能记入撤销指标。 */
public record SessionRevocationCompletedEvent(UserSessionRevocationReasonEnum reason, int deletedCount) {}
