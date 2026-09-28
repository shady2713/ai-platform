package com.basicframework.module.ai.service.speech;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_INPUT_TOO_LARGE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_OUTPUT_DURATION_EXCEEDED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_OUTPUT_EMPTY;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 受控音频校验（X04）：把"调用方声明的音频"与"上游返回的音频"变成服务端已核验的事实。
 *
 * <p>输入侧（受理与执行各一次）顺序固定，全部在任何模型调用之前完成，因此拒绝路径零外发：
 * <ol>
 *   <li><b>声明与内容一致</b>：真实字节数必须等于声明值、文件头魔数必须与声明 MIME 一致、
 *       带摘要时摘要必须一致——任一项不符按 {@code AI_MEDIA_INPUT_TYPE_UNSUPPORTED} 拒绝
 *       （"伪装音频"指的就是声明与内容不是同一种东西）；</li>
 *   <li><b>字节上限</b>：内容超过平台上限按 {@code AI_MEDIA_INPUT_TOO_LARGE} 拒绝；</li>
 *   <li><b>时长</b>：本切片不做音频解码，真实时长无法从字节反算；单段时长由受理时的声明值收窄
 *       （{@link AiSpeechParams#requireAudioDeclaredMetadata}），执行期只复核内容本身。</li>
 * </ol>
 *
 * <p>产物侧（TTS）只按内容判定：MIME 必须与请求输出格式的固定映射一致
 * （{@link AiSpeechAudioFormat#fromOutputFormat}，不让上游自报 MIME），文件头必须与该格式一致；
 * 空字节按 {@code AI_MEDIA_OUTPUT_EMPTY}、伪装内容按 {@code AI_MEDIA_OUTPUT_INVALID} 拒绝（502，
 * 不落半段音频、不转码）；真实时长超过平台上限按 {@code AI_MEDIA_OUTPUT_DURATION_EXCEEDED} 拒绝；
 * 时长未知按未知处理（不写 0、不冒充"未超限"）。
 *
 * <p>本类不做业务归属判定：文件必须先经 A07 业务 ACL 读取，读到字节后再交到这里。
 */
@Component
public class AiSpeechAudioGuard {

    private static final String SHA256_PATTERN = "^[0-9a-f]{64}$";

    /**
     * 输入核验（受理与执行复用同一判定）：声明与内容不一致一律拒绝。
     *
     * @param declaredMimeType  调用方声明的 MIME（白名单内的取值）
     * @param declaredSizeBytes 调用方声明的字节数
     * @param declaredSha256    调用方声明的摘要（可为空；非空必须与真实内容一致）
     * @param content           经 A07 读取到的真实字节
     */
    public AiSpeechAudioVerified verifyInput(
            String declaredMimeType, long declaredSizeBytes, String declaredSha256, byte[] content) {
        AiSpeechAudioFormat format = AiSpeechAudioFormat.fromMimeType(declaredMimeType)
                .orElseThrow(() -> exception(AI_MEDIA_INPUT_TYPE_UNSUPPORTED));
        if (content == null || content.length == 0) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        if (content.length > AiSpeechParams.MAX_AUDIO_BYTES) {
            throw exception(AI_MEDIA_INPUT_TOO_LARGE);
        }
        if (content.length != declaredSizeBytes) {
            // 声明与内容不是同一份文件：不能把"声明的大小"当作准入依据
            throw exception(AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
        }
        if (!format.matchesMagic(content)) {
            throw exception(AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
        }
        String computed = sha256Hex(content);
        if (declaredSha256 != null && !declaredSha256.isBlank()) {
            String normalized = declaredSha256.trim().toLowerCase(Locale.ROOT);
            if (!normalized.matches(SHA256_PATTERN) || !normalized.equals(computed)) {
                throw exception(AI_MEDIA_REQUEST_INVALID);
            }
        }
        // 输入侧没有时长事实：本切片不做解码，时长由受理时的声明值收窄（这里返回未知）
        return new AiSpeechAudioVerified(format, content.length, null, computed);
    }

    /**
     * 产物核验（TTS）：字节必须是白名单格式的真实音频且不超过字节/时长上限。
     *
     * @param mimeType               端口返回的产物 MIME（必须与请求输出格式的固定映射一致）
     * @param content                端口返回的产物字节
     * @param reportedDurationMillis 端口换算出的真实时长（未知为空；未知不拒绝也不写 0）
     * @param expectedOutputFormat   受理时固定的请求输出格式（mp3/wav/opus）
     */
    public AiSpeechAudioVerified verifyOutput(
            String mimeType, byte[] content, Long reportedDurationMillis, String expectedOutputFormat) {
        AiSpeechAudioFormat expected = AiSpeechAudioFormat.fromOutputFormat(expectedOutputFormat)
                .orElseThrow(() -> exception(AI_MEDIA_REQUEST_INVALID));
        if (mimeType == null || !expected.mimeType().equals(mimeType.trim().toLowerCase(Locale.ROOT))) {
            // 上游产物与请求的格式不一致：不能只信上游声明
            throw exception(AI_MEDIA_OUTPUT_INVALID);
        }
        if (content == null || content.length == 0) {
            throw exception(AI_MEDIA_OUTPUT_EMPTY);
        }
        if (content.length > AiSpeechParams.MAX_AUDIO_BYTES) {
            throw exception(AI_MEDIA_OUTPUT_INVALID);
        }
        if (!expected.matchesMagic(content)) {
            throw exception(AI_MEDIA_OUTPUT_INVALID);
        }
        if (reportedDurationMillis != null && reportedDurationMillis > AiSpeechParams.MAX_OUTPUT_DURATION_MILLIS) {
            throw exception(AI_MEDIA_OUTPUT_DURATION_EXCEEDED);
        }
        return new AiSpeechAudioVerified(expected, content.length, reportedDurationMillis, sha256Hex(content));
    }

    /** 归一化可选摘要；空值返回空，非法形状按请求不合规拒绝。 */
    public Optional<String> normalizeDeclaredSha256(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return Optional.empty();
        }
        String normalized = sha256.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches(SHA256_PATTERN)) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        return Optional.of(normalized);
    }

    static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException missingAlgorithm) {
            throw new IllegalStateException("SHA-256 不可用", missingAlgorithm);
        }
    }
}
