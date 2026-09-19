package com.basicframework.module.ai.service.conversation.dto;

import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 会话创建请求（O01）：归属来自服务端身份，请求体只能给出业务键与标题。 */
@Data
@Accessors(chain = true)
@ToString(exclude = {"businessContext"})
public class AiConversationCreateDTO {

    /** 会话业务键（conv_ 前缀；同一应用+主体内唯一） */
    private String conversationKey;

    /** 会话标题 */
    private String title;

    /** 绑定的服务编号（可空：允许先建会话后绑定） */
    private Long serviceId;

    /** 业务上下文（已注册字段的 JSON 对象文本；不参与权限判定） */
    private String businessContext;
}
