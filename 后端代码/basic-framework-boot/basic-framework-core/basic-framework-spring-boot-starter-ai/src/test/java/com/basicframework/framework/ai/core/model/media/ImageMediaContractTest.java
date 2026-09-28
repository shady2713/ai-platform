package com.basicframework.framework.ai.core.model.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.core.model.ModelUsage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 图片能力契约（X01）：理解/OCR/生成/编辑的请求校验与产物响应不变量。
 */
class ImageMediaContractTest {

    private static final MediaFileRef IMAGE = MediaFileRef.of(9L, "image/png", 2048L);

    private static final MediaArtifact ARTIFACT =
            new MediaArtifact("image/png", "png".getBytes(StandardCharsets.UTF_8), null, 1024, 1024, null);

    @Test
    void understandingRequestRequiresImageAndInstruction() {
        ImageUnderstandingRequest request = ImageUnderstandingRequest.of("gpt-4o-mini", IMAGE, "描述业务流程");

        assertThat(request.modelId()).isEqualTo("gpt-4o-mini");
        assertThat(request.image()).isEqualTo(IMAGE);
        assertThat(request.instruction()).isEqualTo("描述业务流程");
        assertThat(request.timeout()).isNull();
        assertThat(new ImageUnderstandingRequest("m", IMAGE, "描述", Duration.ofSeconds(5)).timeout())
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void understandingRequestRejectsMissingParts() {
        assertThatThrownBy(() -> ImageUnderstandingRequest.of(null, IMAGE, "描述"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型标识");
        assertThatThrownBy(() -> ImageUnderstandingRequest.of("m", null, "描述"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图片引用");
        assertThatThrownBy(() -> ImageUnderstandingRequest.of("m", IMAGE, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("理解指令");
    }

    @Test
    void ocrRequestNormalizesLanguageHint() {
        assertThat(ImageOcrRequest.of("m", IMAGE).languageHint()).isNull();
        assertThat(ImageOcrRequest.of("m", IMAGE).image()).isEqualTo(IMAGE);
        assertThat(new ImageOcrRequest("m", IMAGE, " ", null).languageHint()).isNull();
        assertThat(new ImageOcrRequest("m", IMAGE, "zh", Duration.ofSeconds(5)).languageHint())
                .isEqualTo("zh");

        assertThatThrownBy(() -> ImageOcrRequest.of(" ", IMAGE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型标识");
        assertThatThrownBy(() -> ImageOcrRequest.of("m", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图片引用");
    }

    @Test
    void mediaTextResponseRequiresTextAndNormalizesUsage() {
        MediaTextResponse response = new MediaTextResponse("识别结果", ModelUsage.of(5, 1), "m", "stop");

        assertThat(response.text()).isEqualTo("识别结果");
        assertThat(response.usage().totalTokens()).isEqualTo(6);
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(new MediaTextResponse("识别结果", null, "m", null).usage()).isEqualTo(ModelUsage.UNKNOWN);

        assertThatThrownBy(() -> new MediaTextResponse("  ", null, "m", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("媒体文本输出");
        assertThatThrownBy(() -> new MediaTextResponse(null, null, "m", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("媒体文本输出");
    }

    @Test
    void generationRequestAppliesPlatformDefaults() {
        ImageGenerationRequest request = ImageGenerationRequest.of("m", "画一张流程图");

        assertThat(request.size()).isNull();
        assertThat(request.count()).isEqualTo(1);
        assertThat(request.outputFormat()).isEqualTo("png");
        assertThat(request.timeout()).isNull();
        assertThat(new ImageGenerationRequest("m", "画图", "1024X768", 2, "JPEG", Duration.ofSeconds(30)).size())
                .isEqualTo("1024x768");
        assertThat(new ImageGenerationRequest("m", "画图", "1024x768", 2, "JPEG", null).outputFormat())
                .isEqualTo("jpeg");
    }

    @Test
    void generationRequestRejectsOutOfRangeValues() {
        assertThatThrownBy(() -> ImageGenerationRequest.of("m", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("提示词");
        assertThatThrownBy(() -> new ImageGenerationRequest("m", "画图", "0x100", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尺寸");
        assertThatThrownBy(() -> new ImageGenerationRequest("m", "画图", "1024x", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尺寸");
        assertThatThrownBy(() -> new ImageGenerationRequest("m", "画图", "1x99999", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尺寸");
        assertThatThrownBy(() -> new ImageGenerationRequest("m", "画图", null, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("张数");
        assertThatThrownBy(() ->
                        new ImageGenerationRequest("m", "画图", null, ImageGenerationRequest.MAX_COUNT + 1, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("张数");
        assertThatThrownBy(() -> new ImageGenerationRequest("m", "画图", null, null, "gif", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("输出格式");
    }

    @Test
    void editRequestAppliesPlatformDefaultsAndValidates() {
        ImageEditRequest request = ImageEditRequest.of("m", IMAGE, "去掉水印");

        assertThat(request.source()).isEqualTo(IMAGE);
        assertThat(request.size()).isNull();
        assertThat(request.outputFormat()).isEqualTo("png");

        assertThatThrownBy(() -> ImageEditRequest.of("m", null, "去掉水印"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("底图");
        assertThatThrownBy(() -> ImageEditRequest.of("m", IMAGE, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("编辑指令");
        assertThatThrownBy(() -> new ImageEditRequest("m", IMAGE, "编辑", "99999x1", "png", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尺寸");
    }

    @Test
    void imageResultRequiresNonEmptyArtifactsAndNormalizesUsage() {
        ImageResult result = ImageResult.of(ARTIFACT, null, "dall-e-3");

        assertThat(result.images()).containsExactly(ARTIFACT);
        assertThat(result.usage()).isEqualTo(ModelUsage.UNKNOWN);
        assertThat(result.finishReason()).isNull();

        assertThatThrownBy(() -> new ImageResult(null, null, "m", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图片产物");
        assertThatThrownBy(() -> new ImageResult(List.of(), null, "m", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图片产物");
    }

    @Test
    void imageResultCopiesArtifactList() {
        List<MediaArtifact> mutable = new ArrayList<>();
        mutable.add(ARTIFACT);
        ImageResult result = new ImageResult(mutable, ModelUsage.of(1, 2), "m", "stop");

        mutable.clear();

        assertThat(result.images()).hasSize(1);
        assertThat(result.usage().totalTokens()).isEqualTo(3);
        assertThatThrownBy(() -> result.images().add(ARTIFACT)).isInstanceOf(UnsupportedOperationException.class);
    }
}
