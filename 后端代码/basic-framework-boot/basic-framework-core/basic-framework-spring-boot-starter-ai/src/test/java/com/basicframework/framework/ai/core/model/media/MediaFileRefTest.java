package com.basicframework.framework.ai.core.model.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 媒体输入引用契约（X01）：只承载平台私有文件与元数据，构造即校验，MIME/摘要归一化。
 */
class MediaFileRefTest {

    private static final String SHA256 = "a".repeat(64);

    @Test
    void normalizesMimeTypeAndDigest() {
        MediaFileRef ref = new MediaFileRef(9L, "  IMAGE/PNG ", 2048L, SHA256.toUpperCase());

        assertThat(ref.fileId()).isEqualTo(9L);
        assertThat(ref.mimeType()).isEqualTo("image/png");
        assertThat(ref.sizeBytes()).isEqualTo(2048L);
        assertThat(ref.sha256()).isEqualTo(SHA256);
    }

    @Test
    void shorthandAllowsMissingDigest() {
        MediaFileRef ref = MediaFileRef.of(9L, "audio/wav", 1024L);

        assertThat(ref.sha256()).isNull();
        assertThat(ref.mimeType()).isEqualTo("audio/wav");
    }

    @Test
    void rejectsInvalidFileIdentity() {
        assertThatThrownBy(() -> MediaFileRef.of(null, "image/png", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("编号");
        assertThatThrownBy(() -> MediaFileRef.of(0L, "image/png", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("编号");
        assertThatThrownBy(() -> MediaFileRef.of(-1L, "image/png", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("编号");
        assertThatThrownBy(() -> MediaFileRef.of(9L, "image/png", 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字节数");
    }

    @Test
    void rejectsInvalidMimeType() {
        assertThatThrownBy(() -> MediaFileRef.of(9L, null, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIME");
        assertThatThrownBy(() -> MediaFileRef.of(9L, "  ", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIME");
        assertThatThrownBy(() -> MediaFileRef.of(9L, "image", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIME");
        assertThatThrownBy(() -> MediaFileRef.of(9L, "image/", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIME");
    }

    @Test
    void rejectsInvalidDigest() {
        assertThatThrownBy(() -> new MediaFileRef(9L, "image/png", 1L, "abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
        assertThatThrownBy(() -> new MediaFileRef(9L, "image/png", 1L, "z".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
        // 空摘要按"未提供"处理
        assertThat(new MediaFileRef(9L, "image/png", 1L, " ").sha256()).isNull();
    }
}
