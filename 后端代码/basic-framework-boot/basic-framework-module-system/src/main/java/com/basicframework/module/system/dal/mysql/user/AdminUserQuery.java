package com.basicframework.module.system.dal.mysql.user;

import java.time.LocalDateTime;
import java.util.Collection;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 管理后台用户分页查询条件
 */
@Getter
@AllArgsConstructor
public class AdminUserQuery {

    private final String username;
    private final String mobile;
    private final Integer status;
    private final LocalDateTime[] createTime;
    private final Collection<Long> deptIds;
    private final Collection<Long> userIds;
}
