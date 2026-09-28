package com.basicframework.module.ai.service.media.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 媒体任务租约（X03）：worker 标识 + 领取代数构成栅栏，续租与落终态都必须带上。 */
@Data
@Accessors(chain = true)
public class AiMediaTaskLeaseDTO {

    /** 任务编号 */
    private Long taskId;

    /** 租约持有者（worker 标识） */
    private String owner;

    /** 领取代数（每次领取 +1） */
    private Integer epoch;

    /** 本次尝试序号（第几次尝试） */
    private Integer attempt;
}
