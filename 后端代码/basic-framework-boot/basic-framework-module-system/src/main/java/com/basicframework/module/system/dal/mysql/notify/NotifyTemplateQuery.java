package com.basicframework.module.system.dal.mysql.notify;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通知模板分页查询条件
 */
@Getter
@AllArgsConstructor
public class NotifyTemplateQuery {

    private final String code;
    private final String name;
    private final Integer type;
    private final Integer status;
    private final LocalDateTime[] createTime;
}
