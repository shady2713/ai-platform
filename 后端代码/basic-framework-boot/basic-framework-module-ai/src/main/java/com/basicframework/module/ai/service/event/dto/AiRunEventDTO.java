package com.basicframework.module.ai.service.event.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 运行事件（O05）：与 `docs/contracts/ai/run-event.schema.json`（RunEvent v1）逐字段对应。
 *
 * <p>{@code blockJson} 是受控结果块正文；事件不携带提示词、模型输入正文与凭据。
 */
@Data
@Accessors(chain = true)
@ToString(exclude = {"blockJson"})
public class AiRunEventDTO {

    /** 事件契约版本（固定 1.0） */
    private String schemaVersion;

    /** 运行内事件序号（从 1 递增） */
    private Integer seq;

    /** 运行业务键（run_ 前缀） */
    private String runId;

    /** 事件状态 */
    private String status;

    /** 结果块类型（受控结构；无块时为空） */
    private String blockType;

    /** 结果块正文 */
    private String blockJson;

    /** 事件时间 */
    private LocalDateTime createdAt;
}
