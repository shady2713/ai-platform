package com.basicframework.framework.ai.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.core.model.media.ImageEditRequest;
import com.basicframework.framework.ai.core.model.media.ImageGenerationRequest;
import com.basicframework.framework.ai.core.model.media.ImageOcrRequest;
import com.basicframework.framework.ai.core.model.media.ImageUnderstandingRequest;
import com.basicframework.framework.ai.core.model.media.MediaFileRef;
import com.basicframework.framework.ai.core.model.media.SpeechSynthesisRequest;
import com.basicframework.framework.ai.core.model.media.SpeechTranscriptionRequest;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * X01 媒体端口默认实现：未覆盖媒体能力的适配器必须给出"能力未开通"的明确拒绝，
 * 不得静默回退为文本调用，也不得产生任何调用计数（无隐藏外发）。
 */
class MediaPortDefaultsTest {

    private static final MediaFileRef IMAGE = MediaFileRef.of(1L, "image/png", 16L);

    private static final MediaFileRef AUDIO = MediaFileRef.of(2L, "audio/wav", 16L);

    /** 只实现文本能力的适配器；文本调用计数用于证明媒体拒绝不会回退为文本请求。 */
    private static final class TextOnlyPort implements ModelPort {

        private final AtomicInteger textCalls = new AtomicInteger();

        @Override
        public Set<ModelCapability> capabilities() {
            return Set.of(ModelCapability.TEXT);
        }

        @Override
        public ModelResponse generate(ModelRequest request) {
            textCalls.incrementAndGet();
            return new ModelResponse("ok", ModelUsage.UNKNOWN, null, "gpt-4o-mini", null);
        }
    }

    @Test
    void mediaDefaultsRejectWithCapabilityNotEnabled() {
        TextOnlyPort port = new TextOnlyPort();

        assertRejected(
                () -> port.understandImage(ImageUnderstandingRequest.of("m", IMAGE, "描述")), "IMAGE_UNDERSTANDING");
        assertRejected(() -> port.recognizeImageText(ImageOcrRequest.of("m", IMAGE)), "IMAGE_OCR");
        assertRejected(() -> port.generateImage(ImageGenerationRequest.of("m", "画一张流程图")), "IMAGE_GENERATION");
        assertRejected(() -> port.editImage(ImageEditRequest.of("m", IMAGE, "去掉水印")), "IMAGE_EDIT");
        assertRejected(() -> port.transcribeSpeech(SpeechTranscriptionRequest.of("m", AUDIO)), "SPEECH_TO_TEXT");
        assertRejected(() -> port.synthesizeSpeech(SpeechSynthesisRequest.of("m", "你好")), "TEXT_TO_SPEECH");

        assertThat(port.textCalls).as("拒绝不得静默回退为文本调用").hasValue(0);
        assertThat(port.capabilities()).containsExactly(ModelCapability.TEXT);
    }

    @Test
    void probeDefaultsToAdapterNotImplementedWithoutAnyCall() {
        TextOnlyPort port = new TextOnlyPort();

        ModelProbeKind[] mediaKinds = {
            ModelProbeKind.IMAGE_UNDERSTANDING,
            ModelProbeKind.IMAGE_OCR,
            ModelProbeKind.IMAGE_GENERATION,
            ModelProbeKind.IMAGE_EDIT,
            ModelProbeKind.SPEECH_TO_TEXT,
            ModelProbeKind.TEXT_TO_SPEECH
        };
        for (ModelProbeKind kind : mediaKinds) {
            ModelProbeResult result = port.probe(kind);
            assertThat(result.kind()).isEqualTo(kind);
            assertThat(result.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
            assertThat(result.detailCode()).isEqualTo(ModelProbeResult.CODE_ADAPTER_NOT_IMPLEMENTED);
            assertThat(result.latencyMillis()).isZero();
        }
        assertThat(port.textCalls).as("默认探测不得发起厂商调用").hasValue(0);
    }

    private static void assertRejected(Runnable call, String capability) {
        assertThatThrownBy(call::run).isInstanceOf(ModelException.class).satisfies(exception -> {
            ModelException modelException = (ModelException) exception;
            assertThat(modelException.getReason()).isEqualTo(ModelException.Reason.CAPABILITY_NOT_ENABLED);
            assertThat(modelException.getMessage()).contains(capability);
        });
    }
}
