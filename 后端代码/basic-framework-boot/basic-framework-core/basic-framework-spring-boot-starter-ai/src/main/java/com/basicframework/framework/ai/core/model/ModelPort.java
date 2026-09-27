package com.basicframework.framework.ai.core.model;

import com.basicframework.framework.ai.core.model.media.ImageEditRequest;
import com.basicframework.framework.ai.core.model.media.ImageGenerationRequest;
import com.basicframework.framework.ai.core.model.media.ImageOcrRequest;
import com.basicframework.framework.ai.core.model.media.ImageResult;
import com.basicframework.framework.ai.core.model.media.ImageUnderstandingRequest;
import com.basicframework.framework.ai.core.model.media.MediaTextResponse;
import com.basicframework.framework.ai.core.model.media.SpeechSynthesisRequest;
import com.basicframework.framework.ai.core.model.media.SpeechSynthesisResponse;
import com.basicframework.framework.ai.core.model.media.SpeechTranscriptionRequest;
import com.basicframework.framework.ai.core.model.media.SpeechTranscriptionResponse;
import java.util.Set;

/**
 * 模型能力端口：业务模块与模型提供方之间的稳定契约。
 *
 * <p>实现只能来自 {@code com.basicframework.framework.ai.provider.springai} 包：
 * 该包是仓库内唯一允许直接引用 Spring AI 类型的区域（架构说明 03 的上游组件选择），
 * 其他包引用厂商类型会被模块边界门禁的 vendor 规则拒绝。
 *
 * <p>接口按任务扩展但保持向后兼容，不复制第二套并行契约：M02 增加一次性调用，
 * M03 增加文本流与结构化输出，M04 增加批量嵌入与能力探测，X01 增加多模态媒体调用
 * （{@link #understandImage}、{@link #recognizeImageText}、{@link #generateImage}、
 * {@link #editImage}、{@link #transcribeSpeech}、{@link #synthesizeSpeech}）；
 * 未覆盖的能力在默认实现里按"能力未开通"拒绝并指明能力名，而不是静默降级或回退到别的模型。
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

    // ========== 多模态媒体（X01） ==========
    //
    // 六个媒体方法都按"能力未开通"的默认实现拒绝：适配器未覆盖时调用方得到
    // CAPABILITY_NOT_ENABLED 与能力名，不会静默降级为文本调用，也不会自动切换到别的端点/供应商。
    // 实现方覆盖时必须先核对 capabilities() 是否包含对应能力，并遵循请求里的超时语义。

    /**
     * 图片理解（X01）：返回描述/问答文本；默认按"能力未开通"拒绝。
     */
    default MediaTextResponse understandImage(ImageUnderstandingRequest request) {
        throw capabilityNotEnabled(ModelCapability.IMAGE_UNDERSTANDING);
    }

    /**
     * 图片文字识别（X01，OCR）：返回识别文本；默认按"能力未开通"拒绝。
     *
     * <p>与 {@link #understandImage} 分开：适配器不得用图片理解能力代替 OCR 探测或调用。
     */
    default MediaTextResponse recognizeImageText(ImageOcrRequest request) {
        throw capabilityNotEnabled(ModelCapability.IMAGE_OCR);
    }

    /**
     * 图片生成（X01，文生图）：返回非空图片产物；默认按"能力未开通"拒绝。
     */
    default ImageResult generateImage(ImageGenerationRequest request) {
        throw capabilityNotEnabled(ModelCapability.IMAGE_GENERATION);
    }

    /**
     * 图片编辑（X01，含图生图）：以平台私有底图生成新图片；默认按"能力未开通"拒绝。
     */
    default ImageResult editImage(ImageEditRequest request) {
        throw capabilityNotEnabled(ModelCapability.IMAGE_EDIT);
    }

    /**
     * 语音转写（X01，非实时 STT）：返回全文与可选分段字幕；默认按"能力未开通"拒绝。
     */
    default SpeechTranscriptionResponse transcribeSpeech(SpeechTranscriptionRequest request) {
        throw capabilityNotEnabled(ModelCapability.SPEECH_TO_TEXT);
    }

    /**
     * 语音合成（X01，非实时 TTS）：返回音频产物；默认按"能力未开通"拒绝。
     */
    default SpeechSynthesisResponse synthesizeSpeech(SpeechSynthesisRequest request) {
        throw capabilityNotEnabled(ModelCapability.TEXT_TO_SPEECH);
    }

    /** 媒体能力未开通的稳定拒绝：消息只含能力名，不含输入内容与上游信息。 */
    private static ModelException capabilityNotEnabled(ModelCapability capability) {
        return new ModelException(
                ModelException.Reason.CAPABILITY_NOT_ENABLED, "端点未开通能力：" + capability + "（需先声明并通过探测）");
    }
}
