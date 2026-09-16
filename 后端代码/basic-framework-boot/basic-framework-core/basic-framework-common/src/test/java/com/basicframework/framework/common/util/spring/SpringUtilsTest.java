package com.basicframework.framework.common.util.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import cn.hutool.extra.spring.SpringUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * {@link SpringUtils} 单元测试
 *
 */
class SpringUtilsTest {

    @Test
    void utilityClass_isInstantiable() {
        assertThat(new SpringUtils()).isNotNull();
    }

    @Test
    void isProd_returnsTrueWhenActiveProfileIsProd() {
        try (MockedStatic<SpringUtil> springUtil = mockStatic(SpringUtil.class)) {
            springUtil.when(SpringUtil::getActiveProfile).thenReturn("prod");

            assertThat(SpringUtils.isProd()).isTrue();
        }
    }

    @Test
    void isProd_returnsFalseForOtherProfiles() {
        try (MockedStatic<SpringUtil> springUtil = mockStatic(SpringUtil.class)) {
            springUtil.when(SpringUtil::getActiveProfile).thenReturn("dev");

            assertThat(SpringUtils.isProd()).isFalse();
        }
    }

    @Test
    void isProd_returnsFalseForNullProfile() {
        try (MockedStatic<SpringUtil> springUtil = mockStatic(SpringUtil.class)) {
            springUtil.when(SpringUtil::getActiveProfile).thenReturn(null);

            assertThat(SpringUtils.isProd()).isFalse();
        }
    }
}
