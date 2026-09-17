package com.basicframework.module.ai.api.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 跨语言协议夹具测试：与 TS 侧（{@code @vben/ai-contracts}）读取同一份
 * {@code docs/contracts/ai/samples}。命名约定即契约：{@code *.valid.json} 必须接受，
 * {@code *.invalid.json} 必须拒绝（绑定失败或协议校验失败都算拒绝）。
 */
class AiProtocolFixtureTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static Path samplesDir() {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 8 && dir != null; depth++) {
            Path candidate = dir.resolve("docs/contracts/ai/samples");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到 docs/contracts/ai/samples：测试必须能到达仓库根");
    }

    private static void parseAndValidate(String name, String payload) throws IOException {
        switch (prefix(name)) {
            case "result-block" ->
                OBJECT_MAPPER.readValue(payload, AiResultBlockDTO.class).validate();
            case "theme-tokens" -> parseTheme(payload);
            case "run-event" ->
                OBJECT_MAPPER.readValue(payload, AiRunEventDTO.class).validate();
            case "chart-spec" ->
                OBJECT_MAPPER.readValue(payload, AiChartSpecDTO.class).validate();
            default -> {
                // query-plan / report-spec 的设计契约由文档校验脚本负责，这里只要求可解析
                OBJECT_MAPPER.readTree(payload);
            }
        }
    }

    /** 主题校验：与 TS 侧保持一致的最小规则（颜色必须是 #RGB/#RRGGBB，半径 0..24）。 */
    private static void parseTheme(String payload) throws IOException {
        var node = OBJECT_MAPPER.readTree(payload);
        String color = node.path("primaryColor").asText("");
        if (!color.matches("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")) {
            throw new IllegalArgumentException("非法主题颜色：" + color);
        }
        int radius = node.path("radius").asInt(-1);
        if (radius < 0 || radius > 24) {
            throw new IllegalArgumentException("主题圆角越界：" + radius);
        }
    }

    private static String prefix(String name) {
        int index = name.indexOf('.');
        return name.substring(0, index);
    }

    private static List<Path> sampleFiles(String suffix) throws IOException {
        try (Stream<Path> stream = Files.list(samplesDir())) {
            return stream.filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    @Test
    void acceptsEveryValidSample() throws IOException {
        List<Path> files = sampleFiles(".valid.json");
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            parseAndValidate(file.getFileName().toString(), Files.readString(file));
        }
    }

    @Test
    void rejectsEveryInvalidSample() throws IOException {
        List<Path> files = sampleFiles(".invalid.json");
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            String name = file.getFileName().toString();
            String payload = Files.readString(file);
            // 拒绝 = 绑定失败（未知字段/类型）或协议校验失败，两者都必须发生
            try {
                parseAndValidate(name, payload);
                org.junit.jupiter.api.Assertions.fail("夹具 " + name + " 必须被拒绝");
            } catch (Exception expected) {
                assertThat(expected).isNotNull();
            }
        }
    }

    @Test
    void moneyKeepsDecimalPrecisionAcrossBindingAndWriteBack() throws IOException {
        String payload = Files.readString(samplesDir().resolve("result-block.chart-money.valid.json"));

        AiResultBlockDTO block = OBJECT_MAPPER.readValue(payload, AiResultBlockDTO.class);
        block.validate();

        AiChartSeriesDTO series = block.spec().series().get(0);
        // 反序列化后精度与标度不变（BigDecimal，不经 double）
        assertThat(series.data().get(0)).isEqualByComparingTo(new BigDecimal("12345678901234.56"));
        assertThat(series.data().get(0).scale()).isEqualTo(2);
        // 写出协议时使用十进制字符串，保持原精度
        assertThat(series.toProtocolData()).containsExactly("12345678901234.56", "98765432109876.54");
        assertThat(block.spec().type()).isEqualTo("bar");
        assertThat(block.spec().categories()).containsExactly("一月", "二月");
        assertThat(block.kind()).isEqualTo("chart");
    }

    @Test
    void validatesTextAndErrorBlocksAndRejectsMismatchedShape() {
        new AiResultBlockDTO("text", "你好", null, null).validate();
        new AiResultBlockDTO("error", null, null, "上游超时").validate();

        // kind 与配套字段不一致：text 块带 spec 必须拒绝
        assertThatThrownBy(() -> new AiResultBlockDTO(
                                "text",
                                "x",
                                new AiChartSpecDTO(
                                        "bar",
                                        null,
                                        List.of("a"),
                                        List.of(new AiChartSeriesDTO("s", List.of(BigDecimal.ONE)))),
                                null)
                        .validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能包含 text");
        // 缺失 kind
        assertThatThrownBy(() -> new AiResultBlockDTO(null, "x", null, null).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少 kind");
        // 未知 kind 直接在协议校验层拒绝（夹具中的未知 kind 会先在绑定阶段失败，这里覆盖校验分支）
        assertThatThrownBy(() -> new AiResultBlockDTO("html", null, null, null).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知结果块 kind");
        // 超长文本
        assertThatThrownBy(() -> new AiResultBlockDTO("text", "x".repeat(20_001), null, null).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("长度上限");
        // chart 块缺 spec / error 块缺 message / error 块超长
        assertThatThrownBy(() -> new AiResultBlockDTO("chart", null, null, null).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能包含 spec");
        assertThatThrownBy(() -> new AiResultBlockDTO("error", null, null, null).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能包含 message");
        assertThatThrownBy(() -> new AiResultBlockDTO("error", null, null, "x".repeat(1_001)).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("长度上限");
    }

    @Test
    void chartSpecRejectsInconsistentSeriesAndAcceptsNumbers() {
        AiChartSpecDTO valid = new AiChartSpecDTO(
                "line",
                "t",
                List.of("a", "b"),
                List.of(new AiChartSeriesDTO("s", List.of(BigDecimal.ONE, new BigDecimal("2.50")))));
        valid.validate();
        assertThat(valid.series().get(0).toProtocolData()).containsExactly("1", "2.50");

        // 未知图表类型
        assertThatThrownBy(() -> new AiChartSpecDTO(
                                "radar",
                                null,
                                List.of("a"),
                                List.of(new AiChartSeriesDTO("s", List.of(BigDecimal.ONE))))
                        .validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知图表类型");
        // 长度不一致
        assertThatThrownBy(() -> new AiChartSpecDTO(
                                "bar",
                                null,
                                List.of("a"),
                                List.of(new AiChartSeriesDTO("s", List.of(BigDecimal.ONE, BigDecimal.ONE))))
                        .validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("长度必须与类目长度一致");
        // 空类目 / 空系列 / 空名称 / 空数值
        assertThatThrownBy(
                        () -> new AiChartSpecDTO("bar", null, List.of(), List.of(new AiChartSeriesDTO("s", List.of())))
                                .validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("类目不能为空");
        assertThatThrownBy(() -> new AiChartSpecDTO("bar", null, List.of("a"), List.of()).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("系列不能为空");
        assertThatThrownBy(() -> new AiChartSpecDTO(
                                "bar", null, List.of("a"), List.of(new AiChartSeriesDTO(" ", List.of(BigDecimal.ONE))))
                        .validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("名称不能为空");
        assertThatThrownBy(() -> new AiChartSpecDTO(
                                "bar",
                                null,
                                List.of("a"),
                                List.of(new AiChartSeriesDTO("s", java.util.Collections.singletonList(null))))
                        .validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("存在空数值");
    }

    @Test
    void runEventRejectsUnknownVersionAndInvalidFields() {
        new AiRunEventDTO("1.0", 3, "run_demo123", "RUNNING", null, "2026-09-17T00:00:00Z").validate();
        new AiRunEventDTO(
                        "1.0",
                        4,
                        "run_demo123",
                        "SUCCEEDED",
                        new AiResultBlockDTO("text", "完成", null, null),
                        "2026-09-17T00:00:00Z")
                .validate();

        assertThatThrownBy(() -> new AiRunEventDTO("2.0", 1, "run_demo123", "RUNNING", null, "t").validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知协议版本");
        assertThatThrownBy(() -> new AiRunEventDTO("1.0", 0, "run_demo123", "RUNNING", null, "t").validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("序号必须为正整数");
        assertThatThrownBy(() -> new AiRunEventDTO("1.0", 1, "bad", "RUNNING", null, "t").validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("运行业务键不合法");
        assertThatThrownBy(() -> new AiRunEventDTO("1.0", 1, "run_demo123", "UNKNOWN", null, "t").validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知运行状态");
        assertThatThrownBy(() -> new AiRunEventDTO("1.0", 1, "run_demo123", "RUNNING", null, " ").validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少创建时间");
    }
}
