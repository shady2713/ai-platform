package com.basicframework.framework.ai.provider.springai;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelProbeKind;
import com.basicframework.framework.ai.core.model.ModelProbeResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;

/**
 * X02 媒体探测（图片理解 / OCR）：用平台合成夹具做真实多模态调用，未实现的能力不外发。
 *
 * <p>三条被钉住的语义：
 * <ol>
 *   <li><b>确实带了图片</b>：探测请求是多模态消息（含 image/png 媒体），不是纯文本调用；</li>
 *   <li><b>空文本不算通过</b>：返回空白文本记 UNSUPPORTED + {@code NO_TEXT_RETURNED}，不静默成功；</li>
 *   <li><b>未声明能力不发厂商调用</b>：计数为 0，返回 UNSUPPORTED + {@code CAPABILITY_NOT_DECLARED}。</li>
 * </ol>
 * 生成/编辑与语音能力属 X03/X04：在本适配器里保持"适配器未实现"且**不发起任何调用**。
 */
class SpringAiMediaProbeTest {

    /** PNG 文件头：89 50 4E 47。 */
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};

    @Test
    void imageUnderstandingProbeSendsSyntheticPngAndPassesOnNonEmptyText() {
        List<Prompt> prompts = new ArrayList<>();
        SpringAiModelClient client =
                new SpringAiModelClient(VendorChatResponses.snapshot(ModelCapability.IMAGE_UNDERSTANDING), prompt -> {
                    prompts.add(prompt);
                    return VendorChatResponses.text("一张蓝色方形图片");
                });

        ModelProbeResult probe = client.probe(ModelProbeKind.IMAGE_UNDERSTANDING);

        assertThat(probe.isSupported()).isTrue();
        assertThat(probe.detailCode()).isNull();
        UserMessage message = prompts.get(0).getUserMessages().get(0);
        assertThat(message.getMedia()).as("探测必须是真实的多模态调用").hasSize(1);
        Media media = message.getMedia().get(0);
        assertThat(media.getMimeType().toString()).isEqualTo("image/png");
        assertThat(media.getDataAsByteArray()).startsWith(PNG_MAGIC);
        assertThat(message.getText()).contains("描述");
    }

    @Test
    void ocrProbeUsesItsOwnFixtureAndEmptyTextIsUnsupported() {
        List<Prompt> prompts = new ArrayList<>();
        SpringAiModelClient client =
                new SpringAiModelClient(VendorChatResponses.snapshot(ModelCapability.IMAGE_OCR), prompt -> {
                    prompts.add(prompt);
                    return VendorChatResponses.text("   ");
                });

        ModelProbeResult probe = client.probe(ModelProbeKind.IMAGE_OCR);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
        assertThat(probe.detailCode()).isEqualTo(ModelProbeResult.CODE_NO_TEXT_RETURNED);
        UserMessage message = prompts.get(0).getUserMessages().get(0);
        assertThat(message.getMedia()).hasSize(1);
        assertThat(message.getText()).contains("文字");
    }

    @Test
    void undeclaredMediaCapabilityIsUnsupportedWithoutAnyVendorCall() {
        AtomicInteger vendorCalls = new AtomicInteger();
        SpringAiModelClient client =
                new SpringAiModelClient(VendorChatResponses.snapshot(ModelCapability.TEXT), prompt -> {
                    vendorCalls.incrementAndGet();
                    return VendorChatResponses.text("不应发生");
                });

        ModelProbeResult understanding = client.probe(ModelProbeKind.IMAGE_UNDERSTANDING);
        ModelProbeResult ocr = client.probe(ModelProbeKind.IMAGE_OCR);

        assertThat(understanding.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
        assertThat(understanding.detailCode()).isEqualTo(ModelProbeResult.CODE_CAPABILITY_NOT_DECLARED);
        assertThat(ocr.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
        assertThat(ocr.detailCode()).isEqualTo(ModelProbeResult.CODE_CAPABILITY_NOT_DECLARED);
        assertThat(vendorCalls.get()).as("未声明能力不得发起厂商调用").isZero();
    }

    @Test
    void generationAndSpeechProbesRemainAdapterNotImplementedWithoutAnyCall() {
        AtomicInteger vendorCalls = new AtomicInteger();
        SpringAiModelClient client = new SpringAiModelClient(
                VendorChatResponses.snapshot(
                        ModelCapability.IMAGE_GENERATION,
                        ModelCapability.IMAGE_EDIT,
                        ModelCapability.SPEECH_TO_TEXT,
                        ModelCapability.TEXT_TO_SPEECH),
                prompt -> {
                    vendorCalls.incrementAndGet();
                    return VendorChatResponses.text("不应发生");
                });

        for (ModelProbeKind kind : new ModelProbeKind[] {
            ModelProbeKind.IMAGE_GENERATION,
            ModelProbeKind.IMAGE_EDIT,
            ModelProbeKind.SPEECH_TO_TEXT,
            ModelProbeKind.TEXT_TO_SPEECH
        }) {
            ModelProbeResult probe = client.probe(kind);
            assertThat(probe.status())
                    .as("%s 未实现时必须是 UNSUPPORTED", kind)
                    .isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
            assertThat(probe.detailCode()).isEqualTo(ModelProbeResult.CODE_ADAPTER_NOT_IMPLEMENTED);
        }
        assertThat(vendorCalls.get()).as("未实现的媒体能力不得发起任何厂商调用").isZero();
    }

    @Test
    void upstreamFailureIsRecordedAsStableFailedReason() {
        SpringAiModelClient client =
                new SpringAiModelClient(VendorChatResponses.snapshot(ModelCapability.IMAGE_OCR), prompt -> {
                    throw new IllegalStateException("vendor body sk-must-not-leak");
                });

        ModelProbeResult probe = client.probe(ModelProbeKind.IMAGE_OCR);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.FAILED);
        assertThat(probe.detailCode()).isEqualTo("UPSTREAM_FAILED");
        assertThat(probe.toString()).doesNotContain("sk-must-not-leak");
    }

    @Test
    void understandingProbeWithBlankTextIsUnsupported() {
        SpringAiModelClient client = new SpringAiModelClient(
                VendorChatResponses.snapshot(ModelCapability.IMAGE_UNDERSTANDING),
                prompt -> VendorChatResponses.text("   "));

        ModelProbeResult probe = client.probe(ModelProbeKind.IMAGE_UNDERSTANDING);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
        assertThat(probe.detailCode())
                .as("空白文本不算通过：空回复按『未返回文本』记稳定原因")
                .isEqualTo(ModelProbeResult.CODE_NO_TEXT_RETURNED);
    }

    @Test
    void ocrProbeWithRecognizedTextIsSupported() {
        SpringAiModelClient client = new SpringAiModelClient(
                VendorChatResponses.snapshot(ModelCapability.IMAGE_OCR),
                prompt -> VendorChatResponses.text("合同金额 290.00 元"));

        ModelProbeResult probe = client.probe(ModelProbeKind.IMAGE_OCR);

        assertThat(probe.isSupported()).isTrue();
        assertThat(probe.detailCode()).isNull();
    }

    @Test
    void visionCallWithoutResponseIsTreatedAsNoTextInsteadOfSuccess() {
        SpringAiModelClient client =
                new SpringAiModelClient(VendorChatResponses.snapshot(ModelCapability.IMAGE_OCR), prompt -> null);

        ModelProbeResult probe = client.probe(ModelProbeKind.IMAGE_OCR);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
        assertThat(probe.detailCode()).isEqualTo(ModelProbeResult.CODE_NO_TEXT_RETURNED);
    }

    @Test
    void vendorTimeoutOnMediaProbeIsReportedAsStableFailureReason() {
        SpringAiModelClient client =
                new SpringAiModelClient(VendorChatResponses.snapshot(ModelCapability.IMAGE_OCR), prompt -> {
                    throw new java.util.concurrent.CompletionException(
                            new java.util.concurrent.TimeoutException("vendor-timeout-must-not-leak"));
                });

        ModelProbeResult probe = client.probe(ModelProbeKind.IMAGE_OCR);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.FAILED);
        assertThat(probe.detailCode()).as("失败只落稳定原因码").isNotBlank();
        assertThat(probe.toString()).doesNotContain("vendor-timeout-must-not-leak");
    }
}
