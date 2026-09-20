package com.basicframework.module.ai.service.query.planner;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.domain.query.ResolvedDatasetVersion;
import com.basicframework.module.ai.domain.semantic.AiDatasetDefinition;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 数据集摘要与提示词构建（D05）：模型能看到的**全部**信息都在这里定义。
 *
 * <p>边界（对应卡片"仅提供授权数据集摘要给模型"）：
 * <ul>
 *   <li>只含本次授权范围内的字段/指标/维度（授权外的字段不会出现在摘要里）；</li>
 *   <li>只含语义元数据：逻辑码、别名、语义类型、单位、枚举取值、粒度、时区与**可信当前时间**；</li>
 *   <li>不含：行样本、物理表名/库名、连接信息、凭据、权限码、SQL；</li>
 *   <li>当前时间来自注入的 {@link Clock}（固定时钟可复现），不让模型自己"猜今天几号"。</li>
 * </ul>
 */
@Component
public class AiDatasetSummaryBuilder {

    private static final DateTimeFormatter CURRENT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** 输出契约（M03 要求是"可解析的 JSON 对象"）：只描述运行层判别结果与两种载荷。 */
    private static final String OUTPUT_SCHEMA =
            """
            {
              "type": "object",
              "required": ["kind"],
              "properties": {
                "kind": {"enum": ["PLAN", "CLARIFICATION"]},
                "plan": {
                  "type": "object",
                  "required": ["schemaVersion", "datasetId", "datasetVersion", "metrics", "dimensions",
                               "filters", "timeRange", "orderBy", "limit"],
                  "properties": {
                    "schemaVersion": {"const": "1.0"},
                    "datasetId": {"type": "string"},
                    "datasetVersion": {"type": "integer"},
                    "metrics": {"type": "array", "items": {"type": "string"}},
                    "dimensions": {"type": "array", "items": {"type": "string"}},
                    "filters": {"type": "array"},
                    "timeRange": {"type": ["object", "null"]},
                    "orderBy": {"type": "array"},
                    "limit": {"type": "integer", "minimum": 1, "maximum": 1000}
                  }
                },
                "clarification": {
                  "type": "object",
                  "required": ["question"],
                  "properties": {
                    "question": {"type": "string"},
                    "candidates": {"type": "array"}
                  }
                }
              }
            }
            """;

    /** 输出契约文本（原样交给模型；平台侧仍会二次校验）。 */
    public String outputSchema() {
        return OUTPUT_SCHEMA;
    }

    /** 模型可见的数据集摘要（JSON 文本）。 */
    public String buildSummary(ResolvedDatasetVersion dataset, Clock clock) {
        AiDatasetDefinition definition = dataset.definition();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("datasetId", dataset.planDatasetId());
        summary.put("datasetVersion", dataset.datasetVersionNo());
        summary.put("grain", definition.grain());
        summary.put("currentTime", currentTime(clock, definition));
        summary.put(
                "timezone", definition.time() == null ? null : definition.time().timezone());
        summary.put(
                "time",
                definition.time() == null
                        ? null
                        : ordered(
                                List.of("field", "granularity"),
                                List.of(
                                        definition.time().field(),
                                        definition.time().granularity())));
        List<Object> fields = new ArrayList<>();
        for (AiDatasetDefinition.Field field : definition.fields()) {
            if (!dataset.allowsField(field.name())) {
                continue;
            }
            fields.add(ordered(
                    List.of("code", "aliases", "type", "unit", "enumValues"),
                    java.util.Arrays.asList(
                            field.name(),
                            field.aliases(),
                            field.type(),
                            field.unit(),
                            field.enumValues().isEmpty() ? null : field.enumValues())));
        }
        summary.put("fields", fields);
        List<Object> metrics = new ArrayList<>();
        for (AiDatasetDefinition.Metric metric : definition.metrics()) {
            if (!dataset.allowsField(metric.name())) {
                continue;
            }
            metrics.add(ordered(
                    List.of("code", "field", "aggregation", "unit"),
                    List.of(metric.name(), metric.field(), metric.aggregation(), metric.unit())));
        }
        summary.put("metrics", metrics);
        List<Object> dimensions = new ArrayList<>();
        for (AiDatasetDefinition.Dimension dimension : definition.dimensions()) {
            if (!dataset.allowsField(dimension.name())) {
                continue;
            }
            dimensions.add(ordered(List.of("code", "field"), List.of(dimension.name(), dimension.field())));
        }
        summary.put("dimensions", dimensions);
        return JsonUtils.toJsonString(summary);
    }

    /**
     * 规划提示词：角色与硬约束 + 摘要 + 用户问题 +（可选）修复提示。
     *
     * <p>硬约束写进提示词只是"降低出错率"，真正的边界由校验器保证：提示词被注入也不会越过校验。
     */
    public String buildPrompt(ResolvedDatasetVersion dataset, String question, Clock clock, String repairHint) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是企业数据查询规划器。只允许使用下面给出的数据集与字段目录，禁止编造字段、禁止输出 SQL。\n")
                .append("只返回一个 JSON 对象，kind 为 PLAN 或 CLARIFICATION；口径不清、字段有歧义或超出范围时返回 CLARIFICATION。\n")
                .append("时间使用摘要中的 currentTime 与时区，输出 ISO-8601 带偏移的 [startInclusive, endExclusive)。\n")
                .append("metrics 至少一个；dimensions/orderBy 只能引用本次查询选中的指标或维度码；limit 1..1000。\n")
                .append("输出契约：\n")
                .append(OUTPUT_SCHEMA)
                .append("\n数据集摘要（唯一可用事实来源）：\n")
                .append(buildSummary(dataset, clock))
                .append("\n用户问题：")
                .append(question == null ? "" : question);
        if (StringUtils.hasText(repairHint)) {
            prompt.append("\n上一次输出未通过平台校验，原因：").append(repairHint).append("。请只修正该问题，不得扩大数据集或字段范围。");
        }
        return prompt.toString();
    }

    private static String currentTime(Clock clock, AiDatasetDefinition definition) {
        ZoneId zone = definition.time() == null
                ? clock.getZone()
                : ZoneId.of(definition.time().timezone());
        return clock.instant().atZone(zone).format(CURRENT_TIME_FORMAT);
    }

    private static Map<String, Object> ordered(List<String> keys, List<Object> values) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (int index = 0; index < keys.size(); index++) {
            ordered.put(keys.get(index), values.get(index));
        }
        return ordered;
    }
}
