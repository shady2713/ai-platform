package com.basicframework.framework.common.util.object;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ObjectUtilsTest {

    @Test
    void equalsAny_supportsMatchesMissesAndNull() {
        assertThat(ObjectUtils.equalsAny(2, 1, 2, 3)).isTrue();
        assertThat(ObjectUtils.equalsAny(4, 1, 2, 3)).isFalse();
        assertThat(ObjectUtils.equalsAny(null, 1, null, 3)).isTrue();
    }
}
