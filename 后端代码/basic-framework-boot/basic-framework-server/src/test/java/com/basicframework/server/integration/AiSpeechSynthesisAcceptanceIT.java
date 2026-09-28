package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.media.SpeechSynthesisRequest;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskResultDTO;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import com.basicframework.module.ai.service.speech.dto.AiSpeechSynthesizeDTO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 非实时语音验收（X04）：合成链路与"音频产物受控上传白名单"的当前事实。
 *
 * <p>夹具体系见 {@link AiSpeechAcceptanceSupport}：Quartz 关闭、任务由测试显式驱动。
 * 合成产物无法落私有文件的原因（infra 白名单不含音频后缀）由用例钉住并登记为跨卡缺陷。
 */
class AiSpeechSynthesisAcceptanceIT extends AiSpeechAcceptanceSupport {

    @Test
    void synthesizeCannotStoreArtifactWhileInfraWhitelistLacksAudio() {
        AiMediaTaskResultDTO accepted = speechService.synthesize(new AiSpeechSynthesizeDTO()
                .setRequestKey("it-tts-0002")
                .setEndpointId(speechEndpointId)
                .setText("it 欢迎使用中台")
                .setVoice("Alloy")
                .setOutputFormat("mp3"));

        // 受理即固定文本/音色/格式（生成类没有源文件与语言提示）
        Map<String, Object> queued = taskRow(accepted.getId());
        assertThat(queued.get("media_kind")).isEqualTo(AiMediaTaskDO.KIND_AUDIO);
        assertThat(queued.get("operation")).isEqualTo(AiMediaTaskDO.OPERATION_SYNTHESIZE);
        assertThat(queued.get("capability")).isEqualTo(ModelCapability.TEXT_TO_SPEECH.name());
        assertThat(queued.get("input_text")).isEqualTo("it 欢迎使用中台");
        assertThat(queued.get("voice")).isEqualTo("Alloy");
        assertThat(queued.get("output_format")).isEqualTo("mp3");
        assertThat(queued.get("language_hint")).as("合成没有语言提示").isNull();
        assertThat(queued.get("source_file_id")).isNull();

        assertThat(mediaTaskJob.execute(null)).isEqualTo("recovered=0,claimed=1,succeeded=0,failed=1");

        // 上游调用确实发生，端口收到受理时固定的文本/音色/格式
        assertThat(SpeechTestConfiguration.SYNTHESIZE_CALLS.get()).isEqualTo(1);
        SpeechSynthesisRequest portRequest = SpeechTestConfiguration.LAST_SYNTHESIS.get();
        assertThat(portRequest.modelId()).isEqualTo(MODEL_ID);
        assertThat(portRequest.text()).isEqualTo("it 欢迎使用中台");
        assertThat(portRequest.voice()).isEqualTo("Alloy");
        assertThat(portRequest.outputFormat()).isEqualTo("mp3");

        // 产物落盘被 infra 白名单拒绝（登记缺口）：失败只落稳定原因码，不留产物行与半成品文件/引用。
        // infra 错误码（1_001_003_003）不在 AI 平台错误码区间，任务 Job 按其既有语义收敛为稳定原因码
        // execution-failed（不把非本域的码当平台码转存，也不回传上游正文）。
        Map<String, Object> row = taskRow(accepted.getId());
        assertThat(row.get("status")).isEqualTo(AiMediaTaskDO.STATUS_FAILED);
        assertThat(row.get("failure_code"))
                .as("infra 受控上传白名单不含音频后缀，产物无法落库：任务以稳定原因码收尾")
                .isEqualTo("execution-failed");
        assertThat(row.get("result_count")).isEqualTo(0);
        assertThat(assetRows(accepted.getId())).as("不得留下半个产物").isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_file_binding WHERE business_type = 'ai_media_task' AND business_key = ?",
                        Long.class,
                        String.valueOf(accepted.getId())))
                .as("失败的产物不得留下私有文件引用")
                .isZero();
    }

    @Test
    void nonAudioArtifactFailsTaskWithMediaOutputInvalidAndLeavesNoAssetOrPrivateFile() {
        SpeechTestConfiguration.HTML_ARTIFACT.set(true);
        long filesBefore = countInfraFiles();

        AiMediaTaskResultDTO accepted = speechService.synthesize(synthesizeRequest("it-tts-0003"));

        assertThat(mediaTaskJob.execute(null)).isEqualTo("recovered=0,claimed=1,succeeded=0,failed=1");

        Map<String, Object> row = taskRow(accepted.getId());
        assertThat(row.get("status")).isEqualTo(AiMediaTaskDO.STATUS_FAILED);
        assertThat(row.get("failure_code"))
                .as("产物不合规必须落平台错误码（1_003_010_005 的十进制形式）")
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID.getCode()));
        assertThat(row.get("result_count")).isEqualTo(0);
        assertThat(assetRows(accepted.getId())).as("不落半段音频").isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_file_binding WHERE business_type = 'ai_media_task' AND business_key = ?",
                        Long.class,
                        String.valueOf(accepted.getId())))
                .as("失败的产物不得留下私有文件引用")
                .isZero();
        assertThat(countInfraFiles()).as("不得留下孤儿文件").isEqualTo(filesBefore);
    }

    @Test
    void overlongAudioArtifactFailsWithOutputDurationCode() {
        SpeechTestConfiguration.ARTIFACT_DURATION.set(1_200_001L);

        AiMediaTaskResultDTO accepted = speechService.synthesize(synthesizeRequest("it-tts-0004"));

        assertThat(mediaTaskJob.execute(null)).isEqualTo("recovered=0,claimed=1,succeeded=0,failed=1");

        Map<String, Object> row = taskRow(accepted.getId());
        assertThat(row.get("status")).isEqualTo(AiMediaTaskDO.STATUS_FAILED);
        assertThat(row.get("failure_code"))
                .as("时长超限按 1_003_010_006 失败")
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_MEDIA_OUTPUT_DURATION_EXCEEDED.getCode()));
        assertThat(assetRows(accepted.getId())).isEmpty();
    }

    @Test
    void capabilityNotDeclaredIsRejectedAtExecutionTimeWithoutOutbound() {
        // 端点只声明文本能力：受理阶段照常固定端点，执行期在准入处零外发失败
        AiModelEndpointSaveDTO textOnly = new AiModelEndpointSaveDTO();
        textOnly.setName("it-speech-text-only");
        textOnly.setProvider("openai_compatible");
        textOnly.setBaseUrl("https://it-speech.example.com/v1");
        textOnly.setModelId(MODEL_ID);
        textOnly.setCapabilities(List.of(ModelCapability.TEXT.name()));
        textOnly.setCredential("sk-it-speech-text");
        Long textOnlyEndpointId = endpointService.createEndpoint(textOnly);
        AiModelEndpointDO created = endpointService.getEndpoint(textOnlyEndpointId);
        endpointService.updateEndpointStatus(textOnlyEndpointId, created.getVersion(), true);
        probeService.probeAll(textOnlyEndpointId);

        AiMediaTaskResultDTO accepted = speechService.synthesize(new AiSpeechSynthesizeDTO()
                .setRequestKey("it-tts-0005")
                .setEndpointId(textOnlyEndpointId)
                .setText("it 不应外发")
                .setOutputFormat("mp3"));

        assertThat(mediaTaskJob.execute(null)).isEqualTo("recovered=0,claimed=1,succeeded=0,failed=1");
        Map<String, Object> row = taskRow(accepted.getId());
        assertThat(row.get("status")).isEqualTo(AiMediaTaskDO.STATUS_FAILED);
        assertThat(row.get("failure_code"))
                .as("能力未开通在闸门处被拒绝（1_003_002_007 的十进制形式）")
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_MODEL_CAPABILITY_NOT_ENABLED.getCode()));
        assertThat(SpeechTestConfiguration.SYNTHESIZE_CALLS.get())
                .as("能力未声明不得外发")
                .isZero();
        assertThat(assetRows(accepted.getId())).isEmpty();
    }

    @Test
    void audioUploadThroughControlledFileEndpointIsRejectedByCurrentInfraWhitelist() {
        // 登记的产品缺口：受控上传白名单（FileTypeUtils.ALLOWED_EXTENSIONS）不含音频后缀，
        // 真实客户端目前无法把录音上传成平台私有音频（登记在多模态端点准入矩阵的未验证清单里）。
        assertThatThrownBy(
                        () -> fileService.upload("ai_chat_session", "session-alice", "device.wav", "audio/wav", wav()))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(FILE_TYPE_NOT_ALLOWED_CODE));
        assertThatThrownBy(() ->
                        fileService.upload("ai_chat_session", "session-alice", "device.webm", "audio/webm", wav()))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(FILE_TYPE_NOT_ALLOWED_CODE));
    }
}
