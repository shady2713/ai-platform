package com.basicframework.module.ai.service.image;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.media.MediaFileRef;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.image.dto.AiImageEditDTO;
import com.basicframework.module.ai.service.image.dto.AiImageGenerateDTO;
import com.basicframework.module.ai.service.media.AiMediaTaskService;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskResultDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskSubmitDTO;
import com.basicframework.module.ai.service.vision.AiVisionImageGuard;
import com.basicframework.module.ai.service.vision.AiVisionImageOutput;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 图片生成与编辑实现（X03）：收窄参数 → （编辑）核验底图 → 幂等受理。
 *
 * <p>为什么受理前要核验底图：任务一旦受理就可能在几分钟后才执行，若受理时不核验，
 * 无权/伪装的底图会先进入任务表，直到执行期才失败——调用方拿到的是"排队中"的假象。
 * 这里在受理前按当前主体读一次底图并核验（A07 业务 ACL + 图片格式/像素/摘要核对），
 * 执行期还会再读一次（受理时能读、执行时失权同样拒绝）。
 */
@Service
@RequiredArgsConstructor
public class AiImageServiceImpl implements AiImageService {

    private final AiImageParams params;

    private final AiMediaTaskService taskService;

    private final AiFileService fileService;

    private final AiVisionImageGuard imageGuard;

    @Override
    public AiMediaTaskResultDTO generate(AiImageGenerateDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("生成请求不能为空");
        }
        String requestKey = params.requireRequestKey(request.getRequestKey());
        String prompt = params.requireText(request.getPrompt());
        return taskService.submit(new AiMediaTaskSubmitDTO()
                .setRequestKey(requestKey)
                .setMediaKind(AiMediaTaskDO.KIND_IMAGE)
                .setOperation(AiMediaTaskDO.OPERATION_GENERATE)
                .setCapability(ModelCapability.IMAGE_GENERATION.name())
                .setEndpointId(request.getEndpointId())
                .setInputText(prompt)
                .setTargetSize(params.normalizeSize(request.getSize()))
                .setOutputCount(params.normalizeCount(request.getCount()))
                .setOutputFormat(params.normalizeFormat(request.getOutputFormat())));
    }

    @Override
    public AiMediaTaskResultDTO edit(AiImageEditDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("编辑请求不能为空");
        }
        String requestKey = params.requireRequestKey(request.getRequestKey());
        String instruction = params.requireText(request.getInstruction());
        MediaFileRef source = verifySource(request);
        return taskService.submit(new AiMediaTaskSubmitDTO()
                .setRequestKey(requestKey)
                .setMediaKind(AiMediaTaskDO.KIND_IMAGE)
                .setOperation(AiMediaTaskDO.OPERATION_EDIT)
                .setCapability(ModelCapability.IMAGE_EDIT.name())
                .setEndpointId(request.getEndpointId())
                .setInputText(instruction)
                .setSourceFileId(source.fileId())
                .setSourceMime(source.mimeType())
                .setSourceSizeBytes(source.sizeBytes())
                .setSourceSha256(source.sha256())
                .setTargetSize(params.normalizeSize(request.getSize()))
                .setOutputCount(1)
                .setOutputFormat(params.normalizeFormat(request.getOutputFormat())));
    }

    /**
     * 受理前核验底图：A07 读取（无权与不存在同语义）+ 声明与真实内容一致 + 白名单格式与像素上限。
     *
     * <p>外发的引用只用**核验后的事实**（服务端判定的 MIME/字节数/摘要），不回填调用方声明，
     * 避免"声明 1024x1024 实际是别的东西"这类不一致进入任务行。
     */
    private MediaFileRef verifySource(AiImageEditDTO request) {
        imageGuard.requireDeclaredMetadata(
                request.getSourceFileId(), request.getSourceMime(), request.getSourceSizeBytes());
        String declaredSha256 =
                imageGuard.normalizeDeclaredSha256(request.getSourceSha256()).orElse(null);
        byte[] content = fileService.read(request.getSourceFileId());
        AiVisionImageOutput verified =
                imageGuard.verifyInput(request.getSourceMime(), request.getSourceSizeBytes(), declaredSha256, content);
        return new MediaFileRef(
                request.getSourceFileId(), verified.format().mimeType(), verified.sizeBytes(), verified.sha256());
    }
}
