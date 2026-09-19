package com.basicframework.module.ai.service.conversation.dto;

import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationDO;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationMessageDO;
import com.basicframework.module.ai.domain.runtime.AiRunSnapshot;
import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 会话运行上下文（O01）：运行入口（O02）在**重新鉴权之后**取用的会话历史与固定版本。
 *
 * <p>{@link #pin} 是会话固定的发布版本固定值；{@link #history} 是最近的历史消息（正文只在服务层使用，
 * 不进日志与 {@code toString()}）。调用方拿到它只代表"此刻仍有权"，每一步受限操作仍需按当前授权判定。
 */
@Data
@Accessors(chain = true)
@ToString(exclude = {"history", "businessContext"})
public class AiConversationRunContextDTO {

    /** 会话 */
    private AiConversationDO conversation;

    /** 会话固定的发布版本固定值（未绑定时为空） */
    private AiRunSnapshot pin;

    /** 业务上下文（已注册字段的 JSON 对象文本） */
    private String businessContext;

    /** 最近的历史消息（升序） */
    private List<AiConversationMessageDO> history;
}
