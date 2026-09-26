package com.basicframework.module.ai.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalCaseDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalSuiteDO;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 评测摘要（Q04）：书写顺序与空白不影响摘要，内容变化一定影响。 */
class AiEvalDigestTest {

    private static AiEvalCaseDO evalCase(String caseKey, String checks) {
        return new AiEvalCaseDO()
                .setCaseKey(caseKey)
                .setChecksJson(checks)
                .setExpectVersion("endpoint:3@2")
                .setNeedsReview(false)
                .setQuestion("上个月华东前十的净额是多少？")
                .setSeverity(AiEvalCaseDO.SEVERITY_BLOCKER);
    }

    @Test
    void canonicalJsonIgnoresKeyOrderAndWhitespace() {
        assertThat(AiEvalDigest.canonicalJsonText("{\"b\":1,\"a\":[2,{\"d\":3,\"c\":4}]}"))
                .isEqualTo("{\"a\":[2,{\"c\":4,\"d\":3}],\"b\":1}");
        assertThat(AiEvalDigest.digest("{\"a\":1,\"b\":2}")).isEqualTo(AiEvalDigest.digest("{ \"b\" : 2, \"a\" : 1 }"));
        assertThat(AiEvalDigest.digest("{\"a\":1}")).isNotEqualTo(AiEvalDigest.digest("{\"a\":2}"));
    }

    @Test
    void suiteDigestCoversRulesAndIdentityButNotTitle() {
        AiEvalSuiteDO suite = new AiEvalSuiteDO()
                .setApplicationId(1L)
                .setCode("order-qa")
                .setDataLevel(AiEvalSuiteDO.LEVEL_INTERNAL)
                .setServiceId(4L)
                .setSubjectType("USER")
                .setExternalUserId("eval-runner");
        List<AiEvalCaseDO> cases = List.of(
                evalCase("case_a", "[{\"kind\":\"MONEY\",\"path\":\"amount\",\"expected\":\"450.00\"}]"),
                evalCase("case_b", "[{\"kind\":\"DATE\",\"path\":\"day\",\"expected\":\"2026-08-01\"}]"));

        String digest = AiEvalSuiteDigests.of(suite, cases);
        assertThat(AiEvalSuiteDigests.of(suite, cases)).as("同内容同摘要").isEqualTo(digest);

        List<AiEvalCaseDO> edited = List.of(
                evalCase("case_a", "[{\"kind\":\"MONEY\",\"path\":\"amount\",\"expected\":\"290.00\"}]"),
                evalCase("case_b", "[{\"kind\":\"DATE\",\"path\":\"day\",\"expected\":\"2026-08-01\"}]"));
        assertThat(AiEvalSuiteDigests.of(suite, edited)).as("改期望规则一定改摘要").isNotEqualTo(digest);
        assertThat(AiEvalSuiteDigests.of(suite, List.of(cases.get(0))))
                .as("增减样例一定改摘要")
                .isNotEqualTo(digest);
        assertThat(AiEvalSuiteDigests.of(suite.setName("换个名字"), cases))
                .as("只改名称不影响摘要素（名称不参与判定）")
                .isEqualTo(digest);
    }

    @Test
    void caseDigestIgnoresRuleFormatting() {
        assertThat(AiEvalSuiteDigests.caseDigest(
                        evalCase("case_a", "[{\"kind\":\"VALUE\",\"path\":\"s\",\"expected\":\"OK\"}]")))
                .isEqualTo(AiEvalSuiteDigests.caseDigest(
                        evalCase("case_a", "[ { \"expected\" : \"OK\" , \"path\" : \"s\" , \"kind\" : \"VALUE\" } ]")));
    }

    @Test
    void summaryJsonFreezesPerCaseDigests() {
        String summary = AiEvalSuiteDigests.summaryJson(
                List.of(evalCase("case_a", "[{\"kind\":\"VALUE\",\"path\":\"s\",\"expected\":\"OK\"}]")));

        assertThat(summary).contains("\"caseKey\":\"case_a\"").contains("\"severity\":\"BLOCKER\"");
        assertThat(summary)
                .contains(AiEvalSuiteDigests.caseDigest(
                        evalCase("case_a", "[{\"kind\":\"VALUE\",\"path\":\"s\",\"expected\":\"OK\"}]")));
    }
}
