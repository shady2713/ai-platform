package com.basicframework.framework.ai.core.model;

/**
 * 模型能力类型：AI 能力接缝对业务侧发布的稳定词汇。
 *
 * <p>端点配置声明"需要哪些能力"，提供方实现通过
 * {@link ModelPort#capabilities()} 声明"覆盖哪些能力"，装配校验在启动期比较两者，
 * 避免能力缺失在首次请求时才暴露。调用时还会按请求类型再次检查端点能力：
 * 缺失时给出 {@link ModelException.Reason#CAPABILITY_UNSUPPORTED}，并在消息中指明缺失的能力名。
 *
 * <p>声明不等于可用：M04 的能力探测用真实调用确认能力是否真的可用，
 * 声明集合与探测确认集合的交集才构成可发布范围。
 *
 * <p>取值稳定：已发布的值不得改语义；后续多模态能力（图片、语音）在 X 系列任务中按同一枚举扩展，
 * 不新增平行词汇。
 */
public enum ModelCapability {

    /** 文本生成（一次性返回完整结果）。 */
    TEXT,

    /** 文本流式生成（M03：逐增量返回，可取消）。 */
    TEXT_STREAM,

    /** 结构化输出（M03：返回单个 JSON 对象，由平台校验后交付业务）。 */
    STRUCTURED_OUTPUT,

    /** 工具调用（M04：模型返回工具调用请求；平台只暴露数据，不自动执行）。 */
    TOOL_CALLING,

    /** 文本嵌入（M04：批量嵌入，带维度校验）。 */
    EMBEDDING
}
