package com.basicframework.module.ai.service.query.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.domain.query.ResolvedDatasetVersion;
import com.basicframework.module.ai.testfixture.AiQueryPlanFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D05 摘要与提示词：模型可见范围、可信当前时间、可复现性与不含内部实现细节。 */
class AiDatasetSummaryBuilderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);

    private final AiDatasetSummaryBuilder builder = new AiDatasetSummaryBuilder();

    @Test
    void summaryContainsOnlyAuthorizedSemanticMetadata() {
        ResolvedDatasetVersion dataset = AiQueryPlanFixture.dataset(List.of("net_amount", "amount"));
        String summary = builder.buildSummary(dataset, CLOCK);

        assertThat(summary)
                .contains("\"datasetId\":\"dset_it-query-orders\"")
                .contains("\"datasetVersion\":1")
                .contains("\"currentTime\":\"2026-09-20T20:00:00+08:00\"")
                .contains("\"timezone\":\"Asia/Shanghai\"")
                .contains("\"granularity\":\"DAY\"")
                .contains("\"code\":\"net_amount\"")
                .contains("\"aggregation\":\"SUM\"");
        assertThat(summary)
                .as("授权范围外的字段不得出现在摘要里")
                .doesNotContain("region")
                .doesNotContain("status")
                .doesNotContain("order_id");
        assertThat(summary)
                .as("摘要不得包含物理实现与凭据信息")
                .doesNotContain("sourceColumn")
                .doesNotContain("it_query")
                .doesNotContain("password")
                .doesNotContain("ai:dataset:field:status")
                .doesNotContain("RESTRICTED");
    }

    @Test
    void summaryIsDeterministicForTheSameClock() {
        ResolvedDatasetVersion dataset = AiQueryPlanFixture.dataset();
        assertThat(builder.buildSummary(dataset, CLOCK)).isEqualTo(builder.buildSummary(dataset, CLOCK));

        Clock later = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);
        assertThat(builder.buildSummary(dataset, later))
                .as("当前时间来自注入时钟")
                .isNotEqualTo(builder.buildSummary(dataset, CLOCK));
    }

    @Test
    void promptCarriesContractSummaryAndRepairHint() {
        ResolvedDatasetVersion dataset = AiQueryPlanFixture.dataset();
        String prompt = builder.buildPrompt(dataset, "上个月华东的销售额", CLOCK, null);

        assertThat(prompt)
                .contains("只允许使用下面给出的数据集与字段目录")
                .contains("禁止编造字段、禁止输出 SQL")
                .contains("\"kind\"")
                .contains("上个月华东的销售额")
                .contains("\"currentTime\"");
        assertThat(prompt).doesNotContain("上一次输出未通过平台校验");

        String repaired = builder.buildPrompt(dataset, "上个月华东的销售额", CLOCK, "查询计划不合规");
        assertThat(repaired).contains("上一次输出未通过平台校验").contains("不得扩大数据集或字段范围");

        assertThat(builder.outputSchema())
                .contains("PLAN")
                .contains("CLARIFICATION")
                .contains("\"const\": \"1.0\"");
    }

    @Test
    void outputSchemaIsParseableJsonObject() {
        // M03 要求 jsonSchema 本身是可解析的 JSON 对象（这里用平台工具反证）
        assertThat(com.basicframework.framework.common.util.json.JsonUtils.parseObject(
                        builder.outputSchema(), java.util.Map.class))
                .containsKey("properties");
    }
}
