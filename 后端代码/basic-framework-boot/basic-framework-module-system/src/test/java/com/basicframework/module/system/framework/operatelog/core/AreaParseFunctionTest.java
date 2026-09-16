package com.basicframework.module.system.framework.operatelog.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import com.basicframework.framework.ip.core.utils.AreaUtils;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * {@link AreaParseFunction} 单元测试
 *
 */
class AreaParseFunctionTest {

    private final AreaParseFunction function = new AreaParseFunction();

    @Test
    void metadata_exposesExpectedNameAndPreEvaluateSemantics() {
        assertThat(function.functionName()).isEqualTo(AreaParseFunction.NAME);
        assertThat(function.functionName()).isEqualTo("getArea");
        assertThat(function.executeBefore()).isTrue();
    }

    @Test
    void apply_returnsEmptyForBlankValues() {
        assertThat(function.apply(null)).isEmpty();
        assertThat(function.apply("")).isEmpty();
    }

    @Test
    void apply_formatsAreaNameFromAreaId() {
        try (MockedStatic<AreaUtils> areaUtils = mockStatic(AreaUtils.class)) {
            areaUtils.when(() -> AreaUtils.format(310000)).thenReturn("上海");

            assertThat(function.apply(310000)).isEqualTo("上海");
            assertThat(function.apply("310000")).isEqualTo("上海");
        }
    }
}
