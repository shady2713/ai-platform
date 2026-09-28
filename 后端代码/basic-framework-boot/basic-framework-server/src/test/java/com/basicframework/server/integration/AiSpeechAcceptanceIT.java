package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.ai.core.model.media.SpeechTranscriptionRequest;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskResultDTO;
import com.basicframework.module.ai.service.speech.dto.AiSpeechTranscribeDTO;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 非实时语音验收（X04）：转写链路（受理 → 常驻 Job 执行 → 产物可按 A07 读回）。
 *
 * <p>夹具体系见 {@link AiSpeechAcceptanceSupport}：Quartz 关闭、任务由测试显式驱动，
 * 源音频用平台上已有的私有音频夹具（读取仍走 A07 全链路）。
 */
class AiSpeechAcceptanceIT extends AiSpeechAcceptanceSupport {

    @Test
    void transcribeAcceptsQueuedTaskThenJobStoresTranscriptReadableThroughFileService() {
        byte[] source = wav();
        Long sourceFileId = insertPrivateAudioFixture("it-stt-source.wav", source);
        Integer expectedConfigRevision =
                endpointService.getEndpoint(speechEndpointId).getConfigRevision();

        AiMediaTaskResultDTO accepted = speechService.transcribe(new AiSpeechTranscribeDTO()
                .setRequestKey("it-stt-0001")
                .setEndpointId(speechEndpointId)
                .setSourceFileId(sourceFileId)
                .setSourceMime("audio/wav")
                .setSourceSizeBytes((long) source.length)
                .setSourceSha256(sha256(source))
                .setSourceDurationMillis(8_000L)
                .setLanguageHint("zh-CN"));

        // 受理即落库：QUEUED、固定端点与配置版本、语言提示、用量未知且不写 0
        Map<String, Object> queued = taskRow(accepted.getId());
        assertThat(queued.get("status")).isEqualTo(AiMediaTaskDO.STATUS_QUEUED);
        assertThat(queued.get("media_kind")).isEqualTo(AiMediaTaskDO.KIND_AUDIO);
        assertThat(queued.get("operation")).isEqualTo(AiMediaTaskDO.OPERATION_TRANSCRIBE);
        assertThat(queued.get("capability")).isEqualTo(ModelCapability.SPEECH_TO_TEXT.name());
        assertThat(queued.get("endpoint_id")).isEqualTo(speechEndpointId);
        assertThat(queued.get("endpoint_config_revision")).isEqualTo(expectedConfigRevision);
        assertThat(queued.get("model_ref")).isEqualTo(MODEL_ID);
        assertThat(queued.get("language_hint")).isEqualTo("zh-CN");
        assertThat(queued.get("voice")).as("转写没有音色").isNull();
        assertThat(queued.get("output_format")).as("转写没有输出格式（不得继承列默认值）").isNull();
        assertThat(queued.get("source_file_id")).isEqualTo(sourceFileId);
        assertThat(queued.get("source_mime")).isEqualTo("audio/wav");
        assertThat(queued.get("source_size_bytes")).isEqualTo((long) source.length);
        assertThat(queued.get("source_sha256")).isEqualTo(sha256(source));
        assertThat(queued.get("result_count")).isEqualTo(0);
        assertThat(queued.get("usage_source")).isEqualTo(AiMediaTaskDO.USAGE_SOURCE_UNKNOWN);
        assertThat(queued.get("usage_quantity")).as("上游尚未调用时不得写 0").isNull();
        assertThat(assetRows(accepted.getId())).isEmpty();

        assertThat(mediaTaskJob.execute(null)).isEqualTo("recovered=0,claimed=1,succeeded=1,failed=0");

        Map<String, Object> succeeded = taskRow(accepted.getId());
        assertThat(succeeded.get("status")).isEqualTo(AiMediaTaskDO.STATUS_SUCCEEDED);
        assertThat(succeeded.get("failure_code")).isNull();
        assertThat(succeeded.get("result_count")).isEqualTo(1);
        assertThat(succeeded.get("usage_source")).isEqualTo(AiMediaTaskDO.USAGE_SOURCE_REPORTED);
        assertThat(succeeded.get("usage_unit")).isEqualTo("TOKEN");
        assertThat(succeeded.get("usage_quantity")).isEqualTo((long) REPORTED_TOKENS);
        assertThat(SpeechTestConfiguration.TRANSCRIBE_CALLS.get()).isEqualTo(1);

        // 端口收到的是执行期重读的私有音频引用与受理时固定的语言提示
        SpeechTranscriptionRequest portRequest = SpeechTestConfiguration.LAST_TRANSCRIPTION.get();
        assertThat(portRequest.modelId()).isEqualTo(MODEL_ID);
        assertThat(portRequest.audio().fileId()).isEqualTo(sourceFileId);
        assertThat(portRequest.audio().mimeType()).isEqualTo("audio/wav");
        assertThat(portRequest.audio().sizeBytes()).isEqualTo((long) source.length);
        assertThat(portRequest.audio().sha256()).isEqualTo(sha256(source));
        assertThat(portRequest.languageHint()).isEqualTo("zh-CN");

        // 转写全文落平台私有文本文件：读回字节与平台事实一致
        List<Map<String, Object>> assets = assetRows(accepted.getId());
        assertThat(assets).hasSize(1);
        Map<String, Object> asset = assets.get(0);
        assertThat(asset.get("ordinal")).isEqualTo(1);
        assertThat(asset.get("mime_type")).isEqualTo("text/plain");
        assertThat(asset.get("duration_millis")).isNull();
        assertThat(asset.get("width")).isNull();
        assertThat(asset.get("height")).isNull();
        byte[] expected = TRANSCRIPT.getBytes(StandardCharsets.UTF_8);
        Long fileId = ((Number) asset.get("file_id")).longValue();
        assertThat(fileService.read(fileId)).as("转写全文可经受控文件接口读回").isEqualTo(expected);
        assertThat(((Number) asset.get("size_bytes")).longValue()).isEqualTo(expected.length);
        assertThat(asset.get("sha256")).isEqualTo(sha256(expected));

        // 服务层读回的任务事实与产物一致
        AiMediaTaskResultDTO fetched = taskService.getTask(accepted.getId());
        assertThat(fetched.getStatus()).isEqualTo(AiMediaTaskDO.STATUS_SUCCEEDED);
        assertThat(fetched.getLanguageHint()).isEqualTo("zh-CN");
        assertThat(fetched.getAssets()).hasSize(1);
    }

    @Test
    void repeatedTranscribeRequestKeyReturnsSameTaskAndCallsThePortOnlyOnce() {
        byte[] source = wav();
        Long sourceFileId = insertPrivateAudioFixture("it-stt-idempotent.wav", source);

        AiMediaTaskResultDTO first = speechService.transcribe(transcribeRequest("it-stt-0002", sourceFileId, source));
        AiMediaTaskResultDTO repeated =
                speechService.transcribe(transcribeRequest("it-stt-0002", sourceFileId, source));

        assertThat(repeated.getId()).as("同键重复提交返回同一任务").isEqualTo(first.getId());
        assertThat(taskCountForRequestKey("it-stt-0002")).as("同键只允许一行").isEqualTo(1L);

        assertThat(mediaTaskJob.execute(null)).isEqualTo("recovered=0,claimed=1,succeeded=1,failed=0");
        assertThat(SpeechTestConfiguration.TRANSCRIBE_CALLS.get())
                .as("一次任务只发生一次上游调用")
                .isEqualTo(1);
        assertThat(assetRows(first.getId())).hasSize(1);

        // 同键但换了语言提示：按幂等冲突拒绝，不触发第二次调用
        assertThatThrownBy(() -> speechService.transcribe(
                        transcribeRequest("it-stt-0002", sourceFileId, source).setLanguageHint("en-US")))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_IDEMPOTENCY_CONFLICT.getCode()));
        assertThat(SpeechTestConfiguration.TRANSCRIBE_CALLS.get()).isEqualTo(1);
    }

    @Test
    void transcribeWithMissingUpstreamUsageIsStoredAsUnknownWithNullQuantity() {
        byte[] source = wav();
        Long sourceFileId = insertPrivateAudioFixture("it-stt-usage-unknown.wav", source);
        SpeechTestConfiguration.USAGE.set(ModelUsage.UNKNOWN);

        AiMediaTaskResultDTO accepted =
                speechService.transcribe(transcribeRequest("it-stt-0003", sourceFileId, source));

        assertThat(mediaTaskJob.execute(null)).isEqualTo("recovered=0,claimed=1,succeeded=1,failed=0");
        Map<String, Object> row = taskRow(accepted.getId());
        assertThat(row.get("status")).isEqualTo(AiMediaTaskDO.STATUS_SUCCEEDED);
        assertThat(row.get("usage_source")).isEqualTo(AiMediaTaskDO.USAGE_SOURCE_UNKNOWN);
        assertThat(row.get("usage_unit")).isNull();
        assertThat(row.get("usage_quantity")).as("上游缺失不得用 0 冒充真实计量").isNull();
        assertThat(assetRows(accepted.getId())).hasSize(1);
    }

    @Test
    void revokedSourceIsRejectedAtAcceptTimeAndAtExecutionTime() {
        byte[] source = wav();
        Long sourceFileId = insertPrivateAudioFixture("it-stt-revoked.wav", source);

        // 1) 他人主体：A07 按不存在处理（防编号枚举），不产生任务、不外发
        loginAs("bob");
        assertCode(
                () -> speechService.transcribe(transcribeRequest("it-stt-0004a", sourceFileId, source)),
                AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);
        assertThat(taskCountForRequestKey("it-stt-0004a")).isZero();
        assertThat(SpeechTestConfiguration.TRANSCRIBE_CALLS.get()).isZero();

        // 2) 受理阶段被解除引用：受理前就拒绝，任务表里不留"排队中"的假象
        loginAs("alice");
        fileService.release(sourceFileId);
        assertCode(
                () -> speechService.transcribe(transcribeRequest("it-stt-0004b", sourceFileId, source)),
                AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);
        assertThat(taskCountForRequestKey("it-stt-0004b")).isZero();
        assertThat(SpeechTestConfiguration.TRANSCRIBE_CALLS.get()).isZero();

        // 3) 受理时能读、执行时失权：任务必须失败，且不得发生上游调用、不得落产物
        Long otherFileId = insertPrivateAudioFixture("it-stt-late-revoked.wav", source);
        AiMediaTaskResultDTO accepted =
                speechService.transcribe(transcribeRequest("it-stt-0004c", otherFileId, source));
        fileService.release(otherFileId);

        assertThat(mediaTaskJob.execute(null)).isEqualTo("recovered=0,claimed=1,succeeded=0,failed=1");
        Map<String, Object> row = taskRow(accepted.getId());
        assertThat(row.get("status")).isEqualTo(AiMediaTaskDO.STATUS_FAILED);
        assertThat(row.get("failure_code"))
                .as("执行期失权落平台错误码（1_003_001_003 的十进制形式）")
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND.getCode()));
        assertThat(assetRows(accepted.getId())).isEmpty();
        assertThat(SpeechTestConfiguration.TRANSCRIBE_CALLS.get()).isZero();
    }

    @Test
    void anotherSubjectCannotReadOrCancelTheTask() {
        AiMediaTaskResultDTO accepted = speechService.synthesize(synthesizeRequest("it-tts-0001"));

        loginAs("bob");
        assertCode(() -> taskService.getTask(accepted.getId()), AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);
        assertCode(() -> taskService.cancel(accepted.getId()), AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);
        assertCode(() -> taskService.getAssets(accepted.getId()), AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);
        assertThat(taskService.getTaskPage(new PageParam(), null, null).getList())
                .as("他人任务不出现在分页里")
                .isEmpty();

        assertThat(taskRow(accepted.getId()).get("status")).as("越权取消不得改变任务状态").isEqualTo(AiMediaTaskDO.STATUS_QUEUED);
        assertThat(SpeechTestConfiguration.SYNTHESIZE_CALLS.get()).isZero();
    }
}
