package com.basicframework.module.system.framework.operatelog.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import com.basicframework.framework.dict.core.DictFrameworkUtils;
import com.basicframework.module.system.enums.DictTypeConstants;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * {@link SexParseFunction} 单元测试
 *
 */
class SexParseFunctionTest {

    private final SexParseFunction function = new SexParseFunction();

    @Test
    void metadata_exposesExpectedNameAndPreEvaluateSemantics() {
        assertThat(function.functionName()).isEqualTo(SexParseFunction.NAME);
        assertThat(function.functionName()).isEqualTo("getSex");
        assertThat(function.executeBefore()).isTrue();
    }

    @Test
    void apply_returnsEmptyForBlankValues() {
        assertThat(function.apply(null)).isEmpty();
        assertThat(function.apply("")).isEmpty();
    }

    @Test
    void apply_resolvesUserSexDictLabel() {
        try (MockedStatic<DictFrameworkUtils> dictFrameworkUtils = mockStatic(DictFrameworkUtils.class)) {
            dictFrameworkUtils
                    .when(() -> DictFrameworkUtils.parseDictDataLabel(DictTypeConstants.USER_SEX, "1"))
                    .thenReturn("男");

            assertThat(function.apply("1")).isEqualTo("男");
        }
    }
}
