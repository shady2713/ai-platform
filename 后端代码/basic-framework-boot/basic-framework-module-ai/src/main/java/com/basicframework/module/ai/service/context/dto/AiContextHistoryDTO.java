package com.basicframework.module.ai.service.context.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 历史消息输入（S04）：越新越靠后；超预算时按"保留最新"的固定规则裁剪。 */
@Data
@Accessors(chain = true)
public class AiContextHistoryDTO {

    /** 角色（user/assistant，稳定小写词表） */
    private String role;

    /** 消息正文 */
    private String content;
}
