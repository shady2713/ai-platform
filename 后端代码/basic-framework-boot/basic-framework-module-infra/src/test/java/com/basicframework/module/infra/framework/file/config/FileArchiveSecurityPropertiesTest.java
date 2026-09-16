package com.basicframework.module.infra.framework.file.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * {@link FileArchiveSecurityProperties} 单元测试
 *
 */
class FileArchiveSecurityPropertiesTest {

    @Test
    void defaults_satisfyExpandSafetyBounds() {
        FileArchiveSecurityProperties properties = new FileArchiveSecurityProperties();

        assertThat(properties.getMaxEntries()).isEqualTo(1000);
        assertThat(properties.getMaxExpandedSize()).isEqualTo(DataSize.ofMegabytes(128));
        assertThat(properties.getMaxCompressionRatio()).isEqualTo(100D);
        assertThat(properties.isMaxExpandedSizeValid()).isTrue();
    }

    @Test
    void validation_rejectsZeroAndNullExpandedSize() {
        FileArchiveSecurityProperties properties = new FileArchiveSecurityProperties();

        properties.setMaxExpandedSize(DataSize.ofBytes(0));
        assertThat(properties.isMaxExpandedSizeValid()).isFalse();

        properties.setMaxExpandedSize(null);
        assertThat(properties.isMaxExpandedSizeValid()).isFalse();
    }

    @Test
    void accessors_roundTripConfiguredLimits() {
        FileArchiveSecurityProperties properties = new FileArchiveSecurityProperties();

        properties.setMaxEntries(50);
        properties.setMaxExpandedSize(DataSize.ofMegabytes(16));
        properties.setMaxCompressionRatio(10D);

        assertThat(properties.getMaxEntries()).isEqualTo(50);
        assertThat(properties.getMaxExpandedSize()).isEqualTo(DataSize.ofMegabytes(16));
        assertThat(properties.getMaxCompressionRatio()).isEqualTo(10D);
        assertThat(properties.isMaxExpandedSizeValid()).isTrue();
    }
}
