package com.basicframework.module.ai.service.vision;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_INPUT_TOO_LARGE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 受控图片校验（X02）：把"调用方声明的图片"变成"服务端已核验的图片"，不合格一律拒绝。
 *
 * <p>校验顺序与拒绝码（全部在任何模型调用之前完成，因此拒绝路径零外发）：
 * <ol>
 *   <li><b>元数据形状</b>：文件编号为正、声明 MIME 在白名单、声明字节数为正且不超过平台上限
 *       （超限 → {@code AI_MEDIA_INPUT_TOO_LARGE}；白名单外 → {@code AI_MEDIA_INPUT_TYPE_UNSUPPORTED}）；</li>
 *   <li><b>声明与内容一致</b>：真实字节数必须等于声明值、文件头魔数必须与声明 MIME 一致、
 *       带摘要时摘要必须一致——任何一项不符按 {@code AI_MEDIA_INPUT_TYPE_UNSUPPORTED} 拒绝
 *       （"伪装图片"指的就是声明与内容不是同一种东西）；</li>
 *   <li><b>尺寸可信且在上限内</b>：只解析文件头取真实宽高，解析不出来或超出单边上限
 *       （{@link AiVisionLimits#MAX_IMAGE_DIMENSION}）按 {@code AI_MEDIA_REQUEST_INVALID} 拒绝——
 *       不缩放、不裁剪、不降采样后继续。</li>
 * </ol>
 *
 * <p>本类不做业务归属判定：文件必须先经 A07 业务 ACL 读取，读到字节后再交到这里。
 */
@Component
public class AiVisionImageGuard {

    private static final String SHA256_PATTERN = "^[0-9a-f]{64}$";

    /**
     * 校验声明元数据（不接触文件内容）：形状不合法立即拒绝，避免为明显非法的请求做 IO。
     */
    public void requireDeclaredMetadata(Long fileId, String declaredMimeType, Long declaredSizeBytes) {
        if (fileId == null || fileId <= 0 || declaredSizeBytes == null || declaredSizeBytes <= 0) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        AiVisionImageFormat.fromMimeType(declaredMimeType)
                .orElseThrow(() -> exception(AI_MEDIA_INPUT_TYPE_UNSUPPORTED));
        if (declaredSizeBytes > AiVisionLimits.MAX_IMAGE_BYTES) {
            throw exception(AI_MEDIA_INPUT_TOO_LARGE);
        }
    }

    /**
     * 校验真实内容与声明一致，并返回核验后的图片信息。
     *
     * @param fileId           平台私有文件编号
     * @param declaredMimeType 调用方声明的 MIME（白名单内的取值）
     * @param declaredSizeBytes 调用方声明的字节数
     * @param declaredSha256   调用方声明的摘要（可为空；非空必须与真实内容一致）
     * @param content          经 A07 读取到的真实字节
     */
    public AiVisionImageInfo verify(
            Long fileId, String declaredMimeType, long declaredSizeBytes, String declaredSha256, byte[] content) {
        AiVisionImageFormat format = AiVisionImageFormat.fromMimeType(declaredMimeType)
                .orElseThrow(() -> exception(AI_MEDIA_INPUT_TYPE_UNSUPPORTED));
        if (content == null || content.length == 0) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        if (content.length > AiVisionLimits.MAX_IMAGE_BYTES) {
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
        AiVisionImageHeader.Size size;
        try {
            size = AiVisionImageHeader.read(content, format);
        } catch (RuntimeException failure) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        AiVisionImageInfo info =
                new AiVisionImageInfo(fileId, format, content.length, size.width(), size.height(), computed);
        if (!info.withinDimensionLimits()) {
            // 超大像素：按请求不合规拒绝（端点声明的更窄范围由准入矩阵负责）
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        return info;
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
