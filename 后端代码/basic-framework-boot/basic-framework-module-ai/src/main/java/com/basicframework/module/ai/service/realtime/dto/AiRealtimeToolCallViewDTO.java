package com.basicframework.module.ai.service.realtime.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 会话内工具调用视图（服务层 DTO）：状态与稳定结论，不含参数正文与上游报文。
 *
 * <p>重连后客户端按本视图恢复"哪些调用已执行、哪些被拒绝"，并据此决定是否重发**执行请求**
 * （执行请求本身幂等：已终态的调用不会被第二次执行）。
 */
@Data
@Accessors(chain = true)
public class AiRealtimeToolCallViewDTO {

    /** 工具调用编号（执行入口用它，不用上游调用标识） */
    private Long id;

    /** 提出该调用的回合 */
    private Long turnNo;

    /** 上游工具调用标识 */
    private String callId;

    /** 工具标识 */
    private String toolCode;

    /** 状态（PROPOSED/EXECUTING/EXECUTED/REJECTED/FAILED） */
    private String status;

    /** 结论稳定码（不含上游正文） */
    private String resultCode;

    /** 执行完成时间 */
    private LocalDateTime executedTime;
}
