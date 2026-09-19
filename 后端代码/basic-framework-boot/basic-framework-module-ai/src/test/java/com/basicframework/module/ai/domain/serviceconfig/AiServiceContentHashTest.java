package com.basicframework.module.ai.domain.serviceconfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.domain.serviceconfig.AiServiceContentHash.ResourceBinding;
import java.util.List;
import org.junit.jupiter.api.Test;

/** S02 内容摘要：稳定、与绑定顺序无关、对任一内容项敏感。 */
class AiServiceContentHashTest {

    private static final List<ResourceBinding> BINDINGS =
            List.of(new ResourceBinding("REPORT", "report-1", "READ,EXECUTE"));

    private static String hash(
            Long endpointId,
            Integer revision,
            String prompt,
            String input,
            String output,
            String caps,
            Integer threshold) {
        return AiServiceContentHash.compute(endpointId, revision, prompt, input, output, caps, threshold, BINDINGS);
    }

    @Test
    void sameContentProducesSameHash() {
        assertThat(hash(1L, 3, "p", "{\"a\":1}", null, "TEXT", 80))
                .isEqualTo(hash(1L, 3, "p", "{\"a\":1}", null, "TEXT", 80))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void anyFrozenItemChangesTheHash() {
        String base = hash(1L, 3, "p", "{\"a\":1}", null, "TEXT", 80);
        assertThat(hash(2L, 3, "p", "{\"a\":1}", null, "TEXT", 80)).isNotEqualTo(base);
        assertThat(hash(1L, 4, "p", "{\"a\":1}", null, "TEXT", 80))
                .as("端点配置版本变化必须改变摘要")
                .isNotEqualTo(base);
        assertThat(hash(1L, 3, "p2", "{\"a\":1}", null, "TEXT", 80))
                .as("提示词变化必须改变摘要")
                .isNotEqualTo(base);
        assertThat(hash(1L, 3, "p", "{\"a\":2}", null, "TEXT", 80))
                .as("输入 Schema 变化必须改变摘要")
                .isNotEqualTo(base);
        assertThat(hash(1L, 3, "p", "{\"a\":1}", "{\"b\":1}", "TEXT", 80))
                .as("输出 Schema 变化必须改变摘要")
                .isNotEqualTo(base);
        assertThat(hash(1L, 3, "p", "{\"a\":1}", null, "TEXT,STRUCTURED_OUTPUT", 80))
                .as("能力集合变化必须改变摘要")
                .isNotEqualTo(base);
        assertThat(hash(1L, 3, "p", "{\"a\":1}", null, "TEXT", 90))
                .as("评测门槛变化必须改变摘要（改门槛要重新评测）")
                .isNotEqualTo(base);
    }

    @Test
    void bindingOrderDoesNotMatterButContentDoes() {
        String one = AiServiceContentHash.compute(
                1L,
                3,
                "p",
                "{\"a\":1}",
                null,
                "TEXT",
                0,
                List.of(
                        new ResourceBinding("REPORT", "report-1", "READ,EXECUTE"),
                        new ResourceBinding("DATASET", "ds-1", "READ")));
        String reordered = AiServiceContentHash.compute(
                1L,
                3,
                "p",
                "{\"a\":1}",
                null,
                "TEXT",
                0,
                List.of(
                        new ResourceBinding("DATASET", "ds-1", "READ"),
                        new ResourceBinding("REPORT", "report-1", "EXECUTE,READ")));
        assertThat(one).as("绑定顺序与动作顺序不影响摘要").isEqualTo(reordered);

        String dropped = AiServiceContentHash.compute(
                1L,
                3,
                "p",
                "{\"a\":1}",
                null,
                "TEXT",
                0,
                List.of(new ResourceBinding("REPORT", "report-1", "READ,EXECUTE")));
        assertThat(dropped).as("绑定集合变化必须改变摘要").isNotEqualTo(one);
    }
}
