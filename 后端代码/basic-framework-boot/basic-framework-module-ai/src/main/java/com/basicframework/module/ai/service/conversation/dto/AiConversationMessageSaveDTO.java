package com.basicframework.module.ai.service.conversation.dto;

import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 会话消息写入请求（O01）：正文属于受控业务数据，不进日志。 */
@Data
@Accessors(chain = true)
@ToString(exclude = {"content"})
public class AiConversationMessageSaveDTO {

    /** 会话编号 */
    private Long conversationId;

    /** 角色（user/assistant/system） */
    private String role;

    /** 消息正文 */
    private String content;

    /** 产生该消息的运行编号（O02 起使用，可空） */
    private Long sourceRunId;
}
