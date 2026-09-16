package com.basicframework.module.system.dal.mysql.notify;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 站内信分页查询条件
 */
@Getter
@AllArgsConstructor
public class NotifyMessageQuery {

    private final Long userId;
    private final Integer userType;
    private final String templateCode;
    private final Integer templateType;
    private final LocalDateTime[] createTime;
}
