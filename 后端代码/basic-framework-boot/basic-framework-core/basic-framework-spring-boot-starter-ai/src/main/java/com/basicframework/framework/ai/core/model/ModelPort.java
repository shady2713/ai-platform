package com.basicframework.framework.ai.core.model;

import java.util.Set;

/**
 * 模型能力端口：业务模块与模型提供方之间的稳定契约。
 *
 * <p>实现只能来自 {@code com.basicframework.framework.ai.provider.springai} 包：
 * 该包是仓库内唯一允许直接引用 Spring AI 类型的区域（架构说明 03 的上游组件选择），
 * 其他包引用厂商类型会被模块边界门禁的 vendor 规则拒绝。
 *
 * <p>接口按任务扩展但保持向后兼容，不复制第二套并行契约：M02 增加一次性调用，
 * M03 增加文本流与结构化输出，M04 增加批量嵌入与能力探测；未覆盖的能力在默认实现里
 * 按"能力缺失"拒绝并指明能力名，而不是静默降级。
 */
public interface ModelPort {

    /**
     * 该实现覆盖的模型能力集合；必须非空，启动期校验失败会让应用启动失败。
     *
     * @return 能力集合，不能为空
     */
    Set<ModelCapability> capabilities();

    /**
     * 执行一次文本生成；实现必须遵循 {@link ModelRequest#timeout()}、响应中断，
     * 并把失败收敛为 {@link ModelException} 的稳定原因（不回传厂商原始报文）。
     */
    ModelResponse generate(ModelRequest request);

    /**
     * 执行一次文本流式生成；失败以 {@link ModelException} 抛出，流必须可关闭并释放连接。
     *
     * <p>默认实现按能力缺失拒绝：实现方未覆盖 {@link ModelCapability#TEXT_STREAM} 时
     * 调用方得到明确错误，而不是静默退化成一次性调用。
     */
    default ModelStream stream(ModelRequest request) {
        throw new ModelException(
                ModelException.Reason.CAPABILITY_UNSUPPORTED, "端点不支持所需能力：" + ModelCapability.TEXT_STREAM);
    }

    /**
     * 执行一次结构化输出调用，返回已通过平台校验的 JSON 对象。
     *
     * <p>默认实现按能力缺失拒绝，语义同 {@link #stream(ModelRequest)}。
     */
    default StructuredModelResult generateStructured(StructuredModelRequest request) {
        throw new ModelException(
                ModelException.Reason.CAPABILITY_UNSUPPORTED, "端点不支持所需能力：" + ModelCapability.STRUCTURED_OUTPUT);
    }

    /**
     * 执行一次批量嵌入，返回与请求文本按序对应的向量。
     *
     * <p>默认实现按能力缺失拒绝，语义同 {@link #stream(ModelRequest)}；实现必须校验
     * 批次上限与向量维度自洽（{@link EmbeddingResponse} 构造即校验维度一致性）。
     */
    default EmbeddingResponse embed(EmbeddingRequest request) {
        throw new ModelException(
                ModelException.Reason.CAPABILITY_UNSUPPORTED, "端点不支持所需能力：" + ModelCapability.EMBEDDING);
    }

    /**
     * 对某个能力做一次真实调用探测（M04）：返回稳定结论，不抛出业务异常。
     *
     * <p>探测失败以 {@link ModelProbeResult.Status#FAILED} 返回，失败原因取自
     * {@link ModelException.Reason} 名称；未声明或未实现的能力返回
     * {@link ModelProbeResult.Status#UNSUPPORTED}。默认实现表示"适配器未实现该探测"。
     */
    default ModelProbeResult probe(ModelProbeKind kind) {
        return ModelProbeResult.unsupported(kind, ModelProbeResult.CODE_ADAPTER_NOT_IMPLEMENTED);
    }
}
