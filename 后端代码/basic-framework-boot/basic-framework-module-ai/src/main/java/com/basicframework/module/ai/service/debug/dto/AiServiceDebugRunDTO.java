package com.basicframework.module.ai.service.debug.dto;

import com.basicframework.module.ai.domain.runtime.AiContextBudget;
import com.basicframework.module.ai.service.context.dto.AiContextBuildDTO;
import com.basicframework.module.ai.service.context.dto.AiContextHistoryDTO;
import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 调试运行请求（S04）。
 *
 * <p>调试必须携带**显式测试主体**：平台按该主体的当前授权逐条判定发布版本绑定的资源动作，
 * 因此调试不会比测试主体看得更多；不存在"用管理员身份调试"的隐式路径。
 *
 * <p>请求正文（消息、历史、业务上下文）不进 {@code toString()}。
 */
@Data
@Accessors(chain = true)
@ToString(exclude = {"userMessage", "history", "businessContext", "maxMessages", "maxTokens"})
public class AiServiceDebugRunDTO {

    /** 服务编号 */
    private Long serviceId;

    /** 测试主体类型（USER/APP） */
    private String testSubjectType;

    /** 测试主体标识（USER 为可信外部用户标识；APP 为空） */
    private String testSubjectId;

    /** 本次消息 */
    private String userMessage;

    /** 历史消息（越新越靠后） */
    private List<AiContextHistoryDTO> history;

    /** 业务上下文（已注册 schema 的 JSON 对象文本） */
    private String businessContext;

    /** 调试输入的数据分级（L1–L4）：由发起调试的管理员显式声明，端点外发上限由平台策略强制 */
    private String dataLevel;

    /** 模型调用超时（毫秒，缺省 30000，上限 120000） */
    private Integer timeoutMillis;

    /** 输入预算上限（缺省使用平台默认值） */
    private Integer maxMessages;

    /** 输入 token 预算上限（缺省使用平台默认值） */
    private Integer maxTokens;

    /** 构造上下文构建请求（系统指令来自发布版本冻结的提示词，不由调用方提供）。 */
    public AiContextBuildDTO toContextRequest(String systemPrompt, AiContextBudget budget) {
        return new AiContextBuildDTO()
                .setSystemPrompt(systemPrompt)
                .setBusinessContext(businessContext)
                .setHistory(history)
                .setUserMessage(userMessage)
                .setBudget(budget);
    }
}
