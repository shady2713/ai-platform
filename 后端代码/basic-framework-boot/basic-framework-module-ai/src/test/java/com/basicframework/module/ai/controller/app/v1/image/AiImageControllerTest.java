package com.basicframework.module.ai.controller.app.v1.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.image.vo.AiImageEditReqVO;
import com.basicframework.module.ai.controller.app.v1.image.vo.AiImageGenerateReqVO;
import com.basicframework.module.ai.controller.app.v1.image.vo.AiImageMediaRefVO;
import com.basicframework.module.ai.controller.app.v1.image.vo.AiMediaAssetRespVO;
import com.basicframework.module.ai.controller.app.v1.image.vo.AiMediaTaskRespVO;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.service.image.AiImageService;
import com.basicframework.module.ai.service.image.dto.AiImageEditDTO;
import com.basicframework.module.ai.service.image.dto.AiImageGenerateDTO;
import com.basicframework.module.ai.service.media.AiMediaTaskService;
import com.basicframework.module.ai.service.media.dto.AiMediaAssetDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskResultDTO;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 应用端图片生成与编辑接口契约（X03）：5 个端点都要求已认证主体；请求字段原样委派
 * （底图只传私有文件编号 + 声明级元数据）；响应只暴露平台事实（状态/稳定失败码/产物文件编号/用量），
 * 协议里不存在任何上游地址字段。
 */
class AiImageControllerTest {

    private final AiImageService imageService = mock(AiImageService.class);

    private final AiMediaTaskService taskService = mock(AiMediaTaskService.class);

    private final AiImageController controller = new AiImageController(imageService, taskService);

    @Test
    void generateDelegatesNarrowedRequestAndMapsTaskFacts() {
        when(imageService.generate(any())).thenReturn(taskResult());

        AiMediaTaskRespVO respVO = controller.generate(generateReqVO()).getData();

        ArgumentCaptor<AiImageGenerateDTO> captor = ArgumentCaptor.forClass(AiImageGenerateDTO.class);
        verify(imageService).generate(captor.capture());
        AiImageGenerateDTO request = captor.getValue();
        assertThat(request.getRequestKey()).isEqualTo("img-1");
        assertThat(request.getEndpointId()).isEqualTo(7L);
        assertThat(request.getPrompt()).isEqualTo("一只坐着的橘猫");
        assertThat(request.getSize()).isEqualTo("1024x1024");
        assertThat(request.getCount()).isEqualTo(2);
        assertThat(request.getOutputFormat()).isEqualTo("png");

        assertThat(respVO.getId()).isEqualTo(512L);
        assertThat(respVO.getMediaKind()).isEqualTo(AiMediaTaskDO.KIND_IMAGE);
        assertThat(respVO.getOperation()).isEqualTo(AiMediaTaskDO.OPERATION_GENERATE);
        assertThat(respVO.getCapability()).isEqualTo("IMAGE_GENERATION");
        assertThat(respVO.getStatus()).isEqualTo(AiMediaTaskDO.STATUS_SUCCEEDED);
        assertThat(respVO.getFailureCode()).isNull();
        assertThat(respVO.getInputText()).isEqualTo("一只坐着的橘猫");
        assertThat(respVO.getSourceFileId()).isNull();
        assertThat(respVO.getEndpointId()).isEqualTo(7L);
        assertThat(respVO.getOutputCount()).isEqualTo(1);
        assertThat(respVO.getOutputFormat()).isEqualTo("png");
        assertThat(respVO.getResultCount()).isEqualTo(1);
        assertThat(respVO.getCreateTime()).isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        assertThat(respVO.getUsage().getUnit()).isEqualTo("TOKEN");
        assertThat(respVO.getUsage().getQuantity()).isEqualTo(360L);
        assertThat(respVO.getUsage().getSource()).isEqualTo("REPORTED");
        assertThat(respVO.getAssets()).hasSize(1);
        AiMediaAssetRespVO asset = respVO.getAssets().get(0);
        assertThat(asset.getFileId()).isEqualTo(2048L);
        assertThat(asset.getOrdinal()).isEqualTo(1);
        assertThat(asset.getMimeType()).isEqualTo("image/png");
        assertThat(asset.getSizeBytes()).isEqualTo(4096L);
        assertThat(asset.getSha256()).isEqualTo("a".repeat(64));
        assertThat(asset.getWidth()).isEqualTo(1024);
        assertThat(asset.getHeight()).isEqualTo(1024);
        assertThat(asset.getDurationMillis()).isNull();
    }

    @Test
    void generateMapsUnknownUsageWithoutFabricatingNumbers() {
        when(imageService.generate(any()))
                .thenReturn(new AiMediaTaskResultDTO()
                        .setId(512L)
                        .setStatus(AiMediaTaskDO.STATUS_QUEUED)
                        .setUsageSource(AiMediaTaskDO.USAGE_SOURCE_UNKNOWN));

        AiMediaTaskRespVO respVO = controller.generate(generateReqVO()).getData();

        assertThat(respVO.getUsage().getSource()).isEqualTo("UNKNOWN");
        assertThat(respVO.getUsage().getQuantity()).as("未知用量不得用 0 冒充").isNull();
        assertThat(respVO.getUsage().getUnit()).isNull();
        assertThat(respVO.getAssets()).as("进行中任务没有产物").isEmpty();
    }

    @Test
    void editDelegatesPrivateImageReferenceAndMapsSourceFileId() {
        when(imageService.edit(any()))
                .thenReturn(
                        taskResult().setOperation(AiMediaTaskDO.OPERATION_EDIT).setSourceFileId(88L));

        AiMediaTaskRespVO respVO = controller
                .edit(new AiImageEditReqVO()
                        .setRequestKey("edit-1")
                        .setEndpointId(7L)
                        .setInstruction("去掉水印")
                        .setImage(new AiImageMediaRefVO()
                                .setFileId(88L)
                                .setMime("image/png")
                                .setSize(4096L)
                                .setSha256("b".repeat(64)))
                        .setSize("1024x1024")
                        .setOutputFormat("png"))
                .getData();

        ArgumentCaptor<AiImageEditDTO> captor = ArgumentCaptor.forClass(AiImageEditDTO.class);
        verify(imageService).edit(captor.capture());
        AiImageEditDTO request = captor.getValue();
        assertThat(request.getRequestKey()).isEqualTo("edit-1");
        assertThat(request.getEndpointId()).isEqualTo(7L);
        assertThat(request.getInstruction()).isEqualTo("去掉水印");
        assertThat(request.getSourceFileId()).isEqualTo(88L);
        assertThat(request.getSourceMime()).isEqualTo("image/png");
        assertThat(request.getSourceSizeBytes()).isEqualTo(4096L);
        assertThat(request.getSourceSha256()).isEqualTo("b".repeat(64));
        assertThat(respVO.getOperation()).isEqualTo(AiMediaTaskDO.OPERATION_EDIT);
        assertThat(respVO.getSourceFileId()).as("编辑任务回显底图编号").isEqualTo(88L);
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
        when(taskService.getTaskPage(any(PageParam.class), eq("IMAGE"), eq("QUEUED")))
                .thenReturn(new PageResult<>(
                        List.of(
                                taskResult().setId(1L).setStatus(AiMediaTaskDO.STATUS_QUEUED),
                                taskResult().setId(2L).setStatus(AiMediaTaskDO.STATUS_QUEUED)),
                        2L));

        PageResult<AiMediaTaskRespVO> page = controller
                .getTaskPage(new PageParam().setPageNo(2).setPageSize(5), "IMAGE", "QUEUED")
                .getData();

        ArgumentCaptor<PageParam> pageParam = ArgumentCaptor.forClass(PageParam.class);
        verify(taskService).getTaskPage(pageParam.capture(), eq("IMAGE"), eq("QUEUED"));
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
        for (String methodName : new String[] {"generate", "edit", "getTask", "getTaskPage", "cancel"}) {
            Method method = findMethod(methodName);
            assertThat(method.getAnnotation(AuthenticatedOnly.class))
                    .as("%s 必须要求已认证主体（不接受匿名访问）", methodName)
                    .isNotNull();
        }
    }

    @Test
    void taskAndAssetVosExposeNoAddressLikeFields() {
        assertNoAddressFields(AiMediaTaskRespVO.class);
        assertNoAddressFields(AiMediaAssetRespVO.class);
        assertNoAddressFields(com.basicframework.module.ai.controller.app.v1.image.vo.AiMediaUsageRespVO.class);
        assertNoAddressFields(AiImageMediaRefVO.class);
    }

    private static AiMediaTaskResultDTO taskResult() {
        return new AiMediaTaskResultDTO()
                .setId(512L)
                .setRequestKey("img-1")
                .setMediaKind(AiMediaTaskDO.KIND_IMAGE)
                .setOperation(AiMediaTaskDO.OPERATION_GENERATE)
                .setCapability("IMAGE_GENERATION")
                .setStatus(AiMediaTaskDO.STATUS_SUCCEEDED)
                .setInputText("一只坐着的橘猫")
                .setEndpointId(7L)
                .setOutputCount(1)
                .setOutputFormat("png")
                .setResultCount(1)
                .setUsageUnit("TOKEN")
                .setUsageQuantity(360L)
                .setUsageSource("REPORTED")
                .setCreateTime(LocalDateTime.of(2026, 1, 2, 3, 4, 5))
                .withAssets(List.of(new AiMediaAssetDTO()
                        .setFileId(2048L)
                        .setOrdinal(1)
                        .setMimeType("image/png")
                        .setSizeBytes(4096L)
                        .setSha256("a".repeat(64))
                        .setWidth(1024)
                        .setHeight(1024)));
    }

    private static AiImageGenerateReqVO generateReqVO() {
        return new AiImageGenerateReqVO()
                .setRequestKey("img-1")
                .setEndpointId(7L)
                .setPrompt("一只坐着的橘猫")
                .setSize("1024x1024")
                .setCount(2)
                .setOutputFormat("png");
    }

    private static Method findMethod(String name) {
        for (Method method : AiImageController.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new IllegalStateException("未找到方法：" + name);
    }

    /** 协议里不存在任何"地址"字段：产物读取一律走受控文件接口，不接受上游临时链接。 */
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
