package com.basicframework.framework.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TerminalEnumTest {

    @Test
    void values_matchPublishedTerminalDictionary() {
        assertThat(TerminalEnum.ARRAYS).containsExactly(0, 10, 11, 20, 31, 32);
        assertThat(TerminalEnum.IOS_APP.getName()).isEqualTo("苹果 App");
        assertThat(TerminalEnum.ANDROID_APP.getName()).isEqualTo("安卓 App");
    }
}
