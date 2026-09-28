package com.basicframework.module.ai.controller.app.v1.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.image.vo.AiMediaAssetRespVO;
import com.basicframework.module.ai.controller.app.v1.image.vo.AiMediaTaskRespVO;
import com.basicframework.module.ai.controller.app.v1.image.vo.AiMediaUsageRespVO;
import com.basicframework.module.ai.controller.app.v1.speech.vo.AiSpeechAudioRefVO;
import com.basicframework.module.ai.controller.app.v1.speech.vo.AiSpeechSynthesizeReqVO;
import com.basicframework.module.ai.controller.app.v1.speech.vo.AiSpeechTranscribeReqVO;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.service.media.AiMediaTaskService;
import com.basicframework.module.ai.service.media.dto.AiMediaAssetDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskResultDTO;
import com.basicframework.module.ai.service.speech.AiSpeechService;
import com.basicframework.module.ai.service.speech.dto.AiSpeechSynthesizeDTO;
import com.basicframework.module.ai.service.speech.dto.AiSpeechTranscribeDTO;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 应用端语音接口契约（X04）：5 个端点都要求已认证主体；请求字段原样委派
 * （音频只传私有文件编号 + 声明级元数据）；响应只暴露平台事实（状态/稳定失败码/产物文件编号/用量），
 * 协议里不存在任何上游地址字段。
 */
class AiSpeechControllerTest {

    private final AiSpeechService speechService = mock(AiSpeechService.class);

    private final AiMediaTaskService taskService = mock(AiMediaTaskService.class);

    private final AiSpeechController controller = new AiSpeechController(speechService, taskService);

    @Test
    void transcribeDelegatesPrivateAudioReferenceAndMapsTaskFacts() {
        when(speechService.transcribe(any())).thenReturn(taskResult());

        AiMediaTaskRespVO respVO = controller
                .transcribe(new AiSpeechTranscribeReqVO()
                        .setRequestKey("stt-1")
                        .setEndpointId(7L)
                        .setAudio(new AiSpeechAudioRefVO()
                                .setFileId(88L)
                                .setMime("audio/wav")
                                .setSize(4096L)
                                .setSha256("b".repeat(64))
                                .setDurationMs(8_000L))
                        .setLanguageHint("zh-CN"))
                .getData();

        ArgumentCaptor<AiSpeechTranscribeDTO> captor = ArgumentCaptor.forClass(AiSpeechTranscribeDTO.class);
        verify(speechService).transcribe(captor.capture());
        AiSpeechTranscribeDTO request = captor.getValue();
        assertThat(request.getRequestKey()).isEqualTo("stt-1");
        assertThat(request.getEndpointId()).isEqualTo(7L);
        assertThat(request.getSourceFileId()).isEqualTo(88L);
        assertThat(request.getSourceMime()).isEqualTo("audio/wav");
        assertThat(request.getSourceSizeBytes()).isEqualTo(4096L);
        assertThat(request.getSourceSha256()).isEqualTo("b".repeat(64));
        assertThat(request.getSourceDurationMillis()).isEqualTo(8_000L);
        assertThat(request.getLanguageHint()).isEqualTo("zh-CN");

        assertThat(respVO.getId()).isEqualTo(512L);
        assertThat(respVO.getMediaKind()).isEqualTo(AiMediaTaskDO.KIND_AUDIO);
        assertThat(respVO.getOperation()).isEqualTo(AiMediaTaskDO.OPERATION_TRANSCRIBE);
        assertThat(respVO.getCapability()).isEqualTo("SPEECH_TO_TEXT");
        assertThat(respVO.getStatus()).isEqualTo(AiMediaTaskDO.STATUS_SUCCEEDED);
        assertThat(respVO.getSourceFileId()).isEqualTo(88L);
        assertThat(respVO.getLanguageHint()).isEqualTo("zh-CN");
        assertThat(respVO.getOutputFormat()).isNull();
        assertThat(respVO.getResultCount()).isEqualTo(1);
        assertThat(respVO.getUsage().getSource()).isEqualTo("REPORTED");
        assertThat(respVO.getUsage().getQuantity()).isEqualTo(70L);
        assertThat(respVO.getAssets()).hasSize(1);
        AiMediaAssetRespVO asset = respVO.getAssets().get(0);
        assertThat(asset.getFileId()).isEqualTo(2048L);
        assertThat(asset.getMimeType()).isEqualTo("text/plain");
        assertThat(asset.getDurationMillis()).isNull();
        assertThat(asset.getWidth()).isNull();
    }

    @Test
    void synthesizeDelegatesTextVoiceAndFormat() {
        when(speechService.synthesize(any()))
                .thenReturn(taskResult()
                        .setOperation(AiMediaTaskDO.OPERATION_SYNTHESIZE)
                        .setCapability("TEXT_TO_SPEECH")
                        .setInputText("欢迎使用中台")
                        .setVoice("Alloy")
                        .setOutputFormat("mp3")
                        .withAssets(List.of(new AiMediaAssetDTO()
                                .setFileId(2049L)
                                .setOrdinal(1)
                                .setMimeType("audio/mpeg")
                                .setSizeBytes(8192L)
                                .setSha256("c".repeat(64))
                                .setDurationMillis(1_500L))));

        AiMediaTaskRespVO respVO = controller
                .synthesize(new AiSpeechSynthesizeReqVO()
                        .setRequestKey("tts-1")
                        .setEndpointId(7L)
                        .setText("欢迎使用中台")
                        .setVoice("Alloy")
                        .setOutputFormat("mp3"))
                .getData();

        ArgumentCaptor<AiSpeechSynthesizeDTO> captor = ArgumentCaptor.forClass(AiSpeechSynthesizeDTO.class);
        verify(speechService).synthesize(captor.capture());
        AiSpeechSynthesizeDTO request = captor.getValue();
        assertThat(request.getRequestKey()).isEqualTo("tts-1");
        assertThat(request.getEndpointId()).isEqualTo(7L);
        assertThat(request.getText()).isEqualTo("欢迎使用中台");
        assertThat(request.getVoice()).isEqualTo("Alloy");
        assertThat(request.getOutputFormat()).isEqualTo("mp3");

        assertThat(respVO.getOperation()).isEqualTo(AiMediaTaskDO.OPERATION_SYNTHESIZE);
        assertThat(respVO.getInputText()).isEqualTo("欢迎使用中台");
        assertThat(respVO.getVoice()).isEqualTo("Alloy");
        assertThat(respVO.getOutputFormat()).isEqualTo("mp3");
        assertThat(respVO.getAssets().get(0).getMimeType()).isEqualTo("audio/mpeg");
        assertThat(respVO.getAssets().get(0).getDurationMillis()).isEqualTo(1_500L);
    }

    @Test
    void getTaskDelegatesToTheMediaTaskService() {
        when(taskService.getTask(512L)).thenReturn(taskResult());

        AiMediaTaskRespVO respVO = controller.getTask(512L).getData();

        verify(taskService).getTask(512L);
        assertThat(respVO.getId()).isEqualTo(512L);
        assertThat(respVO.getAssets()).hasSize(1);
    }

    @Test
    void getTaskPageScopesBySubjectAndPassesFiltersThrough() {
        when(taskService.getTaskPage(any(PageParam.class), eq("AUDIO"), eq("QUEUED")))
                .thenReturn(new PageResult<>(
                        List.of(
                                taskResult().setId(1L).setStatus(AiMediaTaskDO.STATUS_QUEUED),
                                taskResult().setId(2L).setStatus(AiMediaTaskDO.STATUS_QUEUED)),
                        2L));

        PageResult<AiMediaTaskRespVO> page = controller
                .getTaskPage(new PageParam().setPageNo(2).setPageSize(5), "AUDIO", "QUEUED")
                .getData();

        ArgumentCaptor<PageParam> pageParam = ArgumentCaptor.forClass(PageParam.class);
        verify(taskService).getTaskPage(pageParam.capture(), eq("AUDIO"), eq("QUEUED"));
        assertThat(pageParam.getValue().getPageNo()).isEqualTo(2);
        assertThat(pageParam.getValue().getPageSize()).isEqualTo(5);
        assertThat(page.getTotal()).isEqualTo(2L);
        assertThat(page.getList()).extracting(AiMediaTaskRespVO::getId).containsExactly(1L, 2L);
    }

    @Test
    void cancelDelegatesAndReturnsCancelledFacts() {
        when(taskService.cancel(512L))
                .thenReturn(new AiMediaTaskResultDTO().setId(512L).setStatus(AiMediaTaskDO.STATUS_CANCELLED));

        AiMediaTaskRespVO respVO = controller.cancel(512L).getData();

        verify(taskService).cancel(512L);
        assertThat(respVO.getStatus()).isEqualTo(AiMediaTaskDO.STATUS_CANCELLED);
    }

    @Test
    void everyEndpointRequiresAuthenticatedSubject() {
        for (String methodName : new String[] {"transcribe", "synthesize", "getTask", "getTaskPage", "cancel"}) {
            Method method = findMethod(methodName);
            assertThat(method.getAnnotation(AuthenticatedOnly.class))
                    .as("%s 必须要求已认证主体（不接受匿名访问）", methodName)
                    .isNotNull();
        }
    }

    @Test
    void taskAndAudioVosExposeNoAddressLikeFields() {
        assertNoAddressFields(AiMediaTaskRespVO.class);
        assertNoAddressFields(AiMediaAssetRespVO.class);
        assertNoAddressFields(AiMediaUsageRespVO.class);
        assertNoAddressFields(AiSpeechAudioRefVO.class);
        assertNoAddressFields(AiSpeechTranscribeReqVO.class);
        assertNoAddressFields(AiSpeechSynthesizeReqVO.class);
    }

    private static AiMediaTaskResultDTO taskResult() {
        return new AiMediaTaskResultDTO()
                .setId(512L)
                .setRequestKey("stt-1")
                .setMediaKind(AiMediaTaskDO.KIND_AUDIO)
                .setOperation(AiMediaTaskDO.OPERATION_TRANSCRIBE)
                .setCapability("SPEECH_TO_TEXT")
                .setStatus(AiMediaTaskDO.STATUS_SUCCEEDED)
                .setSourceFileId(88L)
                .setLanguageHint("zh-CN")
                .setEndpointId(7L)
                .setOutputCount(1)
                .setResultCount(1)
                .setUsageUnit("TOKEN")
                .setUsageQuantity(70L)
                .setUsageSource("REPORTED")
                .setCreateTime(LocalDateTime.of(2026, 1, 2, 3, 4, 5))
                .withAssets(List.of(new AiMediaAssetDTO()
                        .setFileId(2048L)
                        .setOrdinal(1)
                        .setMimeType("text/plain")
                        .setSizeBytes(64L)
                        .setSha256("a".repeat(64))));
    }

    private static Method findMethod(String name) {
        for (Method method : AiSpeechController.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new IllegalStateException("未找到方法：" + name);
    }

    /** 协议里不存在任何"地址"字段：音频读取一律走受控文件接口，不接受上游临时链接。 */
    private static void assertNoAddressFields(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String name = field.getName().toLowerCase(Locale.ROOT);
            assertThat(name)
                    .as("%s.%s 不得是地址类字段", type.getSimpleName(), field.getName())
                    .doesNotContain("url")
                    .doesNotContain("link")
                    .doesNotContain("href");
            assertThat(field.getType())
                    .as("%s.%s 不得使用地址类型", type.getSimpleName(), field.getName())
                    .isNotEqualTo(java.net.URL.class)
                    .isNotEqualTo(java.net.URI.class);
        }
    }
}
