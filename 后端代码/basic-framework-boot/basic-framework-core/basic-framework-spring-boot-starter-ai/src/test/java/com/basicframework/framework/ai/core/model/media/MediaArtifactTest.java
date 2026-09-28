package com.basicframework.framework.ai.core.model.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 媒体产物契约（X01）：字节防御性复制、摘要自洽、尺寸/时长校验。
 */
class MediaArtifactTest {

    private static final byte[] PNG = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void computesDigestAndProtectsContent() {
        byte[] source = PNG.clone();
        MediaArtifact artifact = new MediaArtifact("IMAGE/PNG", source, null, 64, 64, null);

        assertThat(artifact.mimeType()).isEqualTo("image/png");
        assertThat(artifact.sizeBytes()).isEqualTo(PNG.length);
        assertThat(artifact.sha256()).hasSize(64).matches("[0-9a-f]{64}");

        // 入参数组之后被修改不影响产物
        source[0] = 0;
        assertThat(artifact.content()).isEqualTo(PNG);
        // 读出的数组被修改不影响产物
        artifact.content()[0] = 0;
        assertThat(artifact.content()).isEqualTo(PNG);
    }

    @Test
    void acceptsMatchingDeclaredDigest() {
        String digest = MediaValues.sha256Hex(PNG);

        MediaArtifact artifact = new MediaArtifact("image/png", PNG, digest.toUpperCase(), null, null, null);

        assertThat(artifact.sha256()).isEqualTo(digest);
    }

    @Test
    void rejectsDigestMismatchAndInvalidShapes() {
        assertThatThrownBy(() -> new MediaArtifact("image/png", PNG, "b".repeat(64), null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("摘要与内容不一致");
        assertThatThrownBy(() -> new MediaArtifact(null, PNG, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIME");
        assertThatThrownBy(() -> new MediaArtifact("image/png", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内容");
        assertThatThrownBy(() -> new MediaArtifact("image/png", new byte[0], null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内容");
        assertThatThrownBy(() -> new MediaArtifact("image/png", PNG, null, 0, 64, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("宽度");
        assertThatThrownBy(() -> new MediaArtifact("image/png", PNG, null, 64, -1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("高度");
        assertThatThrownBy(() -> new MediaArtifact("audio/mpeg", PNG, null, null, null, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时长");
    }

    @Test
    void allowsAudioMetadata() {
        MediaArtifact audio = new MediaArtifact("audio/mpeg", PNG, null, null, null, 1200L);

        assertThat(audio.width()).isNull();
        assertThat(audio.durationMillis()).isEqualTo(1200L);
    }
}
