package com.basicframework.module.ai.service.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.media.MediaFileRef;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.image.dto.AiImageEditDTO;
import com.basicframework.module.ai.service.image.dto.AiImageGenerateDTO;
import com.basicframework.module.ai.service.media.AiMediaTaskService;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskResultDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskSubmitDTO;
import com.basicframework.module.ai.service.vision.AiVisionImageGuard;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 图片生成与编辑受理（X03）：参数收窄后的映射、编辑底图在受理前按当前主体核验、
 * 声明与真实字节不一致或底图不可读时整笔拒绝（不落入任务表、不发生上游调用）。
 *
 * <p>图片核验用真实 {@link AiVisionImageGuard}（与生产同一条拒绝线），只在文件读取与任务受理处打桩：
 * 这样"伪装图片被拒"这类断言验证的是真实判定，而不是替身的行为。
 */
class AiImageServiceImplTest {

    private static final Long ENDPOINT_ID = 7L;

    private final AiMediaTaskService taskService = mock(AiMediaTaskService.class);

    private final AiFileService fileService = mock(AiFileService.class);

    private final AiImageServiceImpl service =
            new AiImageServiceImpl(new AiImageParams(), taskService, fileService, new AiVisionImageGuard());

    @Test
    void generateNarrowsParamsAndSubmitsImageGenerationTask() {
        when(taskService.submit(any(AiMediaTaskSubmitDTO.class)))
                .thenReturn(new AiMediaTaskResultDTO().setId(501L).setStatus(AiMediaTaskDO.STATUS_QUEUED));

        AiMediaTaskResultDTO result = service.generate(new AiImageGenerateDTO()
                .setRequestKey("  img-1  ")
                .setEndpointId(ENDPOINT_ID)
                .setPrompt("  一只坐着的橘猫  ")
                .setSize("1024X1024")
                .setCount(4)
                .setOutputFormat("PNG"));

        ArgumentCaptor<AiMediaTaskSubmitDTO> captor = ArgumentCaptor.forClass(AiMediaTaskSubmitDTO.class);
        verify(taskService).submit(captor.capture());
        AiMediaTaskSubmitDTO submitted = captor.getValue();
        assertThat(submitted.getRequestKey()).as("幂等键去空白后提交").isEqualTo("img-1");
        assertThat(submitted.getMediaKind()).isEqualTo(AiMediaTaskDO.KIND_IMAGE);
        assertThat(submitted.getOperation()).isEqualTo(AiMediaTaskDO.OPERATION_GENERATE);
        assertThat(submitted.getCapability()).isEqualTo(ModelCapability.IMAGE_GENERATION.name());
        assertThat(submitted.getEndpointId()).isEqualTo(ENDPOINT_ID);
        assertThat(submitted.getInputText()).as("提示词去空白").isEqualTo("一只坐着的橘猫");
        assertThat(submitted.getTargetSize()).isEqualTo("1024x1024");
        assertThat(submitted.getOutputCount()).isEqualTo(4);
        assertThat(submitted.getOutputFormat()).isEqualTo("png");
        assertThat(submitted.getSourceFileId()).as("生成类不带源文件").isNull();
        assertThat(result.getId()).isEqualTo(501L);
    }

    @Test
    void generateDefaultsCountAndFormatWhenOmitted() {
        when(taskService.submit(any(AiMediaTaskSubmitDTO.class))).thenReturn(new AiMediaTaskResultDTO().setId(501L));

        service.generate(new AiImageGenerateDTO()
                .setRequestKey("img-1")
                .setEndpointId(ENDPOINT_ID)
                .setPrompt("猫")
                .setSize(null)
                .setCount(null)
                .setOutputFormat(null));

        ArgumentCaptor<AiMediaTaskSubmitDTO> captor = ArgumentCaptor.forClass(AiMediaTaskSubmitDTO.class);
        verify(taskService).submit(captor.capture());
        assertThat(captor.getValue().getTargetSize()).as("空尺寸由端点默认值决定").isNull();
        assertThat(captor.getValue().getOutputCount()).isEqualTo(1);
        assertThat(captor.getValue().getOutputFormat()).isEqualTo("png");
    }

    @Test
    void generateRejectsIllegalParamsBeforeSubmitting() {
        assertThatThrownBy(() -> service.generate(null)).isInstanceOf(IllegalArgumentException.class);
        assertCode(
                () -> service.generate(new AiImageGenerateDTO()
                        .setRequestKey("img-1")
                        .setEndpointId(ENDPOINT_ID)
                        .setPrompt("猫")
                        .setSize("1x1")),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> service.generate(new AiImageGenerateDTO()
                        .setRequestKey("img-1")
                        .setEndpointId(ENDPOINT_ID)
                        .setPrompt("猫")
                        .setCount(9)),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> service.generate(new AiImageGenerateDTO()
                        .setRequestKey("img-1")
                        .setEndpointId(ENDPOINT_ID)
                        .setPrompt("  ")),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        verify(taskService, never()).submit(any(AiMediaTaskSubmitDTO.class));
    }

    @Test
    void editSubmitsVerifiedSourceFactsInsteadOfCallerDeclarations() {
        byte[] content = png(64, 48);
        when(fileService.read(88L)).thenReturn(content);
        when(taskService.submit(any(AiMediaTaskSubmitDTO.class)))
                .thenReturn(new AiMediaTaskResultDTO().setId(502L).setStatus(AiMediaTaskDO.STATUS_QUEUED));

        AiMediaTaskResultDTO result = service.edit(new AiImageEditDTO()
                .setRequestKey("edit-1")
                .setEndpointId(ENDPOINT_ID)
                .setInstruction("  去掉水印  ")
                .setSourceFileId(88L)
                .setSourceMime("image/png")
                .setSourceSizeBytes((long) content.length)
                .setSourceSha256(sha256(content).toUpperCase(Locale.ROOT))
                .setSize("1024x1024")
                .setOutputFormat("png"));

        ArgumentCaptor<AiMediaTaskSubmitDTO> captor = ArgumentCaptor.forClass(AiMediaTaskSubmitDTO.class);
        verify(taskService).submit(captor.capture());
        AiMediaTaskSubmitDTO submitted = captor.getValue();
        assertThat(submitted.getOperation()).isEqualTo(AiMediaTaskDO.OPERATION_EDIT);
        assertThat(submitted.getCapability()).isEqualTo(ModelCapability.IMAGE_EDIT.name());
        assertThat(submitted.getSourceFileId()).isEqualTo(88L);
        assertThat(submitted.getSourceMime()).isEqualTo("image/png");
        assertThat(submitted.getSourceSizeBytes()).as("外发引用使用服务端核验出的字节数").isEqualTo((long) content.length);
        assertThat(submitted.getSourceSha256()).as("摘要按真实内容归一化为小写").isEqualTo(sha256(content));
        assertThat(submitted.getInputText()).isEqualTo("去掉水印");
        assertThat(submitted.getOutputCount()).as("编辑一次只产出一张").isEqualTo(1);
        assertThat(result.getId()).isEqualTo(502L);
    }

    @Test
    void editRejectsDeclaredSizeThatDiffersFromRealBytesBeforeSubmitting() {
        byte[] content = png(64, 48);

        when(fileService.read(88L)).thenReturn(content);
        assertCode(
                () -> service.edit(editRequest(88L, "image/png", content.length + 1L, null)),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
        verify(taskService, never()).submit(any(AiMediaTaskSubmitDTO.class));
    }

    @Test
    void editRejectsDeclaredMimeThatDoesNotMatchRealContentBeforeSubmitting() {
        byte[] content = png(64, 48);
        when(fileService.read(88L)).thenReturn(content);

        // 声明 webp 但内容其实是 png：白名单内 MIME + 魔数不符 → 拒绝
        assertCode(
                () -> service.edit(editRequest(88L, "image/webp", (long) content.length, null)),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
        verify(taskService, never()).submit(any(AiMediaTaskSubmitDTO.class));
    }

    @Test
    void editSurfacesUnreadableOrForeignSourceWithoutSubmitting() {
        when(fileService.read(88L)).thenThrow(new ServiceException(AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));

        assertCode(
                () -> service.edit(editRequest(88L, "image/png", 2048L, null)),
                AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);
        verify(taskService, never()).submit(any(AiMediaTaskSubmitDTO.class));
    }

    @Test
    void editRejectsIllegalSourceMetadataBeforeReadingTheFile() {
        assertCode(
                () -> service.edit(editRequest(null, "image/png", 2048L, null)),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> service.edit(editRequest(0L, "image/png", 2048L, null)),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> service.edit(editRequest(88L, "image/svg+xml", 2048L, null)),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
        assertCode(
                () -> service.edit(editRequest(88L, "image/png", 0L, null)),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> service.edit(editRequest(88L, "image/png", 2048L, "not-a-digest")),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);

        verify(fileService, never()).read(any());
        verify(taskService, never()).submit(any(AiMediaTaskSubmitDTO.class));
    }

    @Test
    void editRejectsTamperedDigestEvenWhenSizeAndMagicMatch() {
        byte[] content = png(64, 48);
        when(fileService.read(88L)).thenReturn(content);

        assertCode(
                () -> service.edit(editRequest(88L, "image/png", (long) content.length, "0".repeat(64))),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        verify(taskService, never()).submit(any(AiMediaTaskSubmitDTO.class));
    }

    @Test
    void editRejectsNullRequest() {
        assertThatThrownBy(() -> service.edit(null)).isInstanceOf(IllegalArgumentException.class);
        verify(taskService, never()).submit(any(AiMediaTaskSubmitDTO.class));
    }

    @Test
    void editNormalizesDeclaredMimeCaseThroughTheVerifiedFormat() {
        byte[] content = png(32, 32);
        when(fileService.read(88L)).thenReturn(content);
        when(taskService.submit(any(AiMediaTaskSubmitDTO.class))).thenReturn(new AiMediaTaskResultDTO().setId(503L));

        service.edit(editRequest(88L, "IMAGE/PNG", (long) content.length, sha256(content)));

        ArgumentCaptor<AiMediaTaskSubmitDTO> captor = ArgumentCaptor.forClass(AiMediaTaskSubmitDTO.class);
        verify(taskService).submit(captor.capture());
        assertThat(captor.getValue().getSourceMime()).as("外发 MIME 用归一化后的核验结果").isEqualTo("image/png");
    }

    @Test
    void outboundSourceReferenceHasNoAddressFields() {
        // 防回归：受理外发的引用是平台自有 MediaFileRef，只有编号与声明级元数据，没有地址字段
        List<String> components = Arrays.stream(MediaFileRef.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
        assertThat(components).containsExactly("fileid", "mimetype", "sizebytes", "sha256");
        assertThat(components).allSatisfy(name -> assertThat(name)
                .doesNotContain("url")
                .doesNotContain("uri")
                .doesNotContain("link")
                .doesNotContain("href"));
    }

    private static AiImageEditDTO editRequest(Long fileId, String mime, Long size, String sha256) {
        return new AiImageEditDTO()
                .setRequestKey("edit-1")
                .setEndpointId(ENDPOINT_ID)
                .setInstruction("去掉水印")
                .setSourceFileId(fileId)
                .setSourceMime(mime)
                .setSourceSizeBytes(size)
                .setSourceSha256(sha256)
                .setOutputFormat("png");
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(expected.getCode()));
    }

    private static byte[] png(int width, int height) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("测试图片编码失败", failure);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException missingAlgorithm) {
            throw new IllegalStateException("SHA-256 不可用", missingAlgorithm);
        }
    }
}
