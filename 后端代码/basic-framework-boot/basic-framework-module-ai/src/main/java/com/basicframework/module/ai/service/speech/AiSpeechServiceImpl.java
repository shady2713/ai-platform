package com.basicframework.module.ai.service.speech;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.media.MediaFileRef;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.media.AiMediaTaskService;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskResultDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskSubmitDTO;
import com.basicframework.module.ai.service.speech.dto.AiSpeechSynthesizeDTO;
import com.basicframework.module.ai.service.speech.dto.AiSpeechTranscribeDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 非实时语音实现（X04）：收窄参数 → （转写）核验音频 → 幂等受理。
 *
 * <p>为什么受理前要核验音频：任务一旦受理就可能在几分钟后才执行，若受理时不核验，
 * 无权/伪装的音频会先进入任务表，直到执行期才失败——调用方拿到的是"排队中"的假象。
 * 这里在受理前按当前主体读一次音频并核验（A07 业务 ACL + 声明/内容/格式核对），
 * 执行期还会再读一次（受理时能读、执行时失权同样拒绝）。
 *
 * <p>外发引用只用**核验后的事实**（服务端判定的 MIME/字节数/摘要），不回填调用方声明，
 * 避免"声明 wav 实际是别的东西"这类不一致进入任务行。
 */
@Service
@RequiredArgsConstructor
public class AiSpeechServiceImpl implements AiSpeechService {

    private final AiSpeechParams params;

    private final AiSpeechAudioGuard audioGuard;

    private final AiMediaTaskService taskService;

    private final AiFileService fileService;

    @Override
    public AiMediaTaskResultDTO transcribe(AiSpeechTranscribeDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("转写请求不能为空");
        }
        String requestKey = params.requireRequestKey(request.getRequestKey());
        String languageHint = params.normalizeLanguageHint(request.getLanguageHint());
        params.requireAudioDeclaredMetadata(
                request.getSourceFileId(),
                request.getSourceMime(),
                request.getSourceSizeBytes(),
                request.getSourceDurationMillis());
        String declaredSha256 =
                audioGuard.normalizeDeclaredSha256(request.getSourceSha256()).orElse(null);
        // 受理前按当前主体读一次音频：无权/解除引用在 A07 处失败，伪装内容在核验处失败
        byte[] content = fileService.read(request.getSourceFileId());
        AiSpeechAudioVerified verified =
                audioGuard.verifyInput(request.getSourceMime(), request.getSourceSizeBytes(), declaredSha256, content);
        MediaFileRef source = new MediaFileRef(
                request.getSourceFileId(), verified.format().mimeType(), verified.sizeBytes(), verified.sha256());
        return taskService.submit(new AiMediaTaskSubmitDTO()
                .setRequestKey(requestKey)
                .setMediaKind(AiMediaTaskDO.KIND_AUDIO)
                .setOperation(AiMediaTaskDO.OPERATION_TRANSCRIBE)
                .setCapability(ModelCapability.SPEECH_TO_TEXT.name())
                .setEndpointId(request.getEndpointId())
                .setLanguageHint(languageHint)
                .setSourceFileId(source.fileId())
                .setSourceMime(source.mimeType())
                .setSourceSizeBytes(source.sizeBytes())
                .setSourceSha256(source.sha256())
                .setOutputCount(1));
    }

    @Override
    public AiMediaTaskResultDTO synthesize(AiSpeechSynthesizeDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("合成请求不能为空");
        }
        String requestKey = params.requireRequestKey(request.getRequestKey());
        String text = params.requireText(request.getText());
        String voice = params.normalizeVoice(request.getVoice());
        String outputFormat = params.normalizeOutputFormat(request.getOutputFormat());
        return taskService.submit(new AiMediaTaskSubmitDTO()
                .setRequestKey(requestKey)
                .setMediaKind(AiMediaTaskDO.KIND_AUDIO)
                .setOperation(AiMediaTaskDO.OPERATION_SYNTHESIZE)
                .setCapability(ModelCapability.TEXT_TO_SPEECH.name())
                .setEndpointId(request.getEndpointId())
                .setInputText(text)
                .setVoice(voice)
                .setOutputCount(1)
                .setOutputFormat(outputFormat));
    }
}
