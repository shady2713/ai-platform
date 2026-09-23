package com.basicframework.module.ai.service.report.revision;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_REVISION_PLAN_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 修订计划（R05）：模型对一次对话修改给出的**操作清单**。
 *
 * <p>输出契约固定为 {@code {"schemaVersion":"1.0","operations":[...]}}：键白名单、操作数上限、
 * 未知操作码一律拒绝。这里只做结构校验——"这些操作能不能落在这份报表上"由补丁器与 R01 校验器判定。
 *
 * <p>{@link #requiresQuery()} 是服务端对"要不要重新查库"的**唯一**结论：
 * 只要有一条数据类操作就必须走受控查询，模型无法把数据类操作伪装成展示类。
 */
public record AiReportRevisionPlan(List<AiReportRevisionOp> operations) {

    /** 契约版本。 */
    public static final String SCHEMA_VERSION = "1.0";

    /** 单次修订的操作数上限（防止模型回一份"整表重写"的清单）。 */
    public static final int MAX_OPERATIONS = 20;

    private static final Set<String> TOP_KEYS = Set.of("schemaVersion", "operations");

    public AiReportRevisionPlan {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }

    /** 解析模型输出（结构不合法抛稳定错误码）。 */
    public static AiReportRevisionPlan parse(String planJson) {
        if (planJson == null || planJson.isBlank()) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        Map<?, ?> parsed;
        try {
            parsed = JsonUtils.parseObject(planJson, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        if (parsed == null) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        parsed.forEach((key, value) -> values.put(String.valueOf(key), value));
        for (String key : values.keySet()) {
            if (!TOP_KEYS.contains(key)) {
                throw exception(AI_REPORT_REVISION_PLAN_INVALID);
            }
        }
        if (!SCHEMA_VERSION.equals(String.valueOf(values.get("schemaVersion")))) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        if (!(values.get("operations") instanceof List<?> raw) || raw.isEmpty() || raw.size() > MAX_OPERATIONS) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        return new AiReportRevisionPlan(
                raw.stream().map(AiReportRevisionOp::parse).toList());
    }

    /** 是否存在数据类操作（有则必须受控查询）。 */
    public boolean requiresQuery() {
        return operations.stream().anyMatch(AiReportRevisionOp::requiresQuery);
    }

    /** 需要重新查询的数据集编号（去重，保持声明顺序）。 */
    public Set<Long> queryDatasetIds() {
        Set<Long> ids = new LinkedHashSet<>();
        for (AiReportRevisionOp operation : operations) {
            if (!operation.requiresQuery()) {
                continue;
            }
            try {
                ids.add(Long.valueOf(operation.datasetId()));
            } catch (NumberFormatException notANumber) {
                // 数据集编号不是数字：模型在编造来源
                throw exception(AI_REPORT_REVISION_PLAN_INVALID);
            }
        }
        return ids;
    }

    /** 操作码列表（写入差异与审计）。 */
    public List<String> opCodes() {
        return operations.stream().map(AiReportRevisionOp::op).toList();
    }
}
