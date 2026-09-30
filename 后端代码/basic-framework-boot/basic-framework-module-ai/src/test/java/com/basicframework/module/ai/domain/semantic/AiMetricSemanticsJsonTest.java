package com.basicframework.module.ai.domain.semantic;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_CALIBER_MISSING_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_CONVERSION_RULE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SOURCE_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 跨源口径定义的**解析原语**负向分支（Y03）。
 *
 * <p>{@link AiMetricSemanticsJson} 的每个 {@code throw} 都是一条独立的形状校验规则：形状错误与
 * 口径冲突要落到不同错误码，运维才能分清"写错了"和"写对了但自相矛盾"。这些分支全部需要**精确的错误码**，
 * 因此这里逐条钉住，而不是只断言"抛了异常"。
 *
 * <p>与 {@link AiMetricSemanticsFactsTest} 分工：那边钉**语义判定**（币种/单位/时区/扇出/缺口），
 * 这边钉**输入形状**（这段 JSON 长得对不对）。两者都是纯函数，不需要数据库。
 */
class AiMetricSemanticsJsonTest {

    @Test
    void readObjectRejectsNullAndNonObjectShapes() {
        assertCode(AI_METRIC_CALIBER_MISSING_CONFLICT, () -> AiMetricSemanticsJson.readObject(null));
        // 数字、布尔、列表都不是"一个 JSON 对象"
        assertCode(AI_METRIC_CALIBER_MISSING_CONFLICT, () -> AiMetricSemanticsJson.readObject(7));
        assertCode(AI_METRIC_CALIBER_MISSING_CONFLICT, () -> AiMetricSemanticsJson.readObject(true));
        assertCode(AI_METRIC_CALIBER_MISSING_CONFLICT, () -> AiMetricSemanticsJson.readObject(List.of()));
        // 解析成功但结果是 null（JSON 字面量 null）
        assertCode(AI_METRIC_CALIBER_MISSING_CONFLICT, () -> AiMetricSemanticsJson.readObject("null"));
        // 不是合法 JSON
        assertCode(AI_METRIC_CALIBER_MISSING_CONFLICT, () -> AiMetricSemanticsJson.readObject("{不是对象"));
    }

    @Test
    void readObjectAcceptsRealMapAndJsonText() {
        assertThat(AiMetricSemanticsJson.readObject(Map.of("a", 1))).containsEntry("a", 1);
        assertThat(AiMetricSemanticsJson.readObject("{\"a\":1}")).containsEntry("a", 1);
    }

    @Test
    void objectListEnforcesBoundsAndItemShape() {
        assertThat(AiMetricSemanticsJson.objectList(List.of(Map.of("role", "order")), 4, 1))
                .hasSize(1);
        // 非列表
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.objectList("nope", 4, 1));
        // 空列表：跨源聚合至少要一个来源
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.objectList(List.of(), 4, 1));
        // 超过上限（放 2 个来源但 max=1）
        assertCode(
                AI_METRIC_SOURCE_INVALID,
                () -> AiMetricSemanticsJson.objectList(
                        List.of(Map.of("role", "order"), Map.of("role", "invoice")), 1, 1));
        // 元素不是对象
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.objectList(List.of("order"), 4, 1));
    }

    @Test
    void nameListRejectsNonListAndDuplicatedNames() {
        assertThat(AiMetricSemanticsJson.nameList(List.of("order", "invoice"), 4, 1))
                .containsExactly("order", "invoice");
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.nameList("order", 4, 1));
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.nameList(List.of(), 4, 1));
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.nameList(List.of("a", "a"), 4, 1));
        // 名字本身不合式（首字母大写、含连字符）
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.nameList(List.of("Order"), 4, 1));
    }

    @Test
    void enumValueRejectsNullAndUnknownValuesButNormalizesCase() {
        assertThat(AiMetricSemanticsJson.enumValue("day", Set.of("DAY", "MONTH")))
                .isEqualTo("DAY");
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.enumValue(null, Set.of("DAY")));
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.enumValue("year", Set.of("DAY")));
        // 数字/布尔会先经 text() 转成字符串再按词表判定
        assertThat(AiMetricSemanticsJson.enumValue(7, Set.of("7"))).isEqualTo("7");
    }

    @Test
    void timezoneRejectsNullAndMalformedValues() {
        assertThat(AiMetricSemanticsJson.timezone("Asia/Shanghai")).isEqualTo("Asia/Shanghai");
        // 缺时区是"口径不完整"，与"时区写错"是两个不同错误码
        assertCode(AI_METRIC_CALIBER_MISSING_CONFLICT, () -> AiMetricSemanticsJson.timezone(null));
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.timezone("Asia//Shanghai!"));
    }

    @Test
    void ruleRejectsMalformedRuleAndUsesItsOwnErrorCode() {
        assertThat(AiMetricSemanticsJson.rule("fx_month_end")).isEqualTo("fx_month_end");
        // 换算规则写错是"换算规则冲突"，不与一般来源形状错误混用同一个码
        assertCode(AI_METRIC_CONVERSION_RULE_CONFLICT, () -> AiMetricSemanticsJson.rule("FX-Month-End"));
        assertCode(AI_METRIC_CONVERSION_RULE_CONFLICT, () -> AiMetricSemanticsJson.rule(null));
    }

    @Test
    void positiveIntAndLongRejectNonNumericAndNonPositiveValues() {
        assertThat(AiMetricSemanticsJson.positiveInt(1)).isEqualTo(1);
        assertThat(AiMetricSemanticsJson.positiveLong(1L)).isEqualTo(1L);
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.positiveInt(0));
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.positiveInt(-1));
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.positiveInt("1"));
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.positiveInt(null));
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.positiveLong(0L));
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.positiveLong("1"));
    }

    @Test
    void boolValueDefaultsToFalseAndOnlyAcceptsExplicitTrue() {
        // optional 只有显式 true 才算"可选来源"：缺省/字符串 "true"/布尔 true 三种写法都覆盖到
        assertThat(AiMetricSemanticsJson.boolValue(null)).isFalse();
        assertThat(AiMetricSemanticsJson.boolValue(Boolean.TRUE)).isTrue();
        assertThat(AiMetricSemanticsJson.boolValue(Boolean.FALSE)).isFalse();
        assertThat(AiMetricSemanticsJson.boolValue("TRUE")).isTrue();
        assertThat(AiMetricSemanticsJson.boolValue("false")).isFalse();
        // 其它字符串不是布尔值，不做任何"真值化"猜测
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.boolValue("yes"));
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.boolValue(1));
    }

    @Test
    void textRejectsUnsupportedTypesInsteadOfStringifyingThem() {
        // 直接经 enumValue 触达：Date 既不是 String 也不是 Number/Boolean，必须被拒而不是 toString 蒙混
        assertCode(AI_METRIC_SOURCE_INVALID, () -> AiMetricSemanticsJson.enumValue(new Date(0L), Set.of("x")));
        // Boolean 也走同一条 String.valueOf 分支，再按词表归一为大写
        assertThat(AiMetricSemanticsJson.enumValue(Boolean.TRUE, Set.of("TRUE")))
                .isEqualTo("TRUE");
    }

    private static void assertCode(ErrorCode expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }
}
