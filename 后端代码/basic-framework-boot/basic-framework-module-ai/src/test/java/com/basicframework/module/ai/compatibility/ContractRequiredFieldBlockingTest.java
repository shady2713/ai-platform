package com.basicframework.module.ai.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.api.protocol.AiChartSpecDTO;
import com.basicframework.module.ai.api.protocol.AiResultBlockDTO;
import com.basicframework.module.ai.api.protocol.AiRunEventDTO;
import com.basicframework.module.ai.domain.theme.AiThemeValidator;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 阻断演示①：删除契约里的必需字段必须能被检出（冻结契约 → 生产校验器）。
 *
 * <p>做法：直接读 {@code docs/contracts/ai/*.schema.json} 的 {@code required} 声明与
 * {@code docs/contracts/ai/samples} 的合法样例，逐字段从样例里删掉后再送进**生产校验器**
 * （{@link AiRunEventDTO}/{@link AiResultBlockDTO}/{@link AiChartSpecDTO}/AiThemeValidator）；
 * 每个被删字段都必须被拒绝，且拒绝信息要指认该字段语义。
 *
 * <p>"先红后绿"的敏感性证明：每个字段的用例都把"合法样例必须通过（绿）"与"删除该字段必须被拒（对违规产物变红）"
 * 放在同一次运行里；若校验器失去某项能力，对应用例立即变红。
 */
class ContractRequiredFieldBlockingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode schema(String name) {
        try {
            return MAPPER.readTree(
                    CompatibilityRepositorySupport.readString(CompatibilityRepositorySupport.contractSchema(name)));
        } catch (Exception exception) {
            throw new IllegalStateException("读取冻结 Schema 失败：" + name, exception);
        }
    }

    private static List<String> requiredFields(String schemaName) {
        JsonNode required = schema(schemaName).path("required");
        assertThat(required.isArray())
                .as("冻结 Schema 必须显式声明 required（否则本演示失去依据）：%s", schemaName)
                .isTrue();
        return Stream.iterate(0, index -> index + 1)
                .limit(required.size())
                .map(index -> required.get(index).asText())
                .toList();
    }

    private static JsonNode sample(String filename) {
        try {
            return MAPPER.readTree(CompatibilityRepositorySupport.readString(
                    CompatibilityRepositorySupport.contractSamplesDirectory().resolve(filename)));
        } catch (Exception exception) {
            throw new IllegalStateException("读取冻结契约样例失败：" + filename, exception);
        }
    }

    private static String withoutField(JsonNode node, String field) {
        ObjectNode copy = node.deepCopy();
        assertThat(copy.has(field)).as("样例里应存在字段 %s", field).isTrue();
        copy.remove(field);
        return copy.toString();
    }

    private static void validateRunEvent(String json) {
        try {
            MAPPER.readValue(json, AiRunEventDTO.class).validate();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void validateResultBlock(String json) {
        try {
            MAPPER.readValue(json, AiResultBlockDTO.class).validate();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void validateChartSpec(String json) {
        try {
            MAPPER.readValue(json, AiChartSpecDTO.class).validate();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void frozenRequiredDeclarationsAndSamplesAreAccepted() {
        assertThat(requiredFields("run-event"))
                .containsExactlyInAnyOrder("schemaVersion", "seq", "runId", "status", "createdAt");
        assertThat(requiredFields("theme-tokens")).containsExactlyInAnyOrder("primaryColor", "radius", "fontFamily");
        assertThat(requiredFields("chart-spec")).containsExactlyInAnyOrder("type", "categories", "series");

        JsonNode runEvent = sample("run-event.valid.json");
        assertThatCode(() -> validateRunEvent(runEvent.toString())).doesNotThrowAnyException();
        JsonNode themeTokens = sample("theme-tokens.valid.json");
        assertThatCode(() -> AiThemeValidator.validateTokens(themeTokens.toString()))
                .doesNotThrowAnyException();
        JsonNode chartSpec = sample("result-block.chart-money.valid.json").path("spec");
        assertThatCode(() -> validateChartSpec(chartSpec.toString())).doesNotThrowAnyException();
    }

    /** run-event 的五个 required 字段逐个删除都必须被生产校验器拒绝。 */
    @ParameterizedTest(name = "删除 run-event 必需字段 {0} 必须被拒绝")
    @MethodSource("runEventRequiredFields")
    void droppingRunEventRequiredFieldIsRejected(String field, String expectedMessageFragment) {
        assertThat(requiredFields("run-event")).contains(field);
        String tampered = withoutField(sample("run-event.valid.json"), field);

        assertThatThrownBy(() -> validateRunEvent(tampered))
                .as("删除必需字段 %s 必须被检出", field)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessageFragment);
    }

    static Stream<Arguments> runEventRequiredFields() {
        return Stream.of(
                Arguments.of("schemaVersion", "未知协议版本"),
                Arguments.of("seq", "事件序号"),
                Arguments.of("runId", "运行业务键"),
                Arguments.of("status", "未知运行状态"),
                Arguments.of("createdAt", "创建时间"));
    }

    /** theme-tokens 的必需字段逐个删除都必须被主题校验器拒绝（错误码=AI_THEME_TOKENS_INVALID）。 */
    @ParameterizedTest(name = "删除 theme-tokens 必需字段 {0} 必须被拒绝")
    @MethodSource("themeRequiredFields")
    void droppingThemeRequiredFieldIsRejected(String field) {
        assertThat(requiredFields("theme-tokens")).contains(field);
        String tampered = withoutField(sample("theme-tokens.valid.json"), field);

        assertThatThrownBy(() -> AiThemeValidator.validateTokens(tampered))
                .as("删除必需字段 %s 必须被检出", field)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_THEME_TOKENS_INVALID.getCode());
    }

    static Stream<String> themeRequiredFields() {
        return Stream.of("primaryColor", "radius", "fontFamily");
    }

    /** chart-spec 的必需字段逐个删除都必须被图表契约校验器拒绝。 */
    @ParameterizedTest(name = "删除 chart-spec 必需字段 {0} 必须被拒绝")
    @MethodSource("chartRequiredFields")
    void droppingChartSpecRequiredFieldIsRejected(String field, String expectedMessageFragment) {
        assertThat(requiredFields("chart-spec")).contains(field);
        String tampered =
                withoutField(sample("result-block.chart-money.valid.json").path("spec"), field);

        assertThatThrownBy(() -> validateChartSpec(tampered))
                .as("删除必需字段 %s 必须被检出", field)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessageFragment);
    }

    static Stream<Arguments> chartRequiredFields() {
        return Stream.of(
                Arguments.of("type", "未知图表类型"),
                Arguments.of("categories", "图表类目不能为空"),
                Arguments.of("series", "图表系列不能为空"));
    }

    /** result-block 是判别联合：删掉判别键或分支配套字段都必须被拒绝。 */
    @Test
    void droppingResultBlockDiscriminatorOrBranchFieldIsRejected() {
        JsonNode chartBlock = sample("result-block.chart-money.valid.json");
        JsonNode textBlock = sample("result-block.text.valid.json");

        assertThatThrownBy(() -> validateResultBlock(withoutField(chartBlock, "kind")))
                .as("删掉判别键 kind 必须被拒绝")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("结果块缺少 kind");
        assertThatThrownBy(() -> validateResultBlock(withoutField(chartBlock, "spec")))
                .as("chart 分支缺少配套字段 spec 必须被拒绝")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chart 块必须且只能包含 spec 字段");
        assertThatThrownBy(() -> validateResultBlock(withoutField(textBlock, "text")))
                .as("text 分支缺少配套字段 text 必须被拒绝")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("text 块必须且只能包含 text 字段");
    }

    /** 反向对照：字段齐全的样例不被误伤（与删除字段的用例构成红/绿两侧）。 */
    @Test
    void samplesWithAllRequiredFieldsRemainAccepted() {
        Map<String, Runnable> accepted = Map.of(
                "run-event",
                        () -> validateRunEvent(sample("run-event.valid.json").toString()),
                "result-block",
                        () -> validateResultBlock(
                                sample("result-block.text.valid.json").toString()),
                "chart-spec",
                        () -> validateChartSpec(sample("result-block.chart-money.valid.json")
                                .path("spec")
                                .toString()));

        accepted.forEach((name, validation) ->
                assertThatCode(validation::run).as("%s 的完整样例必须继续被接受", name).doesNotThrowAnyException());
    }
}
