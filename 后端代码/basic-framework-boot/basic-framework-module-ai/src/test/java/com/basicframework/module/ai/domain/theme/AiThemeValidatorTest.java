package com.basicframework.module.ai.domain.theme;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_FONT_NOT_ALLOWED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_LAYOUT_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_TOKENS_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.util.json.JsonUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** C04 主题 token/布局校验：只接受声明取值，任意 CSS/url/脚本一律拒绝。 */
class AiThemeValidatorTest {

    private static final String DEFAULT_FONT = AiThemeValidator.ALLOWED_FONT_FAMILIES.get(0);

    private static final String DEFAULT_TOKENS_JSON =
            "{\"primaryColor\":\"#1677ff\",\"radius\":6,\"fontFamily\":\"" + DEFAULT_FONT.replace("\"", "\\\"") + "\"}";

    private static String tokens(String primaryColor, Object radius, String fontFamily) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("primaryColor", primaryColor);
        map.put("radius", radius);
        map.put("fontFamily", fontFamily);
        return JsonUtils.toJsonString(map);
    }

    private static void assertCode(ErrorCode expected, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(expected.getCode()));
    }

    @Test
    void normalizesTokensWithStableKeyOrder() {
        String normalized = AiThemeValidator.validateTokens(tokens("#1677ff", 6, DEFAULT_FONT));

        assertThat(normalized).isEqualTo(DEFAULT_TOKENS_JSON);
        // 同一份内容重复校验结果一致（摘要与缓存键都依赖它）
        assertThat(AiThemeValidator.validateTokens(tokens("#1677ff", 6, DEFAULT_FONT)))
                .isEqualTo(normalized);
    }

    @Test
    void acceptsOptionalColorSchemeAndShortHexColor() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("primaryColor", "#abc");
        map.put("radius", 6.5);
        map.put("fontFamily", DEFAULT_FONT);
        map.put("colorScheme", "dark");

        String normalized = AiThemeValidator.validateTokens(JsonUtils.toJsonString(map));

        assertThat(normalized).contains("\"colorScheme\":\"dark\"").contains("\"radius\":6.5");
    }

    @Test
    void rejectsUnknownTokenKeysAndIllegalColors() {
        assertCode(
                AI_THEME_TOKENS_INVALID,
                () -> AiThemeValidator.validateTokens("{\"primaryColor\":\"#1677ff\",\"radius\":6,\"fontFamily\":\""
                        + DEFAULT_FONT + "\",\"background\":\"#fff\"}"));
        assertCode(AI_THEME_TOKENS_INVALID, () -> AiThemeValidator.validateTokens(tokens("red", 6, DEFAULT_FONT)));
        assertCode(
                AI_THEME_TOKENS_INVALID,
                () -> AiThemeValidator.validateTokens(tokens("javascript:alert(1)", 6, DEFAULT_FONT)));
        assertCode(AI_THEME_TOKENS_INVALID, () -> AiThemeValidator.validateTokens(tokens("#12345", 6, DEFAULT_FONT)));
        assertCode(AI_THEME_TOKENS_INVALID, () -> AiThemeValidator.validateTokens(tokens(null, 6, DEFAULT_FONT)));
    }

    @Test
    void rejectsRadiusOutsideContractRangeAndWrongType() {
        assertCode(AI_THEME_TOKENS_INVALID, () -> AiThemeValidator.validateTokens(tokens("#1677ff", -1, DEFAULT_FONT)));
        assertCode(AI_THEME_TOKENS_INVALID, () -> AiThemeValidator.validateTokens(tokens("#1677ff", 25, DEFAULT_FONT)));
        assertCode(
                AI_THEME_TOKENS_INVALID, () -> AiThemeValidator.validateTokens(tokens("#1677ff", "6", DEFAULT_FONT)));
        assertCode(
                AI_THEME_TOKENS_INVALID, () -> AiThemeValidator.validateTokens(tokens("#1677ff", null, DEFAULT_FONT)));
    }

    @Test
    void rejectsFontsOutsideWhitelistWithDedicatedCode() {
        for (String font : new String[] {
            "Comic Sans MS",
            "Arial",
            "url(https://evil.example.com/font.woff)",
            "system-ui, sans-serif",
            "system-ui; background:url(x)"
        }) {
            assertCode(AI_THEME_FONT_NOT_ALLOWED, () -> AiThemeValidator.validateTokens(tokens("#1677ff", 6, font)));
        }
        assertCode(AI_THEME_TOKENS_INVALID, () -> AiThemeValidator.validateTokens(tokens("#1677ff", 6, "")));
        assertCode(
                AI_THEME_TOKENS_INVALID, () -> AiThemeValidator.validateTokens(tokens("#1677ff", 6, "a".repeat(121))));
        assertCode(AI_THEME_TOKENS_INVALID, () -> AiThemeValidator.validateTokens(tokens("#1677ff", 6, null)));
    }

    @Test
    void rejectsIllegalColorSchemeAndMalformedPayloads() {
        assertCode(
                AI_THEME_TOKENS_INVALID,
                () -> AiThemeValidator.validateTokens("{\"primaryColor\":\"#1677ff\",\"radius\":6,\"fontFamily\":\""
                        + DEFAULT_FONT + "\",\"colorScheme\":\"auto\"}"));
        assertCode(AI_THEME_TOKENS_INVALID, () -> AiThemeValidator.validateTokens("{"));
        assertCode(AI_THEME_TOKENS_INVALID, () -> AiThemeValidator.validateTokens("[1,2]"));
        assertCode(AI_THEME_TOKENS_INVALID, () -> AiThemeValidator.validateTokens("null"));
        assertCode(AI_THEME_TOKENS_INVALID, () -> AiThemeValidator.validateTokens(""));
        assertCode(AI_THEME_TOKENS_INVALID, () -> AiThemeValidator.validateTokens(null));
    }

    @Test
    void layoutFillsDefaultsAndNormalizes() {
        assertThat(AiThemeValidator.validateLayout("{}"))
                .isEqualTo("{\"fontScale\":\"normal\",\"density\":\"normal\","
                        + "\"narrowBreakpoint\":768,\"minSidebarWidth\":320}");
        assertThat(AiThemeValidator.validateLayout("{\"fontScale\":\"large\",\"density\":\"compact\","
                        + "\"narrowBreakpoint\":600,\"minSidebarWidth\":360}"))
                .isEqualTo("{\"fontScale\":\"large\",\"density\":\"compact\","
                        + "\"narrowBreakpoint\":600,\"minSidebarWidth\":360}");
    }

    @Test
    void rejectsArbitraryLayoutValues() {
        assertCode(AI_THEME_LAYOUT_INVALID, () -> AiThemeValidator.validateLayout("{\"padding\":\"8px\"}"));
        assertCode(AI_THEME_LAYOUT_INVALID, () -> AiThemeValidator.validateLayout("{\"fontScale\":\"huge\"}"));
        assertCode(AI_THEME_LAYOUT_INVALID, () -> AiThemeValidator.validateLayout("{\"fontScale\":14}"));
        assertCode(AI_THEME_LAYOUT_INVALID, () -> AiThemeValidator.validateLayout("{\"density\":\"dense\"}"));
        assertCode(AI_THEME_LAYOUT_INVALID, () -> AiThemeValidator.validateLayout("{\"narrowBreakpoint\":100}"));
        assertCode(AI_THEME_LAYOUT_INVALID, () -> AiThemeValidator.validateLayout("{\"narrowBreakpoint\":2000}"));
        assertCode(AI_THEME_LAYOUT_INVALID, () -> AiThemeValidator.validateLayout("{\"narrowBreakpoint\":768.5}"));
        assertCode(AI_THEME_LAYOUT_INVALID, () -> AiThemeValidator.validateLayout("{\"minSidebarWidth\":\"320px\"}"));
        assertCode(AI_THEME_LAYOUT_INVALID, () -> AiThemeValidator.validateLayout("{\"minSidebarWidth\":100}"));
        assertCode(AI_THEME_LAYOUT_INVALID, () -> AiThemeValidator.validateLayout("{\"minSidebarWidth\":900}"));
        assertCode(AI_THEME_LAYOUT_INVALID, () -> AiThemeValidator.validateLayout("not-json"));
    }

    @Test
    void fingerprintDependsOnBothPayloads() {
        String tokens = AiThemeValidator.defaultTokensJson();
        String layout = AiThemeValidator.defaultLayoutJson();

        assertThat(AiThemeValidator.fingerprint(tokens, layout))
                .hasSize(64)
                .isEqualTo(AiThemeValidator.fingerprint(tokens, layout));
        assertThat(AiThemeValidator.fingerprint(tokens, layout))
                .isNotEqualTo(AiThemeValidator.fingerprint(tokens, "{}"));
        assertThat(AiThemeValidator.fingerprint(tokens, layout))
                .isNotEqualTo(AiThemeValidator.fingerprint("{}", layout));
    }

    @Test
    void platformDefaultsMatchFrozenContractValues() {
        assertThat(AiThemeValidator.defaultTokensJson()).isEqualTo(DEFAULT_TOKENS_JSON);
        assertThat(AiThemeValidator.defaultLayoutJson()).contains("\"narrowBreakpoint\":768");
        // 默认值必须自己通过校验（否则平台默认主题都发布不了）
        assertThat(AiThemeValidator.validateTokens(AiThemeValidator.defaultTokensJson()))
                .isEqualTo(AiThemeValidator.defaultTokensJson());
        assertThat(AiThemeValidator.validateLayout(AiThemeValidator.defaultLayoutJson()))
                .isEqualTo(AiThemeValidator.defaultLayoutJson());
    }

    @Test
    void allowedFontFamiliesIsDefensiveCopy() {
        var fonts = AiThemeValidator.allowedFontFamilies();
        fonts.clear();
        assertThat(AiThemeValidator.allowedFontFamilies()).hasSize(4);
    }
}
