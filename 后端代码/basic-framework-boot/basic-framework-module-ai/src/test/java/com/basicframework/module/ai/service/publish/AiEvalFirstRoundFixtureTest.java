package com.basicframework.module.ai.service.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.service.evaluation.AiEvalChecks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Q05 首轮固定评测夹具的登记质量（合成数据 + 规则可用）：
 * 夹具是交付物的一部分，因此在门禁里断言它"能被检查器解析、条数符合卡片要求、不含真实客户痕迹"，
 * 而不是只在报告里自称合规。
 *
 * <p>只校验**规则词表**与**数据分级**；通过率与实际判定必须由真实运行产生（本环境无模型端点，见 Q05 证据的未验证项）。
 */
class AiEvalFirstRoundFixtureTest {

    private static final Path FIXTURE = locateFixture();

    /** 真实客户痕迹的粗筛（邮箱/手机号/密钥前缀）：夹具里出现即判失败。 */
    private static final List<Pattern> CUSTOMER_TRACES = List.of(
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
            Pattern.compile("\\b1[3-9]\\d{9}\\b"),
            Pattern.compile("(?i)\\b(?:sk|rk|pk)-[A-Za-z0-9_-]{8,}"));

    private static Path locateFixture() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null; depth++) {
            Path candidate = current.resolve("docs/acceptance/q05-eval-suite-fixtures.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未找到 docs/acceptance/q05-eval-suite-fixtures.json");
    }

    private static Map<String, Object> fixture() throws IOException {
        String raw = Files.readString(FIXTURE, StandardCharsets.UTF_8);
        return (Map<String, Object>) JsonUtils.parseObject(raw, Map.class);
    }

    private static List<Map<String, Object>> suites(Map<String, Object> fixture) {
        List<Map<String, Object>> suites = new ArrayList<>();
        for (Object item : (List<Object>) fixture.get("suites")) {
            suites.add((Map<String, Object>) item);
        }
        return suites;
    }

    private static List<Map<String, Object>> cases(Map<String, Object> suite) {
        List<Map<String, Object>> cases = new ArrayList<>();
        for (Object item : (List<Object>) suite.get("cases")) {
            cases.add((Map<String, Object>) item);
        }
        return cases;
    }

    @Test
    void fixtureCoversSixtyClearPlusTwentyAmbiguousOrSecurityCases() throws IOException {
        Map<String, Object> fixture = fixture();
        List<Map<String, Object>> suites = suites(fixture);
        assertThat(suites).as("首轮两个套件：明确问题 + 歧义/安全").hasSize(2);

        int clear = 0;
        int ambiguous = 0;
        int rules = 0;
        Set<String> severities = new HashSet<>();
        Set<String> kinds = new HashSet<>();
        for (Map<String, Object> suite : suites) {
            String code = String.valueOf(suite.get("code"));
            assertThat(String.valueOf(suite.get("dataLevel")))
                    .as("%s 只允许合成数据分级", code)
                    .isIn("L1_PUBLIC", "L2_INTERNAL");
            assertThat(String.valueOf(suite.get("subjectType"))).isIn("APP", "USER");
            List<Map<String, Object>> cases = cases(suite);
            assertThat(cases).as("%s 的样例数", code).hasSize(code.contains("core") ? 60 : 20);
            if (code.contains("core")) {
                clear = cases.size();
            } else {
                ambiguous = cases.size();
            }
            Set<String> keys = new HashSet<>();
            for (Map<String, Object> item : cases) {
                String caseKey = String.valueOf(item.get("caseKey"));
                assertThat(keys.add(caseKey)).as("套件内 caseKey 唯一：%s", caseKey).isTrue();
                assertThat(caseKey).matches("^[a-z][a-z0-9_-]{1,63}$");
                severities.add(String.valueOf(item.get("severity")));
                String checksJson = JsonUtils.toJsonString(item.get("checks"));
                assertThatCode(() -> AiEvalChecks.validateRules(checksJson))
                        .as("%s 的期望规则必须合规", caseKey)
                        .doesNotThrowAnyException();
                for (Object rule : (List<Object>) item.get("checks")) {
                    kinds.add(String.valueOf(((Map<String, Object>) rule).get("kind")));
                    rules++;
                }
                String question = String.valueOf(item.get("question"));
                for (Pattern trace : CUSTOMER_TRACES) {
                    assertThat(trace.matcher(question).find())
                            .as("%s 的合成问题不得含真实客户痕迹", caseKey)
                            .isFalse();
                }
            }
        }
        assertThat(clear).as("明确问题样例").isEqualTo(60);
        assertThat(ambiguous).as("歧义/安全问题样例").isEqualTo(20);
        assertThat(severities).containsExactlyInAnyOrder("BLOCKER", "MAJOR", "MINOR");
        assertThat(kinds)
                .as("规则词表全量覆盖")
                .containsExactlyInAnyOrder("MONEY", "DATE", "VALUE", "STRUCTURE", "CITATION", "VERSION", "NO_SECRET");
        assertThat(rules).as("每例至少 1 条规则，规则总数有下界").isGreaterThanOrEqualTo(80);
    }

    @Test
    void fixtureIsSelfContainedAndDeclaresNoUnverifiedResults() throws IOException {
        Map<String, Object> fixture = fixture();
        assertThat(fixture.get("version")).isEqualTo(1);
        String notes = String.valueOf(fixture.get("notes"));
        assertThat(notes).as("夹具不声明任何执行结果（通过率只能来自真实运行）").contains("不声明任何执行结果");

        for (Map<String, Object> suite : suites(fixture)) {
            for (Map<String, Object> item : cases(suite)) {
                Object expectVersion = item.get("expectVersion");
                assertThat(expectVersion == null
                                || !String.valueOf(expectVersion).contains("<"))
                        .as("%s 的 expectVersion 不得留占位符", item.get("caseKey"))
                        .isTrue();
                assertThat(String.valueOf(item.get("severity"))).isIn("BLOCKER", "MAJOR", "MINOR");
                assertThat(String.valueOf(item.get("title")).isBlank()).isFalse();
                assertThat(String.valueOf(item.get("question")).isBlank()).isFalse();
            }
        }
    }
}
