package com.basicframework.module.ai.service.vision;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_OUTPUT_EMPTY;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_CAPABILITY_NOT_ENABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.media.ImageOcrRequest;
import com.basicframework.framework.ai.core.model.media.ImageUnderstandingRequest;
import com.basicframework.framework.ai.core.model.media.MediaFileRef;
import com.basicframework.framework.ai.core.model.media.MediaTextResponse;
import com.basicframework.module.ai.adapter.model.AiMediaCapabilityGate;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.vision.dto.AiVisionImageRefDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionOcrRequestDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionOcrResultDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionRegionDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionTextResultDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionUnderstandRequestDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionUsageDTO;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 图片理解与 OCR 实现（X02）。
 *
 * <p>顺序即安全语义（每次调用都按此顺序，任一步失败都不进入下一步）：
 * <ol>
 *   <li><b>形状与声明校验</b>：端点编号、图片声明元数据、指令/语言/超时；不合法立即拒绝（零 IO）；</li>
 *   <li><b>能力准入</b>：{@code assertAdmitted} 先于**读取文件**与**解析客户端**，未声明/未探测确认的能力
 *       在这里被拒（{@code AI_MODEL_CAPABILITY_NOT_ENABLED}），既不读用户文件也不发生任何网络请求；</li>
 *   <li><b>业务 ACL</b>：{@link AiFileService#read(Long)} 按**当前**主体判定归属，无权限与不存在同语义；</li>
 *   <li><b>内容核验</b>：字节数、文件头、摘要与声明一致，真实像素在平台上限内；</li>
 *   <li><b>端口调用</b>：构造 X01 冻结的媒体请求并经准入闸门调用；上游没有文本时按输出异常拒绝。</li>
 * </ol>
 *
 * <p>另外两条刻意的选择：
 * <ul>
 *   <li>准入在读取文件之前显式执行（{@code invoke} 内部还会再判定一次）：这样"能力未开通"的拒绝
 *       连用户文件都不读，也让测试能用"文件服务从未被调用"钉住这个顺序；</li>
 *   <li>模型标识来自端点的**当前配置版本**，不接受请求参数指定（客户端不能借参数换模型/换供应商）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AiVisionServiceImpl implements AiVisionService {

    /** 单次调用超时的允许区间（毫秒）：过小会必然超时，过大等于没有上限。 */
    private static final long MIN_TIMEOUT_MILLIS = 1_000L;

    private static final long MAX_TIMEOUT_MILLIS = 600_000L;

    private final AiFileService fileService;

    private final AiVisionImageGuard imageGuard;

    private final AiMediaCapabilityGate mediaGate;

    private final AiModelEndpointService endpointService;

    @Override
    public AiVisionTextResultDTO understandImage(AiVisionUnderstandRequestDTO request) {
        if (request == null || request.getEndpointId() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        String instruction = requireInstruction(request.getInstruction());
        Duration timeout = normalizeTimeout(request.getTimeoutMillis());
        requireDeclaredImage(request.getImage());
        Long endpointId = request.getEndpointId();
        mediaGate.assertAdmitted(endpointId, ModelCapability.IMAGE_UNDERSTANDING);
        AiVisionImageInfo image = readAndVerify(request.getImage());
        String modelId = currentModelId(endpointId);
        MediaTextResponse response = mediaGate.invoke(
                endpointId,
                ModelCapability.IMAGE_UNDERSTANDING,
                port -> port.understandImage(
                        new ImageUnderstandingRequest(modelId, toMediaFileRef(image), instruction, timeout)));
        return new AiVisionTextResultDTO()
                .setFileId(image.fileId())
                .setText(requireText(response))
                .setUsage(AiVisionUsageDTO.from(response == null ? null : response.usage()))
                .setFinishReason(response == null ? null : response.finishReason());
    }

    @Override
    public AiVisionOcrResultDTO recognizeText(AiVisionOcrRequestDTO request) {
        if (request == null || request.getEndpointId() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        String languageHint = normalizeLanguageHint(request.getLanguageHint());
        Duration timeout = normalizeTimeout(request.getTimeoutMillis());
        requireDeclaredImage(request.getImage());
        Long endpointId = request.getEndpointId();
        mediaGate.assertAdmitted(endpointId, ModelCapability.IMAGE_OCR);
        AiVisionImageInfo image = readAndVerify(request.getImage());
        String modelId = currentModelId(endpointId);
        MediaTextResponse response = mediaGate.invoke(
                endpointId,
                ModelCapability.IMAGE_OCR,
                port -> port.recognizeImageText(
                        new ImageOcrRequest(modelId, toMediaFileRef(image), languageHint, timeout)));
        return new AiVisionOcrResultDTO()
                .setFileId(image.fileId())
                .setPage(1)
                .setText(requireText(response))
                .setRegion(AiVisionRegionDTO.wholePage())
                .setRegionSource(AiVisionRegionSource.WHOLE_PAGE)
                // 端口契约只返回文本：没有逐区域置信度就记 UNKNOWN，不填任何数字
                .setConfidenceSource(AiVisionConfidenceSource.UNKNOWN)
                .setConfidence(null)
                .setReviewRequired(true)
                .setUsage(AiVisionUsageDTO.from(response == null ? null : response.usage()));
    }

    /** 声明的图片元数据（编号/白名单 MIME/字节数）在读取文件之前先判一次，避免为明显非法请求做 IO。 */
    private void requireDeclaredImage(AiVisionImageRefDTO image) {
        if (image == null) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        imageGuard.requireDeclaredMetadata(image.getFileId(), image.getMime(), image.getSize());
        imageGuard.normalizeDeclaredSha256(image.getSha256());
    }

    /** 按当前主体读取私有文件并核验真实内容；无权限与不存在同语义（由 A07 保证）。 */
    private AiVisionImageInfo readAndVerify(AiVisionImageRefDTO image) {
        byte[] content = fileService.read(image.getFileId());
        return imageGuard.verify(image.getFileId(), image.getMime(), image.getSize(), image.getSha256(), content);
    }

    private String requireInstruction(String instruction) {
        if (!StringUtils.hasText(instruction)) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        String trimmed = instruction.trim();
        if (trimmed.length() > AiVisionLimits.MAX_INSTRUCTION_LENGTH) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        return trimmed;
    }

    /** 语言提示只接受短标识形状（如 zh-CN）；不接受任意文本被当成提示发给上游。 */
    private String normalizeLanguageHint(String languageHint) {
        if (!StringUtils.hasText(languageHint)) {
            return null;
        }
        String trimmed = languageHint.trim();
        if (trimmed.length() > AiVisionLimits.MAX_LANGUAGE_HINT_LENGTH || !trimmed.matches("^[A-Za-z][A-Za-z0-9-]*$")) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        return trimmed;
    }

    private Duration normalizeTimeout(Long timeoutMillis) {
        if (timeoutMillis == null) {
            return null;
        }
        if (timeoutMillis < MIN_TIMEOUT_MILLIS || timeoutMillis > MAX_TIMEOUT_MILLIS) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        return Duration.ofMillis(timeoutMillis);
    }

    /** 当前配置版本的模型标识：准入已通过，因此版本必然存在。 */
    private String currentModelId(Long endpointId) {
        return endpointService.getRevisions(endpointId).stream()
                .findFirst()
                .map(AiModelEndpointRevisionDO::getModelId)
                .filter(StringUtils::hasText)
                .orElseThrow(() -> exception(AI_MODEL_CAPABILITY_NOT_ENABLED));
    }

    /** 私有文件引用：只带标识与已核验元数据，不带字节与地址。 */
    private static MediaFileRef toMediaFileRef(AiVisionImageInfo image) {
        return new MediaFileRef(image.fileId(), image.format().mimeType(), image.sizeBytes(), image.sha256());
    }

    /** 空文本是上游协议异常（X01 的媒体输出语义），不得当作识别成功交付。 */
    private static String requireText(MediaTextResponse response) {
        if (response == null || !StringUtils.hasText(response.text())) {
            throw exception(AI_MEDIA_OUTPUT_EMPTY);
        }
        return response.text();
    }
}
