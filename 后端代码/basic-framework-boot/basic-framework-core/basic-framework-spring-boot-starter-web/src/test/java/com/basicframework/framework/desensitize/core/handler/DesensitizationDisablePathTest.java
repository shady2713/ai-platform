package com.basicframework.framework.desensitize.core.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import com.basicframework.framework.common.util.spring.SpringExpressionUtils;
import com.basicframework.framework.desensitize.core.regex.annotation.RegexDesensitize;
import com.basicframework.framework.desensitize.core.regex.handler.DefaultRegexDesensitizationHandler;
import com.basicframework.framework.desensitize.core.slider.annotation.SliderDesensitize;
import com.basicframework.framework.desensitize.core.slider.handler.DefaultDesensitizationHandler;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * disable 表达式取值分支测试（regex 与 slider 两类）。
 *
 * <p>契约：disable 表达式解析为 true 时跳过脱敏返回原串，解析为 false 时仍正常脱敏。
 * 表达式解析结果通过 mock 静态 {@link SpringExpressionUtils} 注入；真实 BeanFactory
 * 下的表达式赋值由 DesensitizationExpressionIT 覆盖。
 */
class DesensitizationDisablePathTest {

    private final DefaultRegexDesensitizationHandler regexHandler = new DefaultRegexDesensitizationHandler();
    private final DefaultDesensitizationHandler sliderHandler = new DefaultDesensitizationHandler();

    @Test
    void regexDisableTrue_returnsOriginUnchanged() {
        RegexDesensitize annotation = regexAnnotation("123", "***", "always");

        try (MockedStatic<SpringExpressionUtils> expressionUtils = mockStatic(SpringExpressionUtils.class)) {
            expressionUtils
                    .when(() -> SpringExpressionUtils.parseExpression("always"))
                    .thenReturn(Boolean.TRUE);

            assertThat(regexHandler.desensitize("123456789", annotation)).isEqualTo("123456789");
        }
    }

    @Test
    void regexDisableFalse_stillMasks() {
        RegexDesensitize annotation = regexAnnotation("123", "***", "never");

        try (MockedStatic<SpringExpressionUtils> expressionUtils = mockStatic(SpringExpressionUtils.class)) {
            expressionUtils
                    .when(() -> SpringExpressionUtils.parseExpression("never"))
                    .thenReturn(Boolean.FALSE);

            assertThat(regexHandler.desensitize("123456789", annotation)).isEqualTo("***456789");
        }
    }

    @Test
    void sliderDisableTrue_returnsOriginUnchanged() {
        SliderDesensitize annotation = sliderAnnotation(3, 4, "*", "always");

        try (MockedStatic<SpringExpressionUtils> expressionUtils = mockStatic(SpringExpressionUtils.class)) {
            expressionUtils
                    .when(() -> SpringExpressionUtils.parseExpression("always"))
                    .thenReturn(Boolean.TRUE);

            assertThat(sliderHandler.desensitize("13812345678", annotation)).isEqualTo("13812345678");
        }
    }

    @Test
    void sliderDisableFalse_stillMasks() {
        SliderDesensitize annotation = sliderAnnotation(3, 4, "*", "never");

        try (MockedStatic<SpringExpressionUtils> expressionUtils = mockStatic(SpringExpressionUtils.class)) {
            expressionUtils
                    .when(() -> SpringExpressionUtils.parseExpression("never"))
                    .thenReturn(Boolean.FALSE);

            assertThat(sliderHandler.desensitize("13812345678", annotation)).isEqualTo("138****5678");
        }
    }

    private RegexDesensitize regexAnnotation(String regex, String replacer, String disable) {
        return (RegexDesensitize) Proxy.newProxyInstance(
                RegexDesensitize.class.getClassLoader(),
                new Class<?>[] {RegexDesensitize.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "regex" -> regex;
                    case "replacer" -> replacer;
                    case "disable" -> disable;
                    case "annotationType" -> RegexDesensitize.class;
                    default -> method.getDefaultValue();
                });
    }

    private SliderDesensitize sliderAnnotation(int prefixKeep, int suffixKeep, String replacer, String disable) {
        return (SliderDesensitize) Proxy.newProxyInstance(
                SliderDesensitize.class.getClassLoader(),
                new Class<?>[] {SliderDesensitize.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prefixKeep" -> prefixKeep;
                    case "suffixKeep" -> suffixKeep;
                    case "replacer" -> replacer;
                    case "disable" -> disable;
                    case "annotationType" -> SliderDesensitize.class;
                    default -> method.getDefaultValue();
                });
    }
}
