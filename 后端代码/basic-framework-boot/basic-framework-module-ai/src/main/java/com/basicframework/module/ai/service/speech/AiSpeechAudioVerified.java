package com.basicframework.module.ai.service.speech;

/**
 * 已核验的音频事实（X04）：服务端从真实字节判定出的格式、字节数与摘要，产物还可能带时长。
 *
 * <p>与调用方的声明分开：声明只用于选择解析器与核对，落库与展示一律用这里的事实，
 * 避免"声明 wav 实际是网页"或"声明 8 秒实际 10 分钟"这类不一致进入任务与产物行。
 *
 * @param format         核验后的音频格式（白名单内的取值）
 * @param sizeBytes      真实字节数
 * @param durationMillis 真实时长（毫秒）；输入侧无解码能力、产物侧上游未提供时为空
 * @param sha256         内容摘要（小写 64 位十六进制，服务端计算）
 */
public record AiSpeechAudioVerified(AiSpeechAudioFormat format, long sizeBytes, Long durationMillis, String sha256) {}
