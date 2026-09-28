package com.basicframework.module.ai.service.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.basicframework.framework.ai.core.model.media.ImageEditRequest;
import com.basicframework.framework.ai.core.model.media.ImageGenerationRequest;
import com.basicframework.framework.ai.core.model.media.ImageResult;
import com.basicframework.framework.ai.core.model.media.MediaArtifact;
import com.basicframework.framework.ai.core.model.media.MediaFileRef;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.model.AiMediaCapabilityGate;
import com.basicframework.module.ai.adapter.model.AiModelClientResolver;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaAssetDO;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.dal.mysql.media.AiMediaAssetMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.file.AiFileBusinessType;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.file.dto.AiFileUploadResultDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaStepOutcome;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskLeaseDTO;
import com.basicframework.module.ai.service.model.AiModelCapabilityProbeService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelProbeResultDTO;
import com.basicframework.module.ai.service.vision.AiVisionImageGuard;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 图片生成/编辑执行器（X03）：真实准入闸门（端点启用 + 能力声明 + 探测确认）先于任何端口调用；
 * 产物必须是真实位图才落私有文件；产物行按序号升序；用量只用上游真实计数（缺失记 UNKNOWN 且数值为空）。
 *
 * <p>端口是记录型替身（非 Mockito 打桩）：这样"准入失败零外发"与"端口收到的引用形状"都是真实行为断言；
 * 文件服务与产物 Mapper 用打桩，断言落库/上传的调用参数。
 */
class AiImageStepExecutorTest {

    private static final Long ENDPOINT_ID = 9L;

    private static final Long TASK_ID = 501L;

    private static final String MODEL_REF = "gpt-image-1";

    private final AiModelEndpointService endpointService = mock(AiModelEndpointService.class);

    private final AiModelCapabilityProbeService probeService = mock(AiModelCapabilityProbeService.class);

    private final AiModelClientResolver clientResolver = mock(AiModelClientResolver.class);

    private final AiFileService fileService = mock(AiFileService.class);

    private final AiMediaAssetMapper assetMapper = mock(AiMediaAssetMapper.class);

    private final RecordingImagePort port = new RecordingImagePort();

    private final AiMediaCapabilityGate gate = new AiMediaCapabilityGate(endpointService, probeService, clientResolver);

    private final AiImageStepExecutor executor =
            new AiImageStepExecutor(gate, fileService, new AiVisionImageGuard(), assetMapper);

    private final AtomicInteger nextFileId = new AtomicInteger(1000);

    @BeforeEach
    void setUp() {
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID))
                .thenReturn(new AiModelEndpointDO()
                        .setId(ENDPOINT_ID)
                        .setEnabled(true)
                        .setConfigRevision(2));
        when(endpointService.getRevisions(ENDPOINT_ID)).thenReturn(List.of(revision("IMAGE_GENERATION,IMAGE_EDIT")));
        when(probeService.getLatestResults(ENDPOINT_ID))
                .thenReturn(List.of(probe("IMAGE_GENERATION", 2), probe("IMAGE_EDIT", 2)));
        when(clientResolver.resolve(ENDPOINT_ID)).thenReturn(port);
        when(fileService.upload(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Long fileId = (long) nextFileId.incrementAndGet();
            return new AiFileUploadResultDTO()
                    .setFileId(fileId)
                    .setBusinessType(invocation.getArgument(0, String.class))
                    .setBusinessKey(invocation.getArgument(1, String.class))
                    .setName(invocation.getArgument(2, String.class))
                    .setSize((long) invocation.getArgument(4, byte[].class).length);
        });
    }

    @Test
    void supportsOnlyImageOperations() {
        assertThat(executor.supports(AiMediaTaskDO.OPERATION_GENERATE)).isTrue();
        assertThat(executor.supports(AiMediaTaskDO.OPERATION_EDIT)).isTrue();
        assertThat(executor.supports(AiMediaTaskDO.OPERATION_TRANSCRIBE)).isFalse();
        assertThat(executor.supports(AiMediaTaskDO.OPERATION_SYNTHESIZE)).isFalse();
        assertThat(executor.supports(null)).isFalse();
    }

    @Test
    void undeclaredCapabilityIsRejectedBeforeAnyPortCall() {
        when(endpointService.getRevisions(ENDPOINT_ID)).thenReturn(List.of(revision("TEXT")));

        assertCode(
                () -> executor.execute(lease(), generateTask()), AiErrorCodeConstants.AI_MODEL_CAPABILITY_NOT_ENABLED);

        assertThat(port.calls).as("未声明能力的任务不得发生外发").hasValue(0);
        verify(clientResolver, never()).resolve(any());
        verify(fileService, never()).upload(any(), any(), any(), any(), any());
        verify(assetMapper, never()).insert(any(AiMediaAssetDO.class));
    }

    @Test
    void declaredButNeverProbedCapabilityIsRejectedBeforeAnyPortCall() {
        when(probeService.getLatestResults(ENDPOINT_ID)).thenReturn(List.of());

        assertCode(
                () -> executor.execute(lease(), generateTask()), AiErrorCodeConstants.AI_MODEL_CAPABILITY_NOT_ENABLED);

        assertThat(port.calls).hasValue(0);
        verify(clientResolver, never()).resolve(any());
    }

    @Test
    void probeConclusionFromAnotherConfigRevisionIsRejectedBeforeAnyPortCall() {
        when(probeService.getLatestResults(ENDPOINT_ID))
                .thenReturn(List.of(probe("IMAGE_GENERATION", 1), probe("IMAGE_EDIT", 1)));

        assertCode(
                () -> executor.execute(lease(), generateTask()), AiErrorCodeConstants.AI_MODEL_CAPABILITY_NOT_ENABLED);

        assertThat(port.calls).hasValue(0);
    }

    @Test
    void nonImageCapabilityOnTaskIsRejectedBeforeAdmission() {
        AiMediaTaskDO task = generateTask().setCapability(ModelCapability.TEXT.name());

        assertCode(() -> executor.execute(lease(), task), AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);

        assertThat(port.calls).hasValue(0);
    }

    @Test
    void operationOtherThanGenerateOrEditIsRejectedAsStateConflict() {
        AiMediaTaskDO task = generateTask().setOperation(AiMediaTaskDO.OPERATION_TRANSCRIBE);

        assertCode(() -> executor.execute(lease(), task), AiErrorCodeConstants.AI_STATE_CONFLICT);

        assertThat(port.calls).hasValue(0);
    }

    @Test
    void htmlArtifactIsRejectedAsInvalidOutputAndNothingIsStored() {
        byte[] html = "<html><body>not an image</body></html>".getBytes(StandardCharsets.UTF_8);
        port.result = ImageResult.of(
                new MediaArtifact("image/png", html, null, null, null, null), ModelUsage.UNKNOWN, MODEL_REF);

        assertCode(() -> executor.execute(lease(), generateTask()), AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID);

        verify(fileService, never()).upload(any(), any(), any(), any(), any());
        verify(assetMapper, never()).insert(any(AiMediaAssetDO.class));
    }

    @Test
    void artifactWithUnsupportedMimeTypeIsRejectedAsInvalidOutput() {
        port.result = ImageResult.of(
                new MediaArtifact("text/html", "<html/>".getBytes(StandardCharsets.UTF_8), null, null, null, null),
                ModelUsage.UNKNOWN,
                MODEL_REF);

        assertCode(() -> executor.execute(lease(), generateTask()), AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID);

        verify(assetMapper, never()).insert(any(AiMediaAssetDO.class));
    }

    @Test
    void upstreamMediaExceptionIsMappedToStablePlatformCode() {
        port.failure = new ModelException(ModelException.Reason.MEDIA_OUTPUT_EMPTY, "vendor raw body");

        assertThatThrownBy(() -> executor.execute(lease(), generateTask()))
                .isInstanceOfSatisfying(ServiceException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(AiErrorCodeConstants.AI_MEDIA_OUTPUT_EMPTY.getCode());
                    assertThat(exception.getMessage()).doesNotContain("vendor");
                });
        verify(assetMapper, never()).insert(any(AiMediaAssetDO.class));
    }

    @Test
    void generateHappyPathStoresEveryArtifactWithAscendingOrdinalAndReportedTokens() {
        byte[] first = png(64, 48);
        byte[] second = png(32, 16);
        port.result = new ImageResult(
                List.of(
                        new MediaArtifact("image/png", first, null, null, null, null),
                        new MediaArtifact("image/png", second, null, null, null, null)),
                ModelUsage.of(120, 240),
                MODEL_REF,
                "stop");

        AiMediaStepOutcome outcome = executor.execute(lease(), generateTask());

        assertThat(outcome.getStatus()).isEqualTo(AiMediaTaskDO.STATUS_SUCCEEDED);
        assertThat(outcome.getResultCount()).as("产物数量即落库数量").isEqualTo(2);
        assertThat(outcome.getUsageUnit()).isEqualTo("TOKEN");
        assertThat(outcome.getUsageQuantity()).as("上游真实计数之和").isEqualTo(360L);
        assertThat(outcome.getUsageSource()).isEqualTo(AiMediaTaskDO.USAGE_SOURCE_REPORTED);

        ArgumentCaptor<String> businessTypes = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> businessKeys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> mimeTypes = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> contents = ArgumentCaptor.forClass(byte[].class);
        verify(fileService, times(2))
                .upload(
                        businessTypes.capture(),
                        businessKeys.capture(),
                        names.capture(),
                        mimeTypes.capture(),
                        contents.capture());
        assertThat(businessTypes.getAllValues()).containsOnly(AiFileBusinessType.MEDIA_TASK.code());
        assertThat(businessKeys.getAllValues()).containsOnly(String.valueOf(TASK_ID));
        assertThat(names.getAllValues()).containsExactly("ai-image-501-1.png", "ai-image-501-2.png");
        assertThat(mimeTypes.getAllValues()).containsOnly("image/png");
        List<byte[]> uploaded = contents.getAllValues();
        assertThat(uploaded).hasSize(2);
        assertThat(uploaded.get(0)).as("第一张按上游顺序落库").isEqualTo(first);
        assertThat(uploaded.get(1)).isEqualTo(second);

        ArgumentCaptor<AiMediaAssetDO> assets = ArgumentCaptor.forClass(AiMediaAssetDO.class);
        verify(assetMapper, times(2)).insert(assets.capture());
        List<AiMediaAssetDO> rows = assets.getAllValues();
        assertThat(rows).extracting(AiMediaAssetDO::getOrdinal).containsExactly(1, 2);
        assertThat(rows).extracting(AiMediaAssetDO::getFileId).containsExactly(1001L, 1002L);
        assertThat(rows).extracting(AiMediaAssetDO::getTaskId).containsOnly(TASK_ID);
        assertThat(rows).extracting(AiMediaAssetDO::getMimeType).containsOnly("image/png");
        assertThat(rows).extracting(AiMediaAssetDO::getSizeBytes).containsExactly((long) first.length, (long)
                second.length);
        assertThat(rows).extracting(AiMediaAssetDO::getSha256).containsExactly(sha256(first), sha256(second));
        assertThat(rows)
                .extracting(AiMediaAssetDO::getWidth)
                .as("宽高按产物字节真实解析，不采信上游声明")
                .containsExactly(64, 32);
        assertThat(rows).extracting(AiMediaAssetDO::getHeight).containsExactly(48, 16);
        assertThat(rows).extracting(AiMediaAssetDO::getDurationMillis).containsOnlyNulls();
    }

    @Test
    void missingUpstreamUsageIsStoredAsUnknownWithoutFabricatingZero() {
        port.result = ImageResult.of(pngArtifact(24, 24), ModelUsage.UNKNOWN, MODEL_REF);

        AiMediaStepOutcome outcome = executor.execute(lease(), generateTask());

        assertThat(outcome.getStatus()).isEqualTo(AiMediaTaskDO.STATUS_SUCCEEDED);
        assertThat(outcome.getResultCount()).isEqualTo(1);
        assertThat(outcome.getUsageQuantity()).as("上游没给用量不得写 0").isNull();
        assertThat(outcome.getUsageUnit()).isNull();
        assertThat(outcome.getUsageSource()).isEqualTo(AiMediaTaskDO.USAGE_SOURCE_UNKNOWN);
    }

    @Test
    void editPassesVerifiedPrivateSourceReferenceToThePort() {
        byte[] source = png(64, 48);
        when(fileService.read(88L)).thenReturn(source);
        port.result = ImageResult.of(pngArtifact(64, 48), ModelUsage.of(10, 20), MODEL_REF);
        AiMediaTaskDO task = editTask()
                .setSourceFileId(88L)
                .setSourceMime("image/png")
                .setSourceSizeBytes((long) source.length)
                .setSourceSha256(sha256(source));

        AiMediaStepOutcome outcome = executor.execute(lease(), task);

        assertThat(outcome.getStatus()).isEqualTo(AiMediaTaskDO.STATUS_SUCCEEDED);
        assertThat(port.calls).hasValue(1);
        ImageEditRequest request = port.lastEditRequest;
        assertThat(request.modelId()).isEqualTo(MODEL_REF);
        assertThat(request.instruction()).isEqualTo("去掉水印");
        assertThat(request.size()).isEqualTo("1024x1024");
        assertThat(request.outputFormat()).isEqualTo("png");
        MediaFileRef sourceRef = request.source();
        assertThat(sourceRef.fileId()).isEqualTo(88L);
        assertThat(sourceRef.mimeType()).isEqualTo("image/png");
        assertThat(sourceRef.sizeBytes()).as("引用字节数按真实读取长度").isEqualTo((long) source.length);
        assertThat(sourceRef.sha256()).isEqualTo(sha256(source));
        verify(assetMapper).insert(any(AiMediaAssetDO.class));
    }

    @Test
    void editWithoutSourceFileIsRejectedBeforeAnyPortCall() {
        AiMediaTaskDO task = editTask().setSourceFileId(null);

        assertCode(() -> executor.execute(lease(), task), AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);

        assertThat(port.calls).as("没有底图不得外发").hasValue(0);
    }

    @Test
    void editWithUnreadableOrEmptySourceIsRejectedBeforeAnyPortCall() {
        when(fileService.read(88L)).thenReturn(new byte[0]);
        AiMediaTaskDO task =
                editTask().setSourceFileId(88L).setSourceMime("image/png").setSourceSizeBytes(2048L);

        assertCode(() -> executor.execute(lease(), task), AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);

        assertThat(port.calls).hasValue(0);
    }

    @Test
    void editSurfacesLostSourcePermissionAsNotFoundWithoutAnyPortCall() {
        when(fileService.read(88L)).thenThrow(new ServiceException(AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
        AiMediaTaskDO task =
                editTask().setSourceFileId(88L).setSourceMime("image/png").setSourceSizeBytes(2048L);

        assertCode(() -> executor.execute(lease(), task), AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);

        assertThat(port.calls).as("执行期失权必须零外发").hasValue(0);
    }

    @Test
    void generatePassesRequestedParametersAndFixedModelToThePort() {
        port.result = ImageResult.of(pngArtifact(64, 48), ModelUsage.UNKNOWN, MODEL_REF);

        executor.execute(lease(), generateTask());

        ImageGenerationRequest request = port.lastGenerationRequest;
        assertThat(request.modelId()).as("端口拿到受理时固定的模型标识").isEqualTo(MODEL_REF);
        assertThat(request.prompt()).isEqualTo("一只坐着的橘猫");
        assertThat(request.size()).isEqualTo("1024x1024");
        assertThat(request.count()).isEqualTo(1);
        assertThat(request.outputFormat()).isEqualTo("png");
    }

    private static AiMediaTaskDO generateTask() {
        return new AiMediaTaskDO()
                .setId(TASK_ID)
                .setMediaKind(AiMediaTaskDO.KIND_IMAGE)
                .setOperation(AiMediaTaskDO.OPERATION_GENERATE)
                .setCapability(ModelCapability.IMAGE_GENERATION.name())
                .setEndpointId(ENDPOINT_ID)
                .setModelRef(MODEL_REF)
                .setInputText("一只坐着的橘猫")
                .setTargetSize("1024x1024")
                .setOutputCount(1)
                .setOutputFormat("png")
                .setStatus(AiMediaTaskDO.STATUS_RUNNING);
    }

    private static AiMediaTaskDO editTask() {
        return generateTask()
                .setOperation(AiMediaTaskDO.OPERATION_EDIT)
                .setCapability(ModelCapability.IMAGE_EDIT.name())
                .setInputText("去掉水印");
    }

    private static AiMediaTaskLeaseDTO lease() {
        return new AiMediaTaskLeaseDTO()
                .setTaskId(TASK_ID)
                .setOwner("media-task-test")
                .setEpoch(1);
    }

    private static AiModelEndpointRevisionDO revision(String capabilities) {
        return new AiModelEndpointRevisionDO()
                .setEndpointId(ENDPOINT_ID)
                .setRevision(1)
                .setModelId(MODEL_REF)
                .setCapabilities(capabilities);
    }

    private static AiModelProbeResultDTO probe(String kind, Integer configRevision) {
        return new AiModelProbeResultDTO()
                .setProbeKind(kind)
                .setStatus("SUPPORTED")
                .setConfigRevision(configRevision);
    }

    private static MediaArtifact pngArtifact(int width, int height) {
        byte[] content = png(width, height);
        return new MediaArtifact("image/png", content, null, width, height, null);
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

    /** 记录型图片端口：真实计数调用、保留最近一次请求，便于断言"引用形状"与"零外发"。 */
    private static final class RecordingImagePort implements ModelPort {

        private final AtomicInteger calls = new AtomicInteger();

        private ImageResult result = ImageResult.of(
                new MediaArtifact("image/png", new byte[] {1}, null, null, null, null), ModelUsage.UNKNOWN, MODEL_REF);

        private ModelException failure;

        private ImageGenerationRequest lastGenerationRequest;

        private ImageEditRequest lastEditRequest;

        @Override
        public Set<ModelCapability> capabilities() {
            return Set.of(ModelCapability.IMAGE_GENERATION, ModelCapability.IMAGE_EDIT);
        }

        @Override
        public ModelResponse generate(ModelRequest request) {
            throw new AssertionError("图片执行器不得回退为文本调用");
        }

        @Override
        public EmbeddingResponse embed(EmbeddingRequest request) {
            throw new AssertionError("图片执行器不得调用嵌入");
        }

        @Override
        public ImageResult generateImage(ImageGenerationRequest request) {
            calls.incrementAndGet();
            lastGenerationRequest = request;
            return respond();
        }

        @Override
        public ImageResult editImage(ImageEditRequest request) {
            calls.incrementAndGet();
            lastEditRequest = request;
            return respond();
        }

        private ImageResult respond() {
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
