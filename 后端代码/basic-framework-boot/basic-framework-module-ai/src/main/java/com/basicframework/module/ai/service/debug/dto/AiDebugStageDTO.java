package com.basicframework.module.ai.service.debug.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 调试阶段摘要（S04）：只记录"做过哪一步、结果如何、耗时多少"。
 *
 * <p>不记录隐藏推理，也不记录任何分区正文；{@code detail} 只允许稳定词（例如版本号、分区名、
 * 稳定原因码），不允许携带提示词或响应正文。
 */
@Data
@Accessors(chain = true)
public class AiDebugStageDTO {

    /** 阶段：RESOLVE/AUTHORIZE/CONTEXT/MODEL */
    private String stage;

    /** 结果：OK/FAILED */
    private String status;

    /** 稳定说明（不含正文） */
    private String detail;

    /** 该阶段耗时（毫秒） */
    private Long durationMs;
}
