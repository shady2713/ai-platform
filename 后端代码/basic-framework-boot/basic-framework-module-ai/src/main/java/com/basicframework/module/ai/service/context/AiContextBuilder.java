package com.basicframework.module.ai.service.context;

import com.basicframework.module.ai.service.context.dto.AiContextBuildDTO;
import com.basicframework.module.ai.service.context.dto.AiContextResultDTO;

/**
 * 上下文构造器（S04）：把不可信输入拼装成分区化的提示词，并在**固定规则**下限制输入预算。
 *
 * <p>约束：
 * <ul>
 *   <li>平台政策分区由平台写入且不可覆盖；不可信内容里的分区标记一律中和，防止冒充；</li>
 *   <li>业务上下文只接受已注册字段（page/objectType/objectId/filters/locale/timezone），
 *       未知字段直接拒绝，而不是静默丢弃；</li>
 *   <li>预算规则固定：强制分区（政策/系统指令/业务上下文/本次消息）必须完整容纳，
 *       容不下就返回可解释的稳定错误；可选分区（知识片段/历史消息）按"知识按调用方顺序保留、
 *       历史保留最新"裁剪，裁剪事实随结果返回。</li>
 * </ul>
 */
public interface AiContextBuilder {

    /** 按固定分区与预算规则拼装上下文。 */
    AiContextResultDTO build(AiContextBuildDTO request);
}
