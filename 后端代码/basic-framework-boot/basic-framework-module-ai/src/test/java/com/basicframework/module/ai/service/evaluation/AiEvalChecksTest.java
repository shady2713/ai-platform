package com.basicframework.module.ai.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 评测期望规则（Q04）：金额/日期/引用/结构/版本/无秘密都由程序核验，规则写错必须报错而不是放行。
 */
class AiEvalChecksTest {

    private static List<AiEvalChecks.Verdict> verify(String rules, String observed) {
        return AiEvalChecks.verify(rules, observed);
    }

    @Test
    void moneyComparesNumericallyAtTwoDecimals() {
        List<AiEvalChecks.Verdict> verdicts = verify(
                "[{\"kind\":\"MONEY\",\"path\":\"data.netAmount\",\"expected\":\"450.00\"}]",
                "{\"data\":{\"netAmount\":450}}");

        assertThat(AiEvalChecks.allPassed(verdicts)).as("450 与 450.00 是同一金额").isTrue();
        assertThat(verdicts.get(0).observed()).isEqualTo("450.00");

        assertThat(AiEvalChecks.allPassed(verify(
                        "[{\"kind\":\"MONEY\",\"path\":\"data.netAmount\",\"expected\":\"450.00\"}]",
                        "{\"data\":{\"netAmount\":\"290.005\"}}")))
                .as("290.005 四舍五入后是 290.01，不等于 450.00")
                .isFalse();
        assertThat(verify(
                                "[{\"kind\":\"MONEY\",\"path\":\"data.netAmount\",\"expected\":\"290.005\"}]",
                                "{\"data\":{\"netAmount\":290.00}}")
                        .get(0)
                        .passed())
                .as("期望值也按 2 位小数比较（290.005 → 290.01 ≠ 290.00）")
                .isFalse();
    }

    @Test
    void dateComparesToDayAndHandlesZones() {
        assertThat(AiEvalChecks.allPassed(verify(
                        "[{\"kind\":\"DATE\",\"path\":\"data.startDate\",\"expected\":\"2026-08-01\"}]",
                        "{\"data\":{\"startDate\":\"2026-08-01T00:00:00\"}}")))
                .isTrue();
        // 带时区：2026-08-01T00:30+08:00 在 UTC 下仍是 7 月 31 日
        assertThat(AiEvalChecks.allPassed(verify(
                        "[{\"kind\":\"DATE\",\"path\":\"data.startDate\",\"expected\":\"2026-07-31\"}]",
                        "{\"data\":{\"startDate\":\"2026-08-01T00:30:00+08:00\"}}")))
                .isTrue();
        assertThat(AiEvalChecks.allPassed(verify(
                        "[{\"kind\":\"DATE\",\"path\":\"data.startDate\",\"expected\":\"2026-08-01\",\"zone\":\"Asia/Shanghai\"}]",
                        "{\"data\":{\"startDate\":\"2026-08-01T00:30:00+08:00\"}}")))
                .isTrue();
        assertThat(AiEvalChecks.allPassed(verify(
                        "[{\"kind\":\"DATE\",\"path\":\"data.startDate\",\"expected\":\"2026-09-01\"}]",
                        "{\"data\":{\"startDate\":\"2026-08-01\"}}")))
                .isFalse();
    }

    @Test
    void structureCitationAndVersionAreCheckedProgrammatically() {
        String observed =
                """
                {"data":{"rows":[{"id":1}]},
                 "citations":[{"ref":"kb:12:doc:7","title":"handbook"}],
                 "version":"endpoint:3@2",
                 "status":"COMPLETE"}
                """;
        String rules =
                """
                [{"kind":"STRUCTURE","requiredPaths":["data.rows","citations","status"]},
                 {"kind":"CITATION","path":"citations","mustReferenceAnyOf":["kb:12:"]},
                 {"kind":"VERSION","path":"version","expected":"endpoint:3@2"},
                 {"kind":"VALUE","path":"status","expected":"COMPLETE"}]
                """;
        assertThat(AiEvalChecks.allPassed(verify(rules, observed))).isTrue();

        assertThat(AiEvalChecks.allPassed(
                        verify("[{\"kind\":\"STRUCTURE\",\"requiredPaths\":[\"data.missing\"]}]", observed)))
                .as("缺少必需字段不算通过")
                .isFalse();
        assertThat(AiEvalChecks.allPassed(verify(
                        "[{\"kind\":\"CITATION\",\"path\":\"citations\",\"mustReferenceAnyOf\":[\"kb:99:\"]}]",
                        observed)))
                .as("引用不在允许来源内不算通过")
                .isFalse();
        assertThat(AiEvalChecks.allPassed(verify(
                        "[{\"kind\":\"VERSION\",\"path\":\"version\",\"expected\":\"endpoint:3@1\"}]", observed)))
                .as("版本不一致不算通过")
                .isFalse();
        assertThat(AiEvalChecks.allPassed(
                        verify("[{\"kind\":\"VALUE\",\"path\":\"status\",\"expected\":\"PARTIAL\"}]", observed)))
                .isFalse();
    }

    @Test
    void secretBearingObservationsFailInsteadOfBeingReported() {
        List<AiEvalChecks.Verdict> verdicts = verify(
                "[{\"kind\":\"NO_SECRET\",\"path\":\"outputDigest\"}]",
                "{\"outputDigest\":\"sk-live-abcdefghijklmnop\"}");

        assertThat(AiEvalChecks.allPassed(verdicts)).isFalse();
        assertThat(verdicts.get(0).observed()).as("报告里只给脱敏标记，不回显疑似秘密").isEqualTo("[已脱敏]");
        assertThat(AiEvalChecks.allPassed(verify(
                        "[{\"kind\":\"NO_SECRET\",\"path\":\"outputDigest\"}]", "{\"outputDigest\":\"9f2c1d\"}")))
                .isTrue();
    }

    @Test
    void malformedRulesAreRejectedInsteadOfPassingSilently() {
        assertThatThrownBy(() -> AiEvalChecks.validateRules("not-json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiEvalChecks.validateRules("[]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiEvalChecks.validateRules("[{\"kind\":\"MAGIC\"}]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiEvalChecks.validateRules("[{\"kind\":\"MONEY\",\"path\":\"a\"}]"))
                .as("金额规则缺少 expected")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> AiEvalChecks.validateRules("[{\"kind\":\"DATE\",\"path\":\"a\",\"expected\":\"8月1日\"}]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiEvalChecks.validateRules("[{\"kind\":\"STRUCTURE\",\"requiredPaths\":[]}]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiEvalChecks.verify("[{\"kind\":\"VALUE\",\"path\":\"a\",\"expected\":1}]", "[]"))
                .as("实际事实必须是对象")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nestedPathsSupportArrayIndexesAndReportMissingValues() {
        String observed = "{\"result\":{\"rows\":[{\"amount\":\"190.00\"}]}}";
        assertThat(AiEvalChecks.allPassed(verify(
                        "[{\"kind\":\"MONEY\",\"path\":\"result.rows[0].amount\",\"expected\":\"190.00\"}]", observed)))
                .isTrue();
        assertThat(AiEvalChecks.allPassed(verify(
                        "[{\"kind\":\"MONEY\",\"path\":\"result.rows[1].amount\",\"expected\":\"190.00\"}]", observed)))
                .as("越界下标按缺失处理")
                .isFalse();
        assertThat(AiEvalChecks.allPassed(
                        verify("[{\"kind\":\"MONEY\",\"path\":\"result.absent\",\"expected\":\"0.00\"}]", observed)))
                .isFalse();
    }
}
