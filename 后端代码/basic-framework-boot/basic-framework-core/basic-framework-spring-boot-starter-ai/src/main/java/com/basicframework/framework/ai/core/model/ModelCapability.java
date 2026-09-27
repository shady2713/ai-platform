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
 * 不新增平行词汇。X01 追加的 6 个媒体能力（图片理解/OCR/生成/编辑、STT/TTS）与文本能力一样，
 * 都要先声明、再经探测确认后才可调用：声明与确认缺一，媒体调用在**任何网络请求之前**被拒绝
 * （{@link ModelException.Reason#CAPABILITY_NOT_ENABLED}），不做供应商回退。
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
    EMBEDDING,

    /** 图片理解（X01：视觉问答/描述；输入是平台私有文件引用，不接收厂商 URL）。 */
    IMAGE_UNDERSTANDING,

    /** 图片文字识别（X01，OCR：与图片理解**分开**声明与探测，不能用理解能力推断 OCR 可用）。 */
    IMAGE_OCR,

    /** 图片生成（X01：文生图；产物必须落平台私有文件，不采用厂商临时下载地址）。 */
    IMAGE_GENERATION,

    /** 图片编辑（X01：含图生图；输入与输出都走平台私有文件引用）。 */
    IMAGE_EDIT,

    /** 语音转写（X01：非实时 STT，可返回分段字幕）。 */
    SPEECH_TO_TEXT,

    /** 语音合成（X01：非实时 TTS，输出音频私有文件）。 */
    TEXT_TO_SPEECH;

    /**
     * 是否为多模态媒体能力（X01）：媒体能力必须经准入闸门（声明 + 探测确认）才能调用。
     *
     * <p>实时语音（FR-37，V1.2）不在本枚举内：会话协商与短期凭证需要独立契约，
     * 由后续任务按同一枚举扩展，不在这里预留占位值。
     */
    public boolean isMedia() {
        return switch (this) {
            case IMAGE_UNDERSTANDING, IMAGE_OCR, IMAGE_GENERATION, IMAGE_EDIT, SPEECH_TO_TEXT, TEXT_TO_SPEECH -> true;
            default -> false;
        };
    }

    /**
     * 该能力对应的探测类别（1:1，稳定契约）。
     *
     * <p>探测确认与声明取交集才构成可发布范围：调用方不得自行猜测探测项名称，
     * 媒体能力未探测确认时按"能力未开通"拒绝。
     */
    public ModelProbeKind probeKind() {
        return switch (this) {
            case TEXT -> ModelProbeKind.TEXT;
            case TEXT_STREAM -> ModelProbeKind.TEXT_STREAM;
            case STRUCTURED_OUTPUT -> ModelProbeKind.STRUCTURED_OUTPUT;
            case TOOL_CALLING -> ModelProbeKind.TOOL_CALLING;
            case EMBEDDING -> ModelProbeKind.EMBEDDING;
            case IMAGE_UNDERSTANDING -> ModelProbeKind.IMAGE_UNDERSTANDING;
            case IMAGE_OCR -> ModelProbeKind.IMAGE_OCR;
            case IMAGE_GENERATION -> ModelProbeKind.IMAGE_GENERATION;
            case IMAGE_EDIT -> ModelProbeKind.IMAGE_EDIT;
            case SPEECH_TO_TEXT -> ModelProbeKind.SPEECH_TO_TEXT;
            case TEXT_TO_SPEECH -> ModelProbeKind.TEXT_TO_SPEECH;
        };
    }
}
