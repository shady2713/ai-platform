package com.basicframework.framework.ai.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * X01 多模态词汇契约：媒体能力与文本能力共用同一枚举，探测项与能力 1:1，
 * 且既有已发布取值顺序与语义不变（新增只追加，不重排）。
 */
class MultimodalVocabularyTest {

    private static final Set<ModelCapability> MEDIA_CAPABILITIES = Set.of(
            ModelCapability.IMAGE_UNDERSTANDING,
            ModelCapability.IMAGE_OCR,
            ModelCapability.IMAGE_GENERATION,
            ModelCapability.IMAGE_EDIT,
            ModelCapability.SPEECH_TO_TEXT,
            ModelCapability.TEXT_TO_SPEECH);

    @Test
    void vocabularyKeepsPublishedValuesAndAppendsMediaCapabilities() {
        assertThat(Arrays.stream(ModelCapability.values()).map(Enum::name))
                .containsExactly(
                        "TEXT",
                        "TEXT_STREAM",
                        "STRUCTURED_OUTPUT",
                        "TOOL_CALLING",
                        "EMBEDDING",
                        "IMAGE_UNDERSTANDING",
                        "IMAGE_OCR",
                        "IMAGE_GENERATION",
                        "IMAGE_EDIT",
                        "SPEECH_TO_TEXT",
                        "TEXT_TO_SPEECH");

        assertThat(Arrays.stream(ModelProbeKind.values()).map(Enum::name))
                .containsExactly(
                        "CONNECTIVITY",
                        "TEXT",
                        "TEXT_STREAM",
                        "STRUCTURED_OUTPUT",
                        "TOOL_CALLING",
                        "EMBEDDING",
                        "IMAGE_UNDERSTANDING",
                        "IMAGE_OCR",
                        "IMAGE_GENERATION",
                        "IMAGE_EDIT",
                        "SPEECH_TO_TEXT",
                        "TEXT_TO_SPEECH");
    }

    @Test
    void onlyMultimodalCapabilitiesAreMarkedAsMedia() {
        for (ModelCapability capability : ModelCapability.values()) {
            assertThat(capability.isMedia())
                    .as("%s 的媒体标记", capability)
                    .isEqualTo(MEDIA_CAPABILITIES.contains(capability));
        }
    }

    @Test
    void everyCapabilityHasSameNamedProbeKind() {
        for (ModelCapability capability : ModelCapability.values()) {
            assertThat(capability.probeKind().name()).as("%s 的探测类别", capability).isEqualTo(capability.name());
        }
    }

    @Test
    void everyMediaCapabilityHasDedicatedProbeKind() {
        Set<String> probeKinds =
                Arrays.stream(ModelProbeKind.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet());

        for (ModelCapability media : MEDIA_CAPABILITIES) {
            assertThat(probeKinds).contains(media.probeKind().name());
        }
        // CONNECTIVITY 只证明可达，不对应任何可发布能力
        assertThat(Arrays.stream(ModelCapability.values())
                        .map(ModelCapability::probeKind)
                        .anyMatch(kind -> kind == ModelProbeKind.CONNECTIVITY))
                .isFalse();
    }

    @Test
    void mediaRejectionVocabularyIsStable() {
        assertThat(ModelException.Reason.valueOf("CAPABILITY_NOT_ENABLED")).isNotNull();
        assertThat(ModelException.Reason.valueOf("MEDIA_INPUT_INVALID")).isNotNull();
        assertThat(ModelException.Reason.valueOf("MEDIA_INPUT_TYPE_UNSUPPORTED"))
                .isNotNull();
        assertThat(ModelException.Reason.valueOf("MEDIA_INPUT_TOO_LARGE")).isNotNull();
        assertThat(ModelException.Reason.valueOf("MEDIA_INPUT_DURATION_EXCEEDED"))
                .isNotNull();
        assertThat(ModelException.Reason.valueOf("MEDIA_OUTPUT_EMPTY")).isNotNull();
        assertThat(ModelProbeResult.CODE_NO_IMAGE_RETURNED).isEqualTo("NO_IMAGE_RETURNED");
        assertThat(ModelProbeResult.CODE_NO_AUDIO_RETURNED).isEqualTo("NO_AUDIO_RETURNED");
    }
}
