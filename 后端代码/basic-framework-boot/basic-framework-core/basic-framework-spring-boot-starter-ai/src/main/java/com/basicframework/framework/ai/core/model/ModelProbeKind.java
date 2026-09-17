package com.basicframework.framework.ai.core.model;

/**
 * 能力探测类型（M04）：管理员在模型管理页对端点做**真实调用**探测，而不是只看配置声明。
 *
 * <p>探测结果把"模型声明"与"适配结果"分开记录，两者共同决定可发布范围：
 * 端点声明的能力与探测确认的能力取交集，才不会把"配了但用不了"的能力发布给业务。
 */
public enum ModelProbeKind {

    /** 连接可达：一次最小生成调用能到达上游并返回（不校验内容）。 */
    CONNECTIVITY,

    /** 文本生成：最小调用返回非空文本。 */
    TEXT,

    /** 文本流式生成：最小流式调用能返回增量并正常结束。 */
    TEXT_STREAM,

    /** 结构化输出：按给定 Schema 返回单个 JSON 对象。 */
    STRUCTURED_OUTPUT,

    /** 工具调用：模型返回工具调用请求；探测**不执行**任何工具。 */
    TOOL_CALLING,

    /** 文本嵌入：批量嵌入返回向量且维度一致。 */
    EMBEDDING
}
