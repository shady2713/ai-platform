package com.basicframework.module.ai.service.quota.dto;

import java.time.Duration;
import lombok.Data;
import lombok.experimental.Accessors;

/** 配额占位申请（服务层 DTO）。 */
@Data
@Accessors(chain = true)
public class AiQuotaAcquireDTO {

    /** 应用编号（并发限额的维度） */
    private Long applicationId;

    /** 持有者（进程/实例标识，便于排查残留占位） */
    private String holderRef;

    /** 调用标识（与账本同口径；重复申请不叠加） */
    private String invocationId;

    /** 租约时长（到期未续租即视为可回收） */
    private Duration lease;

    /** 并发上限 */
    private Integer limit;

    /** 服务编号（可空：按应用限额） */
    private Long serviceId;
}
