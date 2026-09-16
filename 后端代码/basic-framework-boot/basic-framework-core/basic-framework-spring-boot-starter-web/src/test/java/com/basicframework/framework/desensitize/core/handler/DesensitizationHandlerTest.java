package com.basicframework.framework.desensitize.core.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.desensitize.core.base.handler.DesensitizationHandler;
import com.basicframework.framework.desensitize.core.regex.annotation.EmailDesensitize;
import com.basicframework.framework.desensitize.core.regex.annotation.RegexDesensitize;
import com.basicframework.framework.desensitize.core.regex.handler.DefaultRegexDesensitizationHandler;
import com.basicframework.framework.desensitize.core.regex.handler.EmailDesensitizationHandler;
import com.basicframework.framework.desensitize.core.slider.annotation.SliderDesensitize;
import com.basicframework.framework.desensitize.core.slider.handler.DefaultDesensitizationHandler;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

/**
 * 脱敏处理器测试（regex 与 slider 两类）
 *
 * 脱敏是安全契约（sensitiveClass/masking 的落地方式），用动态代理构造
 * 注解实例覆盖默认与自定义路径；空 disable 表达式等价于启用，disable 表达式
 * 取值分支由 DesensitizationDisablePathTest 覆盖（mock 表达式结果），真实
 * Spring BeanFactory 下的表达式赋值由 DesensitizationExpressionIT 覆盖。
 */
class DesensitizationHandlerTest {

    private final EmailDesensitizationHandler emailHandler = new EmailDesensitizationHandler();
    private final DefaultRegexDesensitizationHandler regexHandler = new DefaultRegexDesensitizationHandler();
    private final DefaultDesensitizationHandler sliderHandler = new DefaultDesensitizationHandler();

    @Test
    void email_masksLocalPartAndKeepsDomain() {
        EmailDesensitize annotation = emailAnnotation("", "", "");
        assertThat(emailHandler.desensitize("example@gmail.com", annotation)).isEqualTo("e****@gmail.com");
    }

    @Test
    void regex_defaultMatchesAllAndReplacesWithFixedMask() {
        RegexDesensitize annotation = regexAnnotation("", "");
        // 默认 regex 匹配整个字符串，replacer 默认 ******
        assertThat(regexHandler.desensitize("123456789", annotation)).isEqualTo("******");
    }

    @Test
    void regex_customPatternReplacesOnlyMatches() {
        RegexDesensitize annotation = regexAnnotation("123", "***");
        assertThat(regexHandler.desensitize("123456789", annotation)).isEqualTo("***456789");
    }

    @Test
    void slider_masksMiddleKeepingPrefixAndSuffix() {
        SliderDesensitize annotation = sliderAnnotation(3, 4, "*", "");
        assertThat(sliderHandler.desensitize("13812345678", annotation)).isEqualTo("138****5678");
    }

    @Test
    void slider_fullyMasksWhenShorterThanKeptEdges() {
        // 长度 8 <= prefix(5)+suffix(5)，全部替换为 8 个 replacer
        SliderDesensitize annotation = sliderAnnotation(5, 5, "*", "");
        assertThat(sliderHandler.desensitize("12345678", annotation)).isEqualTo("********");
    }

    @Test
    void slider_emptyOriginStaysEmpty() {
        SliderDesensitize annotation = sliderAnnotation(3, 4, "*", "");
        assertThat(sliderHandler.desensitize("", annotation)).isEmpty();
    }

    @Test
    void slider_usesCustomReplacer() {
        SliderDesensitize annotation = sliderAnnotation(3, 4, "#", "");
        assertThat(sliderHandler.desensitize("13812345678", annotation)).isEqualTo("138####5678");
    }

    @Test
    void missingDisableAttribute_defaultsToEnabled() {
        Deprecated annotation = DeprecatedTarget.class.getAnnotation(Deprecated.class);
        DesensitizationHandler<Deprecated> handler = (origin, ignored) -> origin;

        assertThat(handler.getDisable(annotation)).isEmpty();
    }

    // ---------- 注解动态代理构造（保留默认值，仅覆写被测参数） ----------

    private EmailDesensitize emailAnnotation(String regex, String replacer, String disable) {
        return (EmailDesensitize) Proxy.newProxyInstance(
                EmailDesensitize.class.getClassLoader(),
                new Class<?>[] {EmailDesensitize.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "regex" -> regex.isEmpty() ? method.getDefaultValue() : regex;
                    case "replacer" -> replacer.isEmpty() ? method.getDefaultValue() : replacer;
                    case "disable" -> disable;
                    case "annotationType" -> EmailDesensitize.class;
                    default -> method.getDefaultValue();
                });
    }

    private RegexDesensitize regexAnnotation(String regex, String replacer) {
        return (RegexDesensitize) Proxy.newProxyInstance(
                RegexDesensitize.class.getClassLoader(),
                new Class<?>[] {RegexDesensitize.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "regex" -> regex.isEmpty() ? method.getDefaultValue() : regex;
                    case "replacer" -> replacer.isEmpty() ? method.getDefaultValue() : replacer;
                    case "disable" -> "";
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

    @Deprecated
    private static final class DeprecatedTarget {}
}
