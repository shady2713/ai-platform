package com.basicframework.module.ai.service.context.dto;

import com.basicframework.module.ai.domain.runtime.AiContextBudget;
import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 上下文构建请求（S04）。
 *
 * <p>输入全部来自不可信来源（宿主上下文、检索结果、会话历史、当前消息），
 * 因此正文不进入 {@code toString()}：日志与异常里只能看到预算等结构字段。
 */
@Data
@Accessors(chain = true)
@ToString(exclude = {"systemPrompt", "businessContext", "knowledge", "history", "userMessage"})
public class AiContextBuildDTO {

    /** 服务系统指令（发布版本冻结的提示词模板） */
    private String systemPrompt;

    /** 业务上下文（已注册 schema 的 JSON 对象文本） */
    private String businessContext;

    /** 知识片段（按相关度排序） */
    private List<AiContextKnowledgeDTO> knowledge;

    /** 历史消息（越新越靠后） */
    private List<AiContextHistoryDTO> history;

    /** 本次消息 */
    private String userMessage;

    /** 输入预算；为空时使用平台默认值 */
    private AiContextBudget budget;
}
