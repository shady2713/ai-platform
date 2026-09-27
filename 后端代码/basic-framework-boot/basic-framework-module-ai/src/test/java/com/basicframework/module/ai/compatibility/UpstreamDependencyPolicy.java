package com.basicframework.module.ai.compatibility;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 上游依赖冻结台账与升级准入策略（阻断演示②的判定核心）。
 *
 * <p>台账来源（逐值可追溯）：
 * <ul>
 *   <li>{@code docs/integrations/upstream-registry.yaml}（F02，2026-09-26 复验）与
 *       {@code docs/integrations/ai-platform-upstream-candidates.md} §10.3 的真实依赖树；</li>
 *   <li>冻结落地位置：{@code basic-framework-dependencies/pom.xml} 的属性值。</li>
 * </ul>
 * 本类把"第三方 BOM 静默改版本/引入被否候选"变成可判定的规则；违规产物必须被检出（红），
 * 冻结台账本身必须通过（绿）。
 *
 * <p>规则（对应 06-upstream-upgrade.md §3/§4.1 与 F02 的 Go/No-Go 结论）：
 * <ul>
 *   <li>{@code VERSION_SPLIT}：同一坐标出现多个版本（版本分裂，传递依赖被顶替）；</li>
 *   <li>{@code UNAPPROVED_VERSION_CHANGE}：冻结坐标的版本与台账不一致（未走升级演练就改版本）；</li>
 *   <li>{@code FORBIDDEN_COMPONENT}：引入 F02 判 no-go / not-adopted 的组件；</li>
 *   <li>{@code PLATFORM_LINE_MISMATCH}：Spring Boot 脱离冻结的 3.5 线（Spring AI 2.x 要求 Boot 4.1.1）。</li>
 * </ul>
 */
final class UpstreamDependencyPolicy {

    record Coordinate(String groupId, String artifactId, String version) {
        String key() {
            return groupId + ":" + artifactId;
        }
    }

    enum Rule {
        VERSION_SPLIT,
        UNAPPROVED_VERSION_CHANGE,
        FORBIDDEN_COMPONENT,
        PLATFORM_LINE_MISMATCH
    }

    record Violation(Rule rule, String detail) {
        boolean is(Rule expected) {
            return rule == expected;
        }
    }

    /** 冻结台账：坐标 → 版本（F02 2026-09-26；netty/Boot 由平台 BOM 冻结）。 */
    static final Map<String, String> FROZEN_VERSIONS = Map.ofEntries(
            Map.entry("org.springframework.ai:spring-ai-model", "1.1.8"),
            Map.entry("org.springframework.ai:spring-ai-openai", "1.1.8"),
            Map.entry("org.springframework.ai:spring-ai-commons", "1.1.8"),
            Map.entry("org.springframework.ai:spring-ai-retry", "1.1.8"),
            Map.entry("org.springframework.ai:spring-ai-template-st", "1.1.8"),
            Map.entry("io.qdrant:client", "1.13.0"),
            Map.entry("org.apache.tika:tika-core", "3.2.3"),
            Map.entry("org.apache.pdfbox:pdfbox", "3.0.5"),
            Map.entry("org.apache.poi:poi-ooxml", "5.4.1"),
            Map.entry("org.springframework.boot:spring-boot", "3.5.16"),
            Map.entry("io.netty:netty-common", "4.2.17.Final"),
            Map.entry("io.netty:netty-buffer", "4.2.17.Final"),
            Map.entry("io.netty:netty-transport", "4.2.17.Final"),
            Map.entry("io.netty:netty-codec", "4.2.17.Final"),
            Map.entry("io.netty:netty-handler", "4.2.17.Final"));

    /** 平台 Boot 冻结线（BOM 值 3.5.16）；4.x 属独立平台升级决策。 */
    static final String PLATFORM_BOOT_LINE_PREFIX = "3.5.";

    /** F02 判 no-go / not-adopted 的坐标前缀（引入即阻断，见 upstream-registry.yaml）。 */
    static final List<String> FORBIDDEN_COORDINATE_PREFIXES =
            List.of("com.alibaba.cloud.ai:", "org.springframework.ai:spring-ai-alibaba");

    private UpstreamDependencyPolicy() {}

    /** 冻结的关键坐标子集（覆盖全部判定点；来源：F02 证据 §10.3 的真实产品依赖树）。 */
    static List<Coordinate> frozenProductTree() {
        List<Coordinate> tree = new ArrayList<>();
        FROZEN_VERSIONS.forEach((key, version) -> {
            int separator = key.indexOf(':');
            tree.add(new Coordinate(key.substring(0, separator), key.substring(separator + 1), version));
        });
        return List.copyOf(tree);
    }

    static List<Violation> evaluate(List<Coordinate> inventory) {
        List<Violation> violations = new ArrayList<>();

        Map<String, Set<String>> versionsByCoordinate = new LinkedHashMap<>();
        for (Coordinate coordinate : inventory) {
            versionsByCoordinate
                    .computeIfAbsent(coordinate.key(), ignored -> new LinkedHashSet<>())
                    .add(coordinate.version());
        }
        versionsByCoordinate.forEach((key, versions) -> {
            if (versions.size() > 1) {
                violations.add(new Violation(Rule.VERSION_SPLIT, "坐标 " + key + " 出现多个版本：" + versions));
            }
        });

        for (Coordinate coordinate : inventory) {
            String frozen = FROZEN_VERSIONS.get(coordinate.key());
            if (frozen != null && !frozen.equals(coordinate.version())) {
                violations.add(new Violation(
                        Rule.UNAPPROVED_VERSION_CHANGE,
                        "冻结坐标 " + coordinate.key() + " 的版本从 " + frozen + " 变为 " + coordinate.version()
                                + "：未登记升级演练与台账更新前不得变更"));
            }
        }

        for (Coordinate coordinate : inventory) {
            String key = coordinate.key();
            boolean forbiddenPrefix = FORBIDDEN_COORDINATE_PREFIXES.stream().anyMatch(key::startsWith);
            boolean springAiTwoLine = key.startsWith("org.springframework.ai:spring-ai")
                    && coordinate.version().startsWith("2.");
            if (forbiddenPrefix || springAiTwoLine) {
                violations.add(new Violation(
                        Rule.FORBIDDEN_COMPONENT,
                        "坐标 " + key + ":" + coordinate.version() + " 属 F02 已否决/未采用候选（Spring AI 2.x 需 Boot 4.1.1；"
                                + "Alibaba 线未做兼容验证），不得进入产品依赖树"));
            }
        }

        for (Coordinate coordinate : inventory) {
            if ("org.springframework.boot".equals(coordinate.groupId())
                    && !coordinate.version().startsWith(PLATFORM_BOOT_LINE_PREFIX)) {
                violations.add(new Violation(
                        Rule.PLATFORM_LINE_MISMATCH,
                        "Spring Boot " + coordinate.version() + " 脱离冻结线 " + PLATFORM_BOOT_LINE_PREFIX
                                + "x：切换 Boot 4 属独立平台升级项目"));
            }
        }
        return violations;
    }

    /** 从真实 BOM 的 {@code <properties>} 读取冻结属性值。 */
    static String bomProperty(String propertyName) {
        String xml = CompatibilityRepositorySupport.readString(CompatibilityRepositorySupport.dependenciesBom());
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document =
                    factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList properties = document.getElementsByTagName("properties");
            for (int index = 0; index < properties.getLength(); index++) {
                Element element = (Element) properties.item(index);
                NodeList children = element.getChildNodes();
                for (int child = 0; child < children.getLength(); child++) {
                    if (children.item(child) instanceof Element property
                            && propertyName.equals(property.getTagName())) {
                        return property.getTextContent().trim();
                    }
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "解析依赖 BOM 失败：" + CompatibilityRepositorySupport.dependenciesBom(), exception);
        }
        throw new IllegalStateException("BOM 中不存在冻结属性：" + propertyName);
    }

    /** 从真实上游台账读取某组件的 selected.version（简单缩进解析，只认 selected 段下的第一个 version）。 */
    static String registrySelectedVersion(String componentId) {
        String yaml = CompatibilityRepositorySupport.readString(CompatibilityRepositorySupport.upstreamRegistry());
        String currentComponent = null;
        boolean inSelected = false;
        for (String rawLine : yaml.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.startsWith("- componentId:")) {
                currentComponent = line.substring("- componentId:".length()).strip();
                inSelected = false;
                continue;
            }
            if (!componentId.equals(currentComponent)) {
                continue;
            }
            if ("selected:".equals(line)) {
                inSelected = true;
                continue;
            }
            if (inSelected && line.startsWith("version:")) {
                return line.substring("version:".length()).strip();
            }
            if (line.startsWith("componentId:") || line.startsWith("status:")) {
                inSelected = false;
            }
        }
        throw new IllegalStateException("上游台账中未找到组件 " + componentId + " 的 selected.version");
    }
}
