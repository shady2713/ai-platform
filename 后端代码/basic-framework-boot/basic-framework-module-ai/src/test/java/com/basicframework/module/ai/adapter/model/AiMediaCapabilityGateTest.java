package com.basicframework.module.ai.adapter.model;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_INPUT_DURATION_EXCEEDED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_INPUT_TOO_LARGE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_OUTPUT_EMPTY;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_CALL_FAILED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_CAPABILITY_NOT_ENABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_ENDPOINT_DISABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_OUTBOUND_BLOCKED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.ai.core.model.media.ImageGenerationRequest;
import com.basicframework.framework.ai.core.model.media.ImageOcrRequest;
import com.basicframework.framework.ai.core.model.media.ImageResult;
import com.basicframework.framework.ai.core.model.media.ImageUnderstandingRequest;
import com.basicframework.framework.ai.core.model.media.MediaArtifact;
import com.basicframework.framework.ai.core.model.media.MediaFileRef;
import com.basicframework.framework.ai.core.model.media.MediaTextResponse;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.service.model.AiModelCapabilityProbeService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelProbeResultDTO;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 媒体能力准入闸门（X01，AT-068）：未声明/未探测确认的能力必须在解析客户端与任何外发之前拒绝；
 * 准入通过后每个媒体请求只调用一次端口，端口失败映射为稳定平台错误码。
 */
class AiMediaCapabilityGateTest {

    private static final Long ENDPOINT_ID = 9L;

    private static final MediaFileRef IMAGE = MediaFileRef.of(1L, "image/png", 16L);

    private AiModelEndpointService endpointService;

    private AiModelCapabilityProbeService probeService;

    private AiModelClientResolver clientResolver;

    private CountingMediaPort port;

    private AiMediaCapabilityGate gate;

    @BeforeEach
    void setUp() {
        endpointService = mock(AiModelEndpointService.class);
        probeService = mock(AiModelCapabilityProbeService.class);
        clientResolver = mock(AiModelClientResolver.class);
        port = new CountingMediaPort();
        gate = new AiMediaCapabilityGate(endpointService, probeService, clientResolver);

        when(endpointService.getEnabledEndpoint(ENDPOINT_ID)).thenReturn(endpoint(2));
        when(endpointService.getRevisions(ENDPOINT_ID)).thenReturn(List.of(revision("TEXT,IMAGE_UNDERSTANDING")));
        when(probeService.getLatestResults(ENDPOINT_ID))
                .thenReturn(List.of(probe("IMAGE_UNDERSTANDING", "SUPPORTED", 2)));
        when(clientResolver.resolve(ENDPOINT_ID)).thenReturn(port);
    }

    private static AiModelEndpointDO endpoint(int configRevision) {
        return new AiModelEndpointDO()
                .setId(ENDPOINT_ID)
                .setEnabled(true)
                .setConfigRevision(configRevision)
                .setCredentialRevision(1);
    }

    private static AiModelEndpointRevisionDO revision(String capabilities) {
        return new AiModelEndpointRevisionDO()
                .setEndpointId(ENDPOINT_ID)
                .setRevision(1)
                .setModelId("gpt-4o-mini")
                .setCapabilities(capabilities);
    }

    private static AiModelProbeResultDTO probe(String kind, String status, Integer configRevision) {
        return new AiModelProbeResultDTO().setProbeKind(kind).setStatus(status).setConfigRevision(configRevision);
    }

    private void assertRejectedWithoutOutbound(String scene, Runnable call) {
        assertThatThrownBy(call::run)
                .as(scene)
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                        .isEqualTo(AI_MODEL_CAPABILITY_NOT_ENABLED.getCode()));
        assertThat(port.mediaCalls).as("%s：准入失败不得调用媒体端口", scene).hasValue(0);
        verify(clientResolver, never()).resolve(any());
    }

    @Test
    void undeclaredCapabilityRejectsBeforeResolvingClient() {
        when(endpointService.getRevisions(ENDPOINT_ID)).thenReturn(List.of(revision("TEXT,EMBEDDING")));
        when(probeService.getLatestResults(ENDPOINT_ID))
                .thenReturn(List.of(probe("IMAGE_UNDERSTANDING", "SUPPORTED", 2)));

        assertRejectedWithoutOutbound(
                "未声明的媒体能力",
                () -> gate.invoke(ENDPOINT_ID, ModelCapability.IMAGE_UNDERSTANDING, p -> p.understandImage(request())));
    }

    @Test
    void endpointWithoutRevisionsOrCapabilitiesRejectsWithoutOutbound() {
        when(endpointService.getRevisions(ENDPOINT_ID)).thenReturn(List.of());
        assertRejectedWithoutOutbound(
                "端点没有配置版本", () -> gate.assertAdmitted(ENDPOINT_ID, ModelCapability.IMAGE_UNDERSTANDING));

        when(endpointService.getRevisions(ENDPOINT_ID)).thenReturn(List.of(revision(null)));
        assertRejectedWithoutOutbound(
                "配置版本没有能力集合", () -> gate.assertAdmitted(ENDPOINT_ID, ModelCapability.IMAGE_UNDERSTANDING));
    }

    @Test
    void declaredButNeverProbedRejectsWithoutOutbound() {
        when(probeService.getLatestResults(ENDPOINT_ID)).thenReturn(List.of());

        assertRejectedWithoutOutbound(
                "声明后从未探测", () -> gate.assertAdmitted(ENDPOINT_ID, ModelCapability.IMAGE_UNDERSTANDING));
    }

    @Test
    void failedOrUnsupportedProbeRejectsWithoutOutbound() {
        when(probeService.getLatestResults(ENDPOINT_ID)).thenReturn(List.of(probe("IMAGE_UNDERSTANDING", "FAILED", 2)));
        assertRejectedWithoutOutbound(
                "探测失败", () -> gate.assertAdmitted(ENDPOINT_ID, ModelCapability.IMAGE_UNDERSTANDING));

        when(probeService.getLatestResults(ENDPOINT_ID))
                .thenReturn(List.of(probe("IMAGE_UNDERSTANDING", "UNSUPPORTED", 2)));
        assertRejectedWithoutOutbound(
                "探测结论不支持", () -> gate.assertAdmitted(ENDPOINT_ID, ModelCapability.IMAGE_UNDERSTANDING));
    }

    @Test
    void staleProbeConclusionRejectsAfterConfigurationChange() {
        when(probeService.getLatestResults(ENDPOINT_ID))
                .thenReturn(List.of(probe("IMAGE_UNDERSTANDING", "SUPPORTED", 1)));

        assertRejectedWithoutOutbound(
                "配置版本变化后结论过期", () -> gate.assertAdmitted(ENDPOINT_ID, ModelCapability.IMAGE_UNDERSTANDING));
    }

    @Test
    void probeConfirmationOfAnotherKindDoesNotAdmit() {
        when(probeService.getLatestResults(ENDPOINT_ID)).thenReturn(List.of(probe("TEXT", "SUPPORTED", 2)));

        assertRejectedWithoutOutbound(
                "文本探测不能推断图片能力", () -> gate.assertAdmitted(ENDPOINT_ID, ModelCapability.IMAGE_UNDERSTANDING));
    }

    /**
     * AT-068：六个媒体能力逐个独立准入。每个能力都必须自己声明且自己的探测项被确认；
     * 未探测、只有别的媒体能力的结论、未声明三种情形都必须明确拒绝且零外发。
     */
    @Test
    void everyMediaCapabilityNeedsItsOwnDeclarationAndProbe() {
        for (ModelCapability capability : ModelCapability.values()) {
            if (!capability.isMedia()) {
                continue;
            }
            String name = capability.name();
            String probeKind = capability.probeKind().name();

            when(endpointService.getRevisions(ENDPOINT_ID)).thenReturn(List.of(revision(name)));
            when(probeService.getLatestResults(ENDPOINT_ID)).thenReturn(List.of(probe(probeKind, "SUPPORTED", 2)));
            gate.assertAdmitted(ENDPOINT_ID, capability);

            when(probeService.getLatestResults(ENDPOINT_ID)).thenReturn(List.of());
            assertRejectedWithoutOutbound(name + " 声明后从未探测", () -> gate.assertAdmitted(ENDPOINT_ID, capability));

            when(probeService.getLatestResults(ENDPOINT_ID))
                    .thenReturn(List.of(probe(otherMediaProbeKind(capability), "SUPPORTED", 2)));
            assertRejectedWithoutOutbound(name + " 只有其它媒体能力的探测结论", () -> gate.assertAdmitted(ENDPOINT_ID, capability));

            when(endpointService.getRevisions(ENDPOINT_ID)).thenReturn(List.of(revision("TEXT")));
            when(probeService.getLatestResults(ENDPOINT_ID)).thenReturn(List.of(probe(probeKind, "SUPPORTED", 2)));
            assertRejectedWithoutOutbound(name + " 未声明", () -> gate.assertAdmitted(ENDPOINT_ID, capability));
        }

        assertThat(port.mediaCalls).as("全部准入校验都不得调用媒体端口").hasValue(0);
        verify(clientResolver, never()).resolve(any());
    }

    /** 另一个媒体能力的探测项：用于证明媒体能力之间不能互相推断。 */
    private static String otherMediaProbeKind(ModelCapability capability) {
        return Arrays.stream(ModelCapability.values())
                .filter(ModelCapability::isMedia)
                .filter(other -> other != capability)
                .findFirst()
                .map(other -> other.probeKind().name())
                .orElseThrow();
    }

    @Test
    void disabledEndpointRejectsWithEndpointCodeBeforeOutbound() {
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID)).thenThrow(exception(AI_MODEL_ENDPOINT_DISABLED));

        assertThatThrownBy(() -> gate.assertAdmitted(ENDPOINT_ID, ModelCapability.IMAGE_UNDERSTANDING))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                        .isEqualTo(AI_MODEL_ENDPOINT_DISABLED.getCode()));
        assertThat(port.mediaCalls).hasValue(0);
        verify(clientResolver, never()).resolve(any());
    }

    @Test
    void nonMediaCapabilityIsRejectedAsProgrammingError() {
        assertThatThrownBy(() -> gate.assertAdmitted(ENDPOINT_ID, ModelCapability.TEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("媒体能力");
        assertThatThrownBy(() -> gate.assertAdmitted(ENDPOINT_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("媒体能力");

        assertThat(port.mediaCalls).hasValue(0);
        verify(clientResolver, never()).resolve(any());
    }

    @Test
    void admittedCapabilityInvokesPortExactlyOnce() {
        MediaTextResponse response =
                gate.invoke(ENDPOINT_ID, ModelCapability.IMAGE_UNDERSTANDING, p -> p.understandImage(request()));

        assertThat(response.text()).isEqualTo("一只猫");
        assertThat(port.mediaCalls).hasValue(1);
        verify(clientResolver).resolve(ENDPOINT_ID);
    }

    @Test
    void admissionCheckAloneDoesNotResolveOrCallPort() {
        gate.assertAdmitted(ENDPOINT_ID, ModelCapability.IMAGE_UNDERSTANDING);

        assertThat(port.mediaCalls).hasValue(0);
        verify(clientResolver, never()).resolve(any());
    }

    @Test
    void portModelExceptionsMapToStablePlatformCodesWithoutUpstreamText() {
        assertMappedFailure(ModelException.Reason.CAPABILITY_NOT_ENABLED, AI_MODEL_CAPABILITY_NOT_ENABLED);
        assertMappedFailure(ModelException.Reason.MEDIA_INPUT_INVALID, AI_MEDIA_REQUEST_INVALID);
        assertMappedFailure(ModelException.Reason.MEDIA_INPUT_TYPE_UNSUPPORTED, AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
        assertMappedFailure(ModelException.Reason.MEDIA_INPUT_TOO_LARGE, AI_MEDIA_INPUT_TOO_LARGE);
        assertMappedFailure(ModelException.Reason.MEDIA_INPUT_DURATION_EXCEEDED, AI_MEDIA_INPUT_DURATION_EXCEEDED);
        assertMappedFailure(ModelException.Reason.MEDIA_OUTPUT_EMPTY, AI_MEDIA_OUTPUT_EMPTY);
        assertMappedFailure(ModelException.Reason.TIMEOUT, AI_MODEL_CALL_FAILED);
        assertMappedFailure(ModelException.Reason.TARGET_NOT_ALLOWED, AI_MODEL_OUTBOUND_BLOCKED);
    }

    private void assertMappedFailure(ModelException.Reason reason, ErrorCode expected) {
        ModelPort failing = mock(ModelPort.class);
        when(clientResolver.resolve(ENDPOINT_ID)).thenReturn(failing);
        when(failing.understandImage(any(ImageUnderstandingRequest.class)))
                .thenThrow(new ModelException(reason, "vendor raw body must not leak"));

        assertThatThrownBy(() -> gate.invoke(
                        ENDPOINT_ID, ModelCapability.IMAGE_UNDERSTANDING, p -> p.understandImage(request())))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> {
                    ServiceException serviceException = (ServiceException) exception;
                    assertThat(serviceException.getCode()).isEqualTo(expected.getCode());
                    assertThat(serviceException.getMessage())
                            .doesNotContain("vendor")
                            .isEqualTo(expected.getMsg());
                });
    }

    private static ImageUnderstandingRequest request() {
        return ImageUnderstandingRequest.of("gpt-4o-mini", IMAGE, "描述图片");
    }

    /** 会真实计数的媒体端口：准入失败时计数必须为 0，准入通过时恰好 1。 */
    private static final class CountingMediaPort implements ModelPort {

        private final AtomicInteger mediaCalls = new AtomicInteger();

        @Override
        public Set<ModelCapability> capabilities() {
            return Set.of(ModelCapability.IMAGE_UNDERSTANDING, ModelCapability.IMAGE_GENERATION);
        }

        @Override
        public ModelResponse generate(ModelRequest request) {
            throw new AssertionError("媒体路径不得回退为文本调用");
        }

        @Override
        public MediaTextResponse understandImage(ImageUnderstandingRequest request) {
            mediaCalls.incrementAndGet();
            return new MediaTextResponse("一只猫", ModelUsage.UNKNOWN, "gpt-4o-mini", "stop");
        }

        @Override
        public MediaTextResponse recognizeImageText(ImageOcrRequest request) {
            mediaCalls.incrementAndGet();
            return new MediaTextResponse("识别文本", ModelUsage.UNKNOWN, "gpt-4o-mini", "stop");
        }

        @Override
        public ImageResult generateImage(ImageGenerationRequest request) {
            mediaCalls.incrementAndGet();
            return ImageResult.of(
                    new MediaArtifact("image/png", "png".getBytes(StandardCharsets.UTF_8), null, 512, 512, null),
                    ModelUsage.UNKNOWN,
                    "gpt-4o-mini");
        }
    }
}
