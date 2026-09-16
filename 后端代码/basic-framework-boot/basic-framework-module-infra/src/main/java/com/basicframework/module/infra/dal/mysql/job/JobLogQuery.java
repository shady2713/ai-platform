package com.basicframework.module.infra.dal.mysql.job;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任务日志分页查询条件
 */
@Getter
@AllArgsConstructor
public class JobLogQuery {

    private final Long jobId;
    private final String handlerName;
    private final LocalDateTime beginTime;
    private final LocalDateTime endTime;
    private final Integer status;
}
