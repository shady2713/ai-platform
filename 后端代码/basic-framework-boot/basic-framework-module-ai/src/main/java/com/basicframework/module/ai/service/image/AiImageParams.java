package com.basicframework.module.ai.service.image;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID;

import com.basicframework.framework.ai.core.model.media.ImageGenerationRequest;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 图片请求参数守卫（X03）：把"尺寸/张数/格式/文本长度"收窄到平台允许的范围。
 *
 * <p>为什么由平台再收窄一次：端点声明的范围是**上限**，请求体可以伪造。守卫只做纯函数式的形状判定
 * （不接触文件、不发网络），非法请求在任何 IO 与准入判定之前就被拒绝。
 *
 * <p>尺寸白名单是**显式枚举**而不是"宽高各自范围"：像素总量、长宽比与上游实际支持的档位都是有限的，
 * 枚举让"平台支持的档位"变成可审计的事实，也避免出现 1x100000 这类把上游打挂的形状。
 */
@Slf4j
@Component
public class AiImageParams {

    /** 平台支持的生成尺寸（宽x高）。 */
    public static final Set<String> SUPPORTED_SIZES =
            Set.of("256x256", "512x512", "768x768", "1024x1024", "1024x1536", "1536x1024", "1024x1792", "1792x1024");

    /** 平台支持的输出格式（与 {@link ImageGenerationRequest#OUTPUT_FORMATS} 一致）。 */
    public static final Set<String> SUPPORTED_FORMATS = ImageGenerationRequest.OUTPUT_FORMATS;

    /** 单次生成张数上限（与端口契约一致）。 */
    public static final int MAX_COUNT = ImageGenerationRequest.MAX_COUNT;

    /** 提示词/指令长度上限（与任务列宽一致）。 */
    public static final int MAX_TEXT_LENGTH = 2000;

    /** 幂等键长度上限（与任务列宽一致）。 */
    public static final int MAX_REQUEST_KEY_LENGTH = 40;

    /** 归一化并校验尺寸；为空表示由端点默认值决定。 */
    public String normalizeSize(String size) {
        if (!StringUtils.hasText(size)) {
            return null;
        }
        String normalized = size.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_SIZES.contains(normalized)) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        return normalized;
    }

    /** 归一化并校验输出格式；为空按 png。 */
    public String normalizeFormat(String outputFormat) {
        if (!StringUtils.hasText(outputFormat)) {
            return "png";
        }
        String normalized = outputFormat.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_FORMATS.contains(normalized)) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        return normalized;
    }

    /** 校验生成张数：为空按 1 张，超出平台上限拒绝。 */
    public int normalizeCount(Integer count) {
        if (count == null) {
            return 1;
        }
        if (count < 1 || count > MAX_COUNT) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        return count;
    }

    /** 校验提示词/指令/合成文本：非空且不超长。 */
    public String requireText(String text) {
        if (!StringUtils.hasText(text) || text.length() > MAX_TEXT_LENGTH) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        return text.trim();
    }

    /** 校验幂等键：非空且不超长。 */
    public String requireRequestKey(String requestKey) {
        if (!StringUtils.hasText(requestKey) || requestKey.length() > MAX_REQUEST_KEY_LENGTH) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        return requestKey.trim();
    }

    /** 操作类型必须是图片类（生成/编辑）；其它操作在这里就拒绝，不进任务表。 */
    public String requireImageOperation(String operation) {
        if (AiMediaTaskDO.OPERATION_GENERATE.equals(operation) || AiMediaTaskDO.OPERATION_EDIT.equals(operation)) {
            return operation;
        }
        throw exception(AI_MEDIA_REQUEST_INVALID);
    }
}
