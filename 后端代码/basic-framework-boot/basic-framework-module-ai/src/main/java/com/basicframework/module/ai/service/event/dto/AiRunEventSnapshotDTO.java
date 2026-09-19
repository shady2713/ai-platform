package com.basicframework.module.ai.service.event.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 运行快照（O05）：重放窗口过期时的**明确出口**——返回运行当前状态与最新序号，
 * 引导调用方按快照继续，而不是用一个新 POST 偷偷重跑（那会产生第二个运行）。
 */
@Data
@Accessors(chain = true)
public class AiRunEventSnapshotDTO {

    /** 运行编号 */
    private Long runId;

    /** 运行业务键 */
    private String runKey;

    /** 运行当前状态 */
    private String status;

    /** 运行内最新事件序号（0 表示还没有事件） */
    private Integer latestSeq;

    /** 仍可重放的最早序号（0 表示还没有事件） */
    private Integer earliestSeq;
}
