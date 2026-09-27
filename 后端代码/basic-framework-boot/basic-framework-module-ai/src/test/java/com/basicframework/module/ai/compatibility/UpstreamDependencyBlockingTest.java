package com.basicframework.module.ai.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.compatibility.UpstreamDependencyPolicy.Coordinate;
import com.basicframework.module.ai.compatibility.UpstreamDependencyPolicy.Rule;
import com.basicframework.module.ai.compatibility.UpstreamDependencyPolicy.Violation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 阻断演示②：新增危险依赖（与平台冲突的版本 / 被禁的传递依赖）必须能被检出。
 *
 * <p>与"能编译"无关：判定对象是**依赖台账**（冻结坐标 + 版本），违规产物按 06-upstream-upgrade.md
 * 与 F02 的 Go/No-Go 结论构造；策略见 {@link UpstreamDependencyPolicy}。
 *
 * <p>敏感性：每个违规场景与冻结台账（必须无违规）在同一测试类里对照；若策略失去某项判定，对应用例变红。
 * 另外 {@link #realBomAndUpstreamRegistryAgreeWithFrozenLedger()} 直接把真实 BOM 与真实上游台账
 * 对表——任何一侧未登记就改版本都会让本类变红。
 */
class UpstreamDependencyBlockingTest {

    private static List<Violation> evaluateWithExtra(Coordinate... extra) {
        List<Coordinate> inventory = new ArrayList<>(UpstreamDependencyPolicy.frozenProductTree());
        inventory.addAll(List.of(extra));
        return UpstreamDependencyPolicy.evaluate(inventory);
    }

    private static void assertRuleDetected(List<Violation> violations, Rule rule, String detailFragment) {
        assertThat(violations).as("违规必须被检出：%s", rule).anySatisfy(violation -> {
            assertThat(violation.is(rule)).isTrue();
            assertThat(violation.detail()).contains(detailFragment);
        });
    }

    @Test
    void frozenProductTreePassesUpgradePolicy() {
        List<Violation> violations = UpstreamDependencyPolicy.evaluate(UpstreamDependencyPolicy.frozenProductTree());

        assertThat(violations).as("冻结台账本身必须无违规").isEmpty();
    }

    /** Spring AI 2.x 需要 Boot 4.1.1（F02 no-go）：候选把两个都带进来时必须同时被检出。 */
    @Test
    void springAiTwoLineCandidateIsBlocked() {
        List<Violation> violations = evaluateWithExtra(
                new Coordinate("org.springframework.ai", "spring-ai-model", "2.0.1"),
                new Coordinate("org.springframework.boot", "spring-boot", "4.1.1"));

        assertRuleDetected(violations, Rule.FORBIDDEN_COMPONENT, "Spring AI 2.x 需 Boot 4.1.1");
        assertRuleDetected(violations, Rule.PLATFORM_LINE_MISMATCH, "脱离冻结线");
        assertRuleDetected(violations, Rule.UNAPPROVED_VERSION_CHANGE, "spring-ai-model");
        assertRuleDetected(violations, Rule.VERSION_SPLIT, "spring-ai-model");
    }

    /** 被禁的传递依赖：F02 not-adopted 的 Spring AI Alibaba（会带来 Graph/控制台/网关整套依赖）。 */
    @Test
    void notAdoptedAlibabaCandidateIsBlocked() {
        List<Violation> violations =
                evaluateWithExtra(new Coordinate("com.alibaba.cloud.ai", "spring-ai-alibaba-graph-core", "1.1.2.2"));

        assertRuleDetected(violations, Rule.FORBIDDEN_COMPONENT, "已否决/未采用候选");
    }

    /** 第三方 BOM 静默降级平台钉版（Netty 4.2.17.Final → 4.2.16.Final）必须被检出。 */
    @Test
    void silentNettyDowngradeIsBlocked() {
        List<Coordinate> inventory = new ArrayList<>(UpstreamDependencyPolicy.frozenProductTree());
        inventory.replaceAll(coordinate -> "io.netty:netty-common".equals(coordinate.key())
                ? new Coordinate(coordinate.groupId(), coordinate.artifactId(), "4.2.16.Final")
                : coordinate);

        List<Violation> violations = UpstreamDependencyPolicy.evaluate(inventory);

        assertRuleDetected(violations, Rule.UNAPPROVED_VERSION_CHANGE, "netty-common");
        assertThat(violations).as("降级不得被当成正常升级放行").anySatisfy(violation -> assertThat(violation.detail())
                .contains("4.2.17.Final")
                .contains("4.2.16.Final"));
    }

    /** 版本分裂（Jackson 两个版本同时在树里）必须被检出。 */
    @Test
    void duplicateJacksonVersionSplitIsBlocked() {
        List<Violation> violations = evaluateWithExtra(
                new Coordinate("com.fasterxml.jackson.core", "jackson-databind", "2.21.4"),
                new Coordinate("com.fasterxml.jackson.core", "jackson-databind", "2.20.0"));

        assertRuleDetected(violations, Rule.VERSION_SPLIT, "jackson-databind");
    }

    /** 真实 BOM 与真实上游台账必须与冻结台账一致：未登记就改版本会在这里变红。 */
    @Test
    void realBomAndUpstreamRegistryAgreeWithFrozenLedger() {
        assertThat(UpstreamDependencyPolicy.bomProperty("spring-ai.version"))
                .isEqualTo(UpstreamDependencyPolicy.FROZEN_VERSIONS.get("org.springframework.ai:spring-ai-model"));
        assertThat(UpstreamDependencyPolicy.bomProperty("qdrant-client.version"))
                .isEqualTo(UpstreamDependencyPolicy.FROZEN_VERSIONS.get("io.qdrant:client"));
        assertThat(UpstreamDependencyPolicy.bomProperty("tika-core.version"))
                .isEqualTo(UpstreamDependencyPolicy.FROZEN_VERSIONS.get("org.apache.tika:tika-core"));
        assertThat(UpstreamDependencyPolicy.bomProperty("pdfbox.version"))
                .isEqualTo(UpstreamDependencyPolicy.FROZEN_VERSIONS.get("org.apache.pdfbox:pdfbox"));
        assertThat(UpstreamDependencyPolicy.bomProperty("poi.version"))
                .isEqualTo(UpstreamDependencyPolicy.FROZEN_VERSIONS.get("org.apache.poi:poi-ooxml"));
        assertThat(UpstreamDependencyPolicy.bomProperty("spring.boot.version"))
                .isEqualTo(UpstreamDependencyPolicy.FROZEN_VERSIONS.get("org.springframework.boot:spring-boot"));
        assertThat(UpstreamDependencyPolicy.bomProperty("netty.version"))
                .isEqualTo(UpstreamDependencyPolicy.FROZEN_VERSIONS.get("io.netty:netty-common"));

        assertThat(UpstreamDependencyPolicy.registrySelectedVersion("spring-ai"))
                .isEqualTo(UpstreamDependencyPolicy.FROZEN_VERSIONS.get("org.springframework.ai:spring-ai-model"));
        assertThat(UpstreamDependencyPolicy.registrySelectedVersion("qdrant-java-client"))
                .isEqualTo(UpstreamDependencyPolicy.FROZEN_VERSIONS.get("io.qdrant:client"));
        assertThat(UpstreamDependencyPolicy.registrySelectedVersion("tika-core"))
                .isEqualTo(UpstreamDependencyPolicy.FROZEN_VERSIONS.get("org.apache.tika:tika-core"));
    }
}
