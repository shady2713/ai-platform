package com.basicframework.module.system.framework.operatelog.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import com.basicframework.framework.dict.core.DictFrameworkUtils;
import com.basicframework.module.system.enums.DictTypeConstants;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * {@link BooleanParseFunction} 单元测试
 *
 */
class BooleanParseFunctionTest {

    private final BooleanParseFunction function = new BooleanParseFunction();

    @Test
    void metadata_exposesExpectedNameAndPreEvaluateSemantics() {
        assertThat(function.functionName()).isEqualTo(BooleanParseFunction.NAME);
        assertThat(function.functionName()).isEqualTo("getBoolean");
        assertThat(function.executeBefore()).isTrue();
    }

    @Test
    void apply_returnsEmptyForBlankValues() {
        assertThat(function.apply(null)).isEmpty();
        assertThat(function.apply("")).isEmpty();
    }

    @Test
    void apply_resolvesBooleanDictLabel() {
        try (MockedStatic<DictFrameworkUtils> dictFrameworkUtils = mockStatic(DictFrameworkUtils.class)) {
            dictFrameworkUtils
                    .when(() -> DictFrameworkUtils.parseDictDataLabel(DictTypeConstants.BOOLEAN_STRING, "true"))
                    .thenReturn("是");

            assertThat(function.apply("true")).isEqualTo("是");
        }
    }
}
