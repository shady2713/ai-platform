package com.basicframework.module.ai.service.semantic.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 新草稿映射版本的请求（Y02）。
 *
 * <p>版本有效期是**版本级**判定条件：判定时刻不落在 {@code [validFrom, validTo)} 内即阻断，
 * 不回退到最新版本。一个对象同时只允许一个未发布草稿（避免"条目加到哪个草稿"的歧义）。
 */
@Data
@Accessors(chain = true)
public class AiMasterRevisionDraftDTO {

    /** 统一对象编号 */
    private Long masterObjectId;

    /** 版本有效期起点（含） */
    private LocalDateTime validFrom;

    /** 版本有效期终点（不含；为空表示长期有效） */
    private LocalDateTime validTo;
}
