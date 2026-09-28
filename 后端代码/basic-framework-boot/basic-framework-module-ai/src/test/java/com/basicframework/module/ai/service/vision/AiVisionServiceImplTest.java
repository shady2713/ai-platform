package com.basicframework.module.ai.service.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.model.EmbeddingRequest;
import com.basicframework.framework.ai.core.model.EmbeddingResponse;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.ai.core.model.media.ImageOcrRequest;
import com.basicframework.framework.ai.core.model.media.ImageUnderstandingRequest;
import com.basicframework.framework.ai.core.model.media.MediaFileRef;
import com.basicframework.framework.ai.core.model.media.MediaTextResponse;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.model.AiMediaCapabilityGate;
import com.basicframework.module.ai.adapter.model.AiModelClientResolver;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.model.AiModelCapabilityProbeService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelProbeResultDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionImageRefDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionOcrRequestDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionOcrResultDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionTextResultDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionUnderstandRequestDTO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 图片理解与 OCR 服务（X02）：准入先于读文件与外发、业务 ACL、内容核验、结果溯源语义。
 *
 * <p>本类刻意使用**真实的** {@link AiMediaCapabilityGate} 与会计数的端口替身：
 * "未声明/未确认能力 → 稳定错误码 + 零外发"必须由真实准入链路证明，而不是断言 mock 被调用。
 * 零外发有两层断言：端口计数为 0，且客户端解析器从未被调用（连受管客户端都没建）。
 */
class AiVisionServiceImplTest {

    private static final long ENDPOINT_ID = 7L;

    private static final long FILE_ID = 88L;

    private final AiFileService fileService = mock(AiFileService.class);

    private final AiModelEndpointService endpointService = mock(AiModelEndpointService.class);

    private final AiModelCapabilityProbeService probeService = mock(AiModelCapabilityProbeService.class);

    private final AiModelClientResolver clientResolver = mock(AiModelClientResolver.class);

    private final CountingPort port = new CountingPort();

    private AiVisionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiVisionServiceImpl(
                fileService,
                new AiVisionImageGuard(),
                new AiMediaCapabilityGate(endpointService, probeService, clientResolver),
                endpointService);
        when(endpointService.getRevisions(ENDPOINT_ID))
                .thenReturn(List.of(new AiModelEndpointRevisionDO()
                        .setEndpointId(ENDPOINT_ID)
                        .setModelId("vision-mini")
                        .setCapabilities("IMAGE_UNDERSTANDING,IMAGE_OCR")));
        when(clientResolver.resolve(ENDPOINT_ID)).thenReturn(port);
    }

    @Test
    void ocrRejectsUndeclaredCapabilityWithoutReadingFileOrCallingPort() {
        givenEnabledEndpointWithoutDeclaration();

        assertCode(() -> service.recognizeText(ocrRequest(png(32, 32))), AI_CAPABILITY_NOT_ENABLED);

        assertThat(port.ocrCalls).isZero();
        verify(fileService, never()).read(anyLong());
        verify(clientResolver, never()).resolve(anyLong());
    }

    @Test
    void ocrRejectsDeclaredButUnprobedCapabilityWithoutOutbound() {
        givenEnabledEndpoint();
        when(probeService.getLatestResults(ENDPOINT_ID)).thenReturn(List.of());

        assertCode(() -> service.recognizeText(ocrRequest(png(32, 32))), AI_CAPABILITY_NOT_ENABLED);

        assertThat(port.ocrCalls).isZero();
        verify(fileService, never()).read(anyLong());
        verify(clientResolver, never()).resolve(anyLong());
    }

    @Test
    void ocrRejectsProbeConclusionFromOlderConfigRevision() {
        givenEnabledEndpoint();
        // 探测结论来自旧配置版本：配置变化即失效，必须重探（X01 准入第 3 条）
        when(probeService.getLatestResults(ENDPOINT_ID)).thenReturn(List.of(supportedProbe("IMAGE_OCR", 1)));

        assertCode(() -> service.recognizeText(ocrRequest(png(32, 32))), AI_CAPABILITY_NOT_ENABLED);

        assertThat(port.ocrCalls).isZero();
        verify(fileService, never()).read(anyLong());
    }

    @Test
    void ocrRejectsOtherMediaCapabilityConclusion() {
        givenEnabledEndpoint();
        when(probeService.getLatestResults(ENDPOINT_ID)).thenReturn(List.of(supportedProbe("IMAGE_UNDERSTANDING", 2)));

        assertCode(() -> service.recognizeText(ocrRequest(png(32, 32))), AI_CAPABILITY_NOT_ENABLED);

        assertThat(port.ocrCalls).isZero();
    }

    @Test
    void ocrAdmitsAndPassesVerifiedMetadataAndModelIdToPort() {
        givenAdmitted(Set.of(ModelCapability.IMAGE_OCR, ModelCapability.IMAGE_UNDERSTANDING));
        byte[] content = png(48, 24);
        when(fileService.read(FILE_ID)).thenReturn(content);
        String digest = AiVisionImageGuard.sha256Hex(content);

        AiVisionOcrResultDTO result = service.recognizeText(new AiVisionOcrRequestDTO()
                .setEndpointId(ENDPOINT_ID)
                .setImage(imageRef(content, digest))
                .setLanguageHint("zh-CN")
                .setTimeoutMillis(30_000L));

        assertThat(port.ocrCalls).isEqualTo(1);
        ImageOcrRequest sent = port.lastOcrRequest;
        // 外发给端口的是**核验后**的真实元数据与端点当前版本的模型标识，且超时按请求下发
        assertThat(sent.modelId()).isEqualTo("vision-mini");
        assertThat(sent.image()).isEqualTo(new MediaFileRef(FILE_ID, "image/png", content.length, digest));
        assertThat(sent.languageHint()).isEqualTo("zh-CN");
        assertThat(sent.timeout()).isEqualTo(Duration.ofMillis(30_000L));
        // 结果只引用私有文件标识，并带页码/范围/置信度来源与"需人工复核"
        assertThat(result.getFileId()).isEqualTo(FILE_ID);
        assertThat(result.getText()).isEqualTo("识别文本");
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getRegionSource()).isEqualTo(AiVisionRegionSource.WHOLE_PAGE);
        assertThat(result.getRegion().getWidth()).isEqualTo(1d);
        assertThat(result.getConfidenceSource()).isEqualTo(AiVisionConfidenceSource.UNKNOWN);
        assertThat(result.getConfidence()).as("上游没有置信度时不得编造数值").isNull();
        assertThat(result.isReviewRequired()).isTrue();
        assertThat(result.getUsage().source()).isEqualTo("REPORTED");
        assertThat(result.getUsage().quantity()).isEqualTo(33);
    }

    @Test
    void understandImageUsesSameAdmissionAndReturnsTextWithUsage() {
        givenAdmitted(Set.of(ModelCapability.IMAGE_UNDERSTANDING, ModelCapability.IMAGE_OCR));
        byte[] content = png(16, 16);
        when(fileService.read(FILE_ID)).thenReturn(content);

        AiVisionTextResultDTO result = service.understandImage(new AiVisionUnderstandRequestDTO()
                .setEndpointId(ENDPOINT_ID)
                .setImage(imageRef(content, null))
                .setInstruction("描述图片"));

        assertThat(port.understandCalls).isEqualTo(1);
        assertThat(port.lastUnderstandRequest.image().fileId()).isEqualTo(FILE_ID);
        assertThat(result.getText()).isEqualTo("图片描述");
        assertThat(result.getFileId()).isEqualTo(FILE_ID);
        assertThat(result.getUsage().source()).isEqualTo("REPORTED");
        assertThat(result.getUsage().quantity()).isEqualTo(7);
    }

    @Test
    void ocrRejectsForgedImageAndOversizedPixelsBeforeAnyOutbound() {
        givenAdmitted(Set.of(ModelCapability.IMAGE_OCR));
        byte[] forged = "not-an-image".getBytes(StandardCharsets.UTF_8);
        when(fileService.read(FILE_ID)).thenReturn(forged);
        assertCode(
                () -> service.recognizeText(ocrRequest(forged)), AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED);

        byte[] tooWide = png(9000, 8);
        when(fileService.read(FILE_ID)).thenReturn(tooWide);
        assertCode(() -> service.recognizeText(ocrRequest(tooWide)), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);

        assertThat(port.ocrCalls).as("输入校验失败不得产生任何模型调用").isZero();
    }

    @Test
    void ocrRejectsUnauthorizedOrMissingPrivateFileWithoutOutbound() {
        givenAdmitted(Set.of(ModelCapability.IMAGE_OCR));
        // A07 的读取语义：无权限与不存在返回同一错误码（防编号枚举）
        when(fileService.read(FILE_ID))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND.getCode(), "资源不存在"));

        assertCode(() -> service.recognizeText(ocrRequest(png(32, 32))), AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);
        assertThat(port.ocrCalls).isZero();
    }

    @Test
    void oversizedDeclaredSizeIsRejectedBeforeReadingTheFile() {
        givenAdmitted(Set.of(ModelCapability.IMAGE_OCR));

        AiVisionImageRefDTO image = new AiVisionImageRefDTO()
                .setFileId(FILE_ID)
                .setMime("image/png")
                .setSize(AiVisionLimits.MAX_IMAGE_BYTES + 1);
        assertCode(
                () -> service.recognizeText(
                        new AiVisionOcrRequestDTO().setEndpointId(ENDPOINT_ID).setImage(image)),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TOO_LARGE);

        verify(fileService, never()).read(anyLong());
        assertThat(port.ocrCalls).isZero();
    }

    @Test
    void emptyUpstreamTextIsRejectedAsMediaOutputEmpty() {
        givenAdmitted(Set.of(ModelCapability.IMAGE_OCR));
        byte[] content = png(16, 16);
        when(fileService.read(FILE_ID)).thenReturn(content);
        port.ocrResponse = null;

        assertCode(() -> service.recognizeText(ocrRequest(content)), AiErrorCodeConstants.AI_MEDIA_OUTPUT_EMPTY);
    }

    @Test
    void invalidRequestShapesAreRejectedWithoutIo() {
        givenAdmitted(Set.of(ModelCapability.IMAGE_OCR, ModelCapability.IMAGE_UNDERSTANDING));

        assertCode(
                () -> service.recognizeText(new AiVisionOcrRequestDTO().setImage(imageRef(png(8, 8), null))),
                AiErrorCodeConstants.AI_REQUEST_INVALID);
        assertCode(
                () -> service.recognizeText(new AiVisionOcrRequestDTO().setEndpointId(ENDPOINT_ID)),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> service.recognizeText(new AiVisionOcrRequestDTO()
                        .setEndpointId(ENDPOINT_ID)
                        .setImage(imageRef(png(8, 8), null))
                        .setLanguageHint("中文提示不是语言标识")),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> service.recognizeText(new AiVisionOcrRequestDTO()
                        .setEndpointId(ENDPOINT_ID)
                        .setImage(imageRef(png(8, 8), null))
                        .setTimeoutMillis(10L)),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> service.understandImage(new AiVisionUnderstandRequestDTO()
                        .setEndpointId(ENDPOINT_ID)
                        .setImage(imageRef(png(8, 8), null))
                        .setInstruction("   ")),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> service.understandImage(new AiVisionUnderstandRequestDTO()
                        .setEndpointId(ENDPOINT_ID)
                        .setImage(imageRef(png(8, 8), null))
                        .setInstruction("x".repeat(AiVisionLimits.MAX_INSTRUCTION_LENGTH + 1))),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);

        verify(fileService, never()).read(anyLong());
        assertThat(port.ocrCalls + port.understandCalls).isZero();
    }

    @Test
    void portFailuresKeepPlatformReasonMappingWithoutUpstreamText() {
        givenAdmitted(Set.of(ModelCapability.IMAGE_OCR));
        byte[] content = png(16, 16);
        when(fileService.read(FILE_ID)).thenReturn(content);
        port.ocrFailure = new ModelException(ModelException.Reason.MEDIA_INPUT_TOO_LARGE, "vendor body sk-secret");

        assertThatThrownBy(() -> service.recognizeText(ocrRequest(content)))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> {
                    ServiceException serviceException = (ServiceException) exception;
                    assertThat(serviceException.getCode())
                            .isEqualTo(AiErrorCodeConstants.AI_MEDIA_INPUT_TOO_LARGE.getCode());
                    assertThat(serviceException.getMessage()).doesNotContain("sk-secret");
                });
    }

    private void givenEnabledEndpoint() {
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID))
                .thenReturn(new AiModelEndpointDO().setId(ENDPOINT_ID).setConfigRevision(2));
    }

    private void givenEnabledEndpointWithoutDeclaration() {
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID))
                .thenReturn(new AiModelEndpointDO().setId(ENDPOINT_ID).setConfigRevision(2));
        when(endpointService.getRevisions(ENDPOINT_ID))
                .thenReturn(List.of(new AiModelEndpointRevisionDO()
                        .setEndpointId(ENDPOINT_ID)
                        .setModelId("text-only")
                        .setCapabilities("TEXT,TEXT_STREAM")));
    }

    private void givenAdmitted(Set<ModelCapability> capabilities) {
        givenEnabledEndpoint();
        when(endpointService.getRevisions(ENDPOINT_ID))
                .thenReturn(List.of(new AiModelEndpointRevisionDO()
                        .setEndpointId(ENDPOINT_ID)
                        .setModelId("vision-mini")
                        .setCapabilities(capabilities.stream()
                                .map(Enum::name)
                                .collect(java.util.stream.Collectors.joining(",")))));
        when(probeService.getLatestResults(ENDPOINT_ID))
                .thenReturn(capabilities.stream()
                        .map(capability -> supportedProbe(capability.probeKind().name(), 2))
                        .toList());
    }

    private static AiModelProbeResultDTO supportedProbe(String probeKind, int configRevision) {
        return new AiModelProbeResultDTO()
                .setProbeKind(probeKind)
                .setStatus("SUPPORTED")
                .setConfigRevision(configRevision);
    }

    private static AiVisionImageRefDTO imageRef(byte[] content, String digest) {
        return new AiVisionImageRefDTO()
                .setFileId(FILE_ID)
                .setMime("image/png")
                .setSize((long) content.length)
                .setSha256(digest);
    }

    private static AiVisionOcrRequestDTO ocrRequest(byte[] content) {
        return new AiVisionOcrRequestDTO().setEndpointId(ENDPOINT_ID).setImage(imageRef(content, null));
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }

    private static byte[] png(int width, int height) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("测试图片编码失败", failure);
        }
    }

    private static final ErrorCode AI_CAPABILITY_NOT_ENABLED = AiErrorCodeConstants.AI_MODEL_CAPABILITY_NOT_ENABLED;

    /** 会计数的端口替身：默认对媒体调用回固定文本，可注入失败或空响应。 */
    private static final class CountingPort implements ModelPort {

        private int ocrCalls;

        private int understandCalls;

        private ImageOcrRequest lastOcrRequest;

        private ImageUnderstandingRequest lastUnderstandRequest;

        private ModelException ocrFailure;

        private MediaTextResponse ocrResponse =
                new MediaTextResponse("识别文本", ModelUsage.of(11, 22), "vision-mini", "stop");

        @Override
        public Set<ModelCapability> capabilities() {
            return Set.of(ModelCapability.IMAGE_OCR, ModelCapability.IMAGE_UNDERSTANDING);
        }

        @Override
        public ModelResponse generate(ModelRequest request) {
            throw new ModelException(ModelException.Reason.CAPABILITY_UNSUPPORTED, "文本调用不应发生");
        }

        @Override
        public EmbeddingResponse embed(EmbeddingRequest request) {
            throw new ModelException(ModelException.Reason.CAPABILITY_UNSUPPORTED, "嵌入调用不应发生");
        }

        @Override
        public MediaTextResponse recognizeImageText(ImageOcrRequest request) {
            ocrCalls++;
            lastOcrRequest = request;
            if (ocrFailure != null) {
                throw ocrFailure;
            }
            return ocrResponse;
        }

        @Override
        public MediaTextResponse understandImage(ImageUnderstandingRequest request) {
            understandCalls++;
            lastUnderstandRequest = request;
            return new MediaTextResponse("图片描述", ModelUsage.of(3, 4), "vision-mini", "stop");
        }
    }
}
