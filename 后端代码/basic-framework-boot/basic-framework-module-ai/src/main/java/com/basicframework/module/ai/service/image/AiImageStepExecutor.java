package com.basicframework.module.ai.service.image;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.ai.core.model.media.ImageEditRequest;
import com.basicframework.framework.ai.core.model.media.ImageGenerationRequest;
import com.basicframework.framework.ai.core.model.media.ImageResult;
import com.basicframework.framework.ai.core.model.media.MediaArtifact;
import com.basicframework.framework.ai.core.model.media.MediaFileRef;
import com.basicframework.module.ai.adapter.model.AiMediaCapabilityGate;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaAssetDO;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.dal.mysql.media.AiMediaAssetMapper;
import com.basicframework.module.ai.service.file.AiFileBusinessType;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.media.AiMediaStepExecutor;
import com.basicframework.module.ai.service.media.dto.AiMediaStepOutcome;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskLeaseDTO;
import com.basicframework.module.ai.service.vision.AiVisionImageGuard;
import com.basicframework.module.ai.service.vision.AiVisionImageOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 图片生成/编辑执行器（X03）：准入 → 调用 → **产物核验** → 落平台私有文件 → 记产物与用量。
 *
 * <p>不伪装的三条：
 * <ol>
 *   <li><b>不读上游地址</b>：产物只可能是端口返回的字节（{@link MediaArtifact}）。协议里没有下载地址字段，
 *       本类也不持有任何出网客户端——"上游给个 URL 让我们去取"这条路径在平台里根本不存在；</li>
 *   <li><b>产物先验后存</b>：字节必须真的是白名单格式的位图且在字节/像素上限内
 *       （{@link AiVisionImageGuard#verifyOutput}），否则整笔按 {@code AI_MEDIA_OUTPUT_INVALID} 失败，
 *       不落半张图、不转码；</li>
 *   <li><b>编辑先读源图</b>：编辑必须在执行期按当前主体读一次源图（A07 业务 ACL）。
 *       源图被解除引用/失权时，任务以 {@code AI_RESOURCE_NOT_FOUND} 失败——受理时能读、执行时已失权，
 *       一样拒绝（AT 的"源图失权后编辑拒绝"）。</li>
 * </ol>
 *
 * <p>用量只落上游真实计数：上游没给就记 UNKNOWN 且数值为空，不用图片张数冒充"上游计量"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiImageStepExecutor implements AiMediaStepExecutor {

    /** 平台私有图片的 MIME 与格式由产物核验给出，这里只负责命名。 */
    private static final String FILE_NAME_PREFIX = "ai-image-";

    /** 上游按 token 计量时的平台单位（缺失时不写数值也不写单位）。 */
    static final String USAGE_UNIT_TOKEN = "TOKEN";

    private final AiMediaCapabilityGate mediaGate;

    private final AiFileService fileService;

    private final AiVisionImageGuard imageGuard;

    private final AiMediaAssetMapper assetMapper;

    @Override
    public boolean supports(String operation) {
        return AiMediaTaskDO.OPERATION_GENERATE.equals(operation) || AiMediaTaskDO.OPERATION_EDIT.equals(operation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMediaStepOutcome execute(AiMediaTaskLeaseDTO lease, AiMediaTaskDO task) {
        ModelCapability capability = capabilityOf(task);
        // 1) 准入：端点启用 + 能力已声明 + 探测已确认；未通过不发生任何调用
        mediaGate.assertAdmitted(task.getEndpointId(), capability);
        // 2) 调用：端口只返回字节产物（没有下载地址可以读）
        ImageResult result =
                switch (task.getOperation()) {
                    case AiMediaTaskDO.OPERATION_GENERATE ->
                        mediaGate.invoke(
                                task.getEndpointId(),
                                capability,
                                port -> port.generateImage(new ImageGenerationRequest(
                                        task.getModelRef(),
                                        task.getInputText(),
                                        task.getTargetSize(),
                                        task.getOutputCount(),
                                        task.getOutputFormat(),
                                        null)));
                    case AiMediaTaskDO.OPERATION_EDIT ->
                        mediaGate.invoke(
                                task.getEndpointId(),
                                capability,
                                port -> port.editImage(new ImageEditRequest(
                                        task.getModelRef(),
                                        sourceRef(task),
                                        task.getInputText(),
                                        task.getTargetSize(),
                                        task.getOutputFormat(),
                                        null)));
                    default -> throw exception(AI_STATE_CONFLICT);
                };
        // 3) 产物核验 + 落私有文件 + 记产物行（序号即展示顺序）
        int ordinal = 0;
        for (MediaArtifact artifact : result.images()) {
            AiVisionImageOutput output = imageGuard.verifyOutput(artifact.mimeType(), artifact.content());
            ordinal++;
            Long fileId = fileService
                    .upload(
                            AiFileBusinessType.MEDIA_TASK.code(),
                            String.valueOf(task.getId()),
                            fileName(task.getId(), ordinal, output),
                            output.format().mimeType(),
                            artifact.content())
                    .getFileId();
            assetMapper.insert(new AiMediaAssetDO()
                    .setTaskId(task.getId())
                    .setOrdinal(ordinal)
                    .setFileId(fileId)
                    .setMimeType(output.format().mimeType())
                    .setSizeBytes(output.sizeBytes())
                    .setSha256(output.sha256())
                    .setWidth(output.width())
                    .setHeight(output.height())
                    .setDurationMillis(null)
                    .setVersion(0));
        }
        Long quantity = usageQuantityOf(result.usage());
        return AiMediaStepOutcome.succeeded(
                ordinal, quantity == null ? null : USAGE_UNIT_TOKEN, quantity, usageSourceOf(result.usage()));
    }

    /** 编辑底图引用：只带编号与声明级元数据，不带字节、不带地址（端口按编号取字节）。 */
    private MediaFileRef sourceRef(AiMediaTaskDO task) {
        if (task.getSourceFileId() == null) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        // 执行期按当前主体读一次源图：失权/解除引用在这里失败，不进入上游调用
        byte[] source = fileService.read(task.getSourceFileId());
        if (source == null || source.length == 0) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        return new MediaFileRef(
                task.getSourceFileId(),
                task.getSourceMime(),
                source.length,
                task.getSourceSha256() == null || task.getSourceSha256().isBlank() ? null : task.getSourceSha256());
    }

    /** 生成/编辑能力取自任务行（受理时固定），非图片能力一律不受理。 */
    private static ModelCapability capabilityOf(AiMediaTaskDO task) {
        ModelCapability capability = ModelCapability.valueOf(task.getCapability());
        if (!capability.isMedia()
                || (!ModelCapability.IMAGE_GENERATION.equals(capability)
                        && !ModelCapability.IMAGE_EDIT.equals(capability))) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        return capability;
    }

    /** 产物文件名：任务编号 + 序号 + 服务端判定出的格式（不含上游返回的任何字符串）。 */
    private static String fileName(Long taskId, int ordinal, AiVisionImageOutput output) {
        return FILE_NAME_PREFIX + taskId + "-" + ordinal + "." + output.format().formatName();
    }

    /** 用量来源：上游给了计数记 REPORTED，否则 UNKNOWN（不写 0）。 */
    private static String usageSourceOf(ModelUsage usage) {
        return usage != null && usage.isKnown()
                ? AiMediaTaskDO.USAGE_SOURCE_REPORTED
                : AiMediaTaskDO.USAGE_SOURCE_UNKNOWN;
    }

    /** 上游用量到平台计量的映射（图片按 token 计的世界里单位为 TOKEN；上游缺失时数值为空）。 */
    static Long usageQuantityOf(ModelUsage usage) {
        if (usage == null) {
            return null;
        }
        Integer total = usage.totalTokens();
        return total == null ? null : total.longValue();
    }
}
