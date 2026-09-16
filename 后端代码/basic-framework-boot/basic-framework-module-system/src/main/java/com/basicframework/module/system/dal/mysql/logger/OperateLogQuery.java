package com.basicframework.module.system.dal.mysql.logger;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 操作日志分页查询条件
 */
@Getter
@AllArgsConstructor
public class OperateLogQuery {

    private final Long userId;
    private final Long bizId;
    private final String type;
    private final String subType;
    private final String action;
    private final LocalDateTime[] createTime;
}
