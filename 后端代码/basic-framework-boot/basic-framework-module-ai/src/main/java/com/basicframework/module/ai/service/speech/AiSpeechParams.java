package com.basicframework.module.ai.service.speech;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_INPUT_DURATION_EXCEEDED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_INPUT_TOO_LARGE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID;

import com.basicframework.framework.ai.core.model.media.SpeechSynthesisRequest;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 语音请求参数守卫（X04）：把"音频格式/时长/合成文本/音色/语言"收窄到**平台硬上限**。
 *
 * <p>取值全部来自 X01 已冻结的词汇表，本类不发明新枚举：
 * <ul>
 *   <li>音频格式白名单：{@link AiSpeechAudioFormat}（与客户端契约 {@code AUDIO_MIME_TYPES} 一致）；</li>
 *   <li>音频字节上限 25 MiB、单段时长上限 300 秒、合成音频上限 20 分钟：与客户端契约
 *       {@code MULTIMODAL_LIMITS}（maxAudioBytes / maxAudioDurationMs / maxSpeechOutputDurationMs）一致；</li>
 *   <li>合成文本上限：{@link SpeechSynthesisRequest#MAX_TEXT_LENGTH}（4096 码元）；</li>
 *   <li>输出格式：{@link SpeechSynthesisRequest#OUTPUT_FORMATS}（mp3/wav/opus）；</li>
 *   <li>语言：冻结语言集合（zh-CN/zh-TW/en-US/ja-JP，与客户端契约 {@code MULTIMODAL_LANGUAGES} 一致）；
 *       非空取值必须命中白名单，平台不"自动猜语言"；</li>
 *   <li>音色：形状必须与客户端契约的 {@code voiceIdSchema} 一致（字母开头、2-64 位标识符字符），
 *       具体音色表由端点声明（X01 准入矩阵）负责，平台不硬编码任何供应商音色名。</li>
 * </ul>
 *
 * <p>为什么由平台再收窄一次：端点声明的范围是上限，请求体可以伪造。守卫只做纯函数式的形状判定
 * （不接触文件、不发网络），非法请求在任何 IO 与准入判定之前就被拒绝。
 * 端点级更窄的范围（例如某端点只收 wav）由 X01 准入矩阵的端点声明负责，本类不替端点做决定。
 */
@Slf4j
@Component
public class AiSpeechParams {

    /** 应用层音频字节上限（25 MiB，与客户端契约 maxAudioBytes 一致）。 */
    public static final long MAX_AUDIO_BYTES = 25L * 1024 * 1024;

    /** 非实时语音单段输入时长上限（300 秒，与客户端契约 maxAudioDurationMs 一致）。 */
    public static final long MAX_INPUT_DURATION_MILLIS = 300_000L;

    /** 合成音频产物时长上限（20 分钟，覆盖 4096 码元文本；与客户端契约 maxSpeechOutputDurationMs 一致）。 */
    public static final long MAX_OUTPUT_DURATION_MILLIS = 1_200_000L;

    /** 合成文本长度上限（与端口契约一致）。 */
    public static final int MAX_TEXT_LENGTH = SpeechSynthesisRequest.MAX_TEXT_LENGTH;

    /** TTS 输出格式白名单（与端口契约一致）。 */
    public static final Set<String> SUPPORTED_OUTPUT_FORMATS = SpeechSynthesisRequest.OUTPUT_FORMATS;

    /** 冻结语言集合（与客户端契约 MULTIMODAL_LANGUAGES 一致；端点声明可以更窄）。 */
    public static final Set<String> SUPPORTED_LANGUAGES = Set.of("zh-CN", "zh-TW", "en-US", "ja-JP");

    /** 幂等键长度上限（与任务列宽一致）。 */
    public static final int MAX_REQUEST_KEY_LENGTH = 40;

    /** 音色标识形状（与客户端契约 voiceIdSchema 一致：字母开头、2-64 位标识符字符）。 */
    private static final Pattern VOICE_ID = Pattern.compile("^[A-Za-z][\\w-]{1,63}$");

    /** 校验幂等键：非空且不超长。 */
    public String requireRequestKey(String requestKey) {
        if (!StringUtils.hasText(requestKey) || requestKey.length() > MAX_REQUEST_KEY_LENGTH) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        return requestKey.trim();
    }

    /** 校验合成文本：非空且不超长（不裁剪内容，只拒绝空白）。 */
    public String requireText(String text) {
        if (!StringUtils.hasText(text) || text.length() > MAX_TEXT_LENGTH) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        return text;
    }

    /** 归一化并校验 TTS 输出格式；为空取平台默认 mp3。 */
    public String normalizeOutputFormat(String outputFormat) {
        String normalized =
                StringUtils.hasText(outputFormat) ? outputFormat.trim().toLowerCase(Locale.ROOT) : "mp3";
        if (!SUPPORTED_OUTPUT_FORMATS.contains(normalized)) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        return normalized;
    }

    /** 归一化并校验音色标识；为空表示端点默认音色。 */
    public String normalizeVoice(String voice) {
        if (!StringUtils.hasText(voice)) {
            return null;
        }
        String normalized = voice.trim();
        if (!VOICE_ID.matcher(normalized).matches()) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        return normalized;
    }

    /** 归一化并校验语言提示；为空表示由端点自行识别，非空必须命中冻结语言集合。 */
    public String normalizeLanguageHint(String languageHint) {
        if (!StringUtils.hasText(languageHint)) {
            return null;
        }
        String normalized = languageHint.trim();
        if (!SUPPORTED_LANGUAGES.contains(normalized)) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        return normalized;
    }

    /**
     * 校验音频引用、格式与时长声明（不接触文件内容）：
     * 文件编号为正、声明 MIME 在格式白名单内、声明字节数为正且不超平台上限、
     * 声明时长为正且不超单段上限。
     *
     * <p>时长是**调用方声明**：本切片不做音频解码，服务端无法从字节反算真实时长
     * （端点声明的更窄上限由 X01 准入矩阵负责）；因此声明值必须给出，缺失不能按"未超限"放行。
     */
    public void requireAudioDeclaredMetadata(
            Long fileId, String declaredMime, Long declaredSizeBytes, Long declaredDurationMillis) {
        if (fileId == null || fileId <= 0 || declaredSizeBytes == null || declaredSizeBytes <= 0) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        AiSpeechAudioFormat.fromMimeType(declaredMime).orElseThrow(() -> exception(AI_MEDIA_INPUT_TYPE_UNSUPPORTED));
        if (declaredSizeBytes > MAX_AUDIO_BYTES) {
            throw exception(AI_MEDIA_INPUT_TOO_LARGE);
        }
        if (declaredDurationMillis == null || declaredDurationMillis <= 0) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        if (declaredDurationMillis > MAX_INPUT_DURATION_MILLIS) {
            throw exception(AI_MEDIA_INPUT_DURATION_EXCEEDED);
        }
    }
}
