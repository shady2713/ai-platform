package com.basicframework.module.ai.service.speech;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_OUTPUT_EMPTY;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.ai.core.model.media.MediaFileRef;
import com.basicframework.framework.ai.core.model.media.SpeechSynthesisRequest;
import com.basicframework.framework.ai.core.model.media.SpeechSynthesisResponse;
import com.basicframework.framework.ai.core.model.media.SpeechTranscriptionRequest;
import com.basicframework.framework.ai.core.model.media.SpeechTranscriptionResponse;
import com.basicframework.module.ai.adapter.model.AiMediaCapabilityGate;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaAssetDO;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.dal.mysql.media.AiMediaAssetMapper;
import com.basicframework.module.ai.service.file.AiFileBusinessType;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.media.AiMediaStepExecutor;
import com.basicframework.module.ai.service.media.dto.AiMediaStepOutcome;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskLeaseDTO;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 非实时语音执行器（X04）：准入 → 调用 → **结果核验** → 落平台私有文件 → 记产物与用量。
 *
 * <p>与图片执行器（X03）共享全部任务语义（受理、幂等、租约、终态、用量），只有"这一次调用怎么发、
 * 结果怎么验"不同。三条不伪装：
 * <ol>
 *   <li><b>不读上游地址</b>：协议里没有下载地址字段，本类也不持有任何出网客户端；</li>
 *   <li><b>结果先验后存</b>：转写必须是**非空文本**（全文），落成平台私有文本文件
 *       （{@code text/plain}，UTF-8）；合成的字节必须是白名单格式的真实音频且在字节/时长上限内
 *       （{@link AiSpeechAudioGuard#verifyOutput}），否则整笔按上游失败语义失败，不落半段音频；</li>
 *   <li><b>转写执行期先读源音频</b>：按当前主体再读一次（A07 业务 ACL）。受理时能读、执行时已失权/解除引用，
 *       一律以 {@code AI_RESOURCE_NOT_FOUND} 失败，不进入上游调用。</li>
 * </ol>
 *
 * <p>用量只落上游真实计数：上游没给就记 UNKNOWN 且数值为空，不用音频时长/字符数冒充"上游计量"。
 * 转写的分段字幕（{@code SpeechTranscriptionResponse.segments}）本切片不落库：任务行只保存全文文件，
 * 分段展示属后续前端切片（登记在多模态端点准入矩阵的未验证清单里）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiSpeechStepExecutor implements AiMediaStepExecutor {

    /** 转写全文的私有文件名前缀（不含上游返回的任何字符串）。 */
    static final String TRANSCRIPT_FILE_NAME_PREFIX = "ai-transcript-";

    /** 合成音频的私有文件名前缀与后缀由服务端判定出的格式给出。 */
    static final String AUDIO_FILE_NAME_PREFIX = "ai-speech-";

    /** 转写全文的私有文件 MIME（平台自有的文本交付形式，不是上游声明的类型）。 */
    static final String TRANSCRIPT_MIME_TYPE = "text/plain";

    /** 上游按 token 计量时的平台单位（缺失时不写数值也不写单位）。 */
    static final String USAGE_UNIT_TOKEN = "TOKEN";

    private final AiMediaCapabilityGate mediaGate;

    private final AiFileService fileService;

    private final AiSpeechAudioGuard audioGuard;

    private final AiMediaAssetMapper assetMapper;

    @Override
    public boolean supports(String operation) {
        return AiMediaTaskDO.OPERATION_TRANSCRIBE.equals(operation)
                || AiMediaTaskDO.OPERATION_SYNTHESIZE.equals(operation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMediaStepOutcome execute(AiMediaTaskLeaseDTO lease, AiMediaTaskDO task) {
        ModelCapability capability = capabilityOf(task);
        // 1) 准入：端点启用 + 能力已声明 + 探测已确认；未通过不发生任何调用
        mediaGate.assertAdmitted(task.getEndpointId(), capability);
        return switch (task.getOperation()) {
            case AiMediaTaskDO.OPERATION_TRANSCRIBE -> transcribe(task, capability);
            case AiMediaTaskDO.OPERATION_SYNTHESIZE -> synthesize(task, capability);
            default -> throw exception(AI_STATE_CONFLICT);
        };
    }

    /** 转写：执行期重读源音频 → 端口转写 → 全文落私有文本文件。 */
    private AiMediaStepOutcome transcribe(AiMediaTaskDO task, ModelCapability capability) {
        SpeechTranscriptionResponse result = mediaGate.invoke(
                task.getEndpointId(),
                capability,
                port -> port.transcribeSpeech(new SpeechTranscriptionRequest(
                        task.getModelRef(), sourceRef(task), task.getLanguageHint(), null)));
        String text = result.text();
        if (!StringUtils.hasText(text)) {
            // 端口契约已拒绝空白全文，这里兜底：不落空文件、不谎报成功
            throw exception(AI_MEDIA_OUTPUT_EMPTY);
        }
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        Long fileId = fileService
                .upload(
                        AiFileBusinessType.MEDIA_TASK.code(),
                        String.valueOf(task.getId()),
                        TRANSCRIPT_FILE_NAME_PREFIX + task.getId() + ".txt",
                        TRANSCRIPT_MIME_TYPE,
                        content)
                .getFileId();
        assetMapper.insert(new AiMediaAssetDO()
                .setTaskId(task.getId())
                .setOrdinal(1)
                .setFileId(fileId)
                .setMimeType(TRANSCRIPT_MIME_TYPE)
                .setSizeBytes((long) content.length)
                .setSha256(AiSpeechAudioGuard.sha256Hex(content))
                .setWidth(null)
                .setHeight(null)
                .setDurationMillis(null)
                .setVersion(0));
        return AiMediaStepOutcome.succeeded(
                1, usageUnitOf(result.usage()), usageQuantityOf(result.usage()), usageSourceOf(result.usage()));
    }

    /** 合成：端口返回音频字节 → 核验（格式/文件头/字节/时长）→ 落私有音频文件。 */
    private AiMediaStepOutcome synthesize(AiMediaTaskDO task, ModelCapability capability) {
        SpeechSynthesisResponse result = mediaGate.invoke(
                task.getEndpointId(),
                capability,
                port -> port.synthesizeSpeech(new SpeechSynthesisRequest(
                        task.getModelRef(), task.getInputText(), task.getVoice(), task.getOutputFormat(), null)));
        AiSpeechAudioVerified verified = audioGuard.verifyOutput(
                result.audio().mimeType(),
                result.audio().content(),
                result.audio().durationMillis(),
                task.getOutputFormat());
        Long fileId = fileService
                .upload(
                        AiFileBusinessType.MEDIA_TASK.code(),
                        String.valueOf(task.getId()),
                        AUDIO_FILE_NAME_PREFIX + task.getId() + "-1."
                                + verified.format().formatName(),
                        verified.format().mimeType(),
                        result.audio().content())
                .getFileId();
        assetMapper.insert(new AiMediaAssetDO()
                .setTaskId(task.getId())
                .setOrdinal(1)
                .setFileId(fileId)
                .setMimeType(verified.format().mimeType())
                .setSizeBytes(verified.sizeBytes())
                .setSha256(verified.sha256())
                .setWidth(null)
                .setHeight(null)
                .setDurationMillis(verified.durationMillis())
                .setVersion(0));
        return AiMediaStepOutcome.succeeded(
                1, usageUnitOf(result.usage()), usageQuantityOf(result.usage()), usageSourceOf(result.usage()));
    }

    /**
     * 转写源音频引用：执行期按当前主体读一次（A07），失权/解除引用在这里失败，不进入上游调用。
     *
     * <p>引用只带编号与声明级元数据（MIME/字节数/摘要），不带字节、不带地址；端口按编号取字节
     * （当前适配器尚未实现该接缝，见多模态端点准入矩阵的未验证清单：STT 运行期调用保持"能力未开通"拒绝）。
     */
    private MediaFileRef sourceRef(AiMediaTaskDO task) {
        if (task.getSourceFileId() == null) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        byte[] content = fileService.read(task.getSourceFileId());
        if (content == null || content.length == 0) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        return new MediaFileRef(
                task.getSourceFileId(),
                task.getSourceMime(),
                content.length,
                task.getSourceSha256() == null || task.getSourceSha256().isBlank() ? null : task.getSourceSha256());
    }

    /** 能力取自任务行（受理时固定）：转写只认 SPEECH_TO_TEXT，合成只认 TEXT_TO_SPEECH。 */
    private static ModelCapability capabilityOf(AiMediaTaskDO task) {
        ModelCapability capability = ModelCapability.valueOf(task.getCapability());
        if (!capability.isMedia()) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        boolean matches =
                switch (task.getOperation()) {
                    case AiMediaTaskDO.OPERATION_TRANSCRIBE -> ModelCapability.SPEECH_TO_TEXT.equals(capability);
                    case AiMediaTaskDO.OPERATION_SYNTHESIZE -> ModelCapability.TEXT_TO_SPEECH.equals(capability);
                    default -> false;
                };
        if (!matches) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        return capability;
    }

    /** 用量来源：上游给了计数记 REPORTED，否则 UNKNOWN（不写 0）。 */
    private static String usageSourceOf(ModelUsage usage) {
        return usage != null && usage.isKnown()
                ? AiMediaTaskDO.USAGE_SOURCE_REPORTED
                : AiMediaTaskDO.USAGE_SOURCE_UNKNOWN;
    }

    /** 上游用量到平台计量的映射（语音与图片同口径：上游按 token 报数时单位为 TOKEN；缺失时数值为空）。 */
    static String usageUnitOf(ModelUsage usage) {
        return usageQuantityOf(usage) == null ? null : USAGE_UNIT_TOKEN;
    }

    static Long usageQuantityOf(ModelUsage usage) {
        if (usage == null) {
            return null;
        }
        Integer total = usage.totalTokens();
        return total == null ? null : total.longValue();
    }
}
