package com.basicframework.module.ai.domain.workflow;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_NODE_TYPE_MISMATCH;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.domain.query.QueryScope;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 数据查询节点的冻结行范围（X08）。
 *
 * <p>V1.2 边界：管理端受控运行没有"按调用方授权推导行范围"的执行上下文（该接缝属于应用端授权层），
 * 因此行范围在**发布期冻结为非空约束**并随图快照保存；运行期交给 R05 的受控执行，
 * 由编译器强制 AND 进 WHERE——运行不能扩大、也不能去掉这个范围（空范围在发布期就被拒绝，
 * "没有约束"从不等价于"不过滤"）。操作符只接受 EQ/IN，取值只接受 JSON 标量。
 */
public record AiWorkflowRowScope(List<Condition> conditions) {

    /** 条件数上限（设计期冻结的固定过滤，不需要更多）。 */
    private static final int MAX_CONDITIONS = 8;

    /** 单个条件的取值数上限。 */
    private static final int MAX_VALUES = 20;

    /** 冻结的行范围条件：物理列 + 操作符（EQ/IN）+ 标量取值。 */
    public record Condition(String sourceColumn, String operator, List<Object> values) {

        public Condition {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public AiWorkflowRowScope {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    /** 是否有效（非空、每条条件都有列/操作符/取值）——发布期与运行期共用同一判定。 */
    public boolean isEffective() {
        if (conditions.isEmpty() || conditions.size() > MAX_CONDITIONS) {
            return false;
        }
        for (Condition condition : conditions) {
            if (!StringUtils.hasText(condition.sourceColumn())
                    || condition.sourceColumn().length() > 64
                    || condition.values().isEmpty()
                    || condition.values().size() > MAX_VALUES) {
                return false;
            }
            if ("EQ".equals(condition.operator()) && condition.values().size() != 1) {
                return false;
            }
        }
        return true;
    }

    /** 解析图配置里的行范围（JSON 数组文本或已解析列表）；形状不合规抛类型不匹配。 */
    public static AiWorkflowRowScope parse(Object raw) {
        List<Map<String, Object>> items = rawItems(raw);
        List<Condition> conditions = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String operator = String.valueOf(item.get("operator"));
            if (!"EQ".equals(operator) && !"IN".equals(operator)) {
                throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
            }
            List<Object> values = scalarValues(item.get("values"));
            conditions.add(new Condition(String.valueOf(item.get("sourceColumn")), operator, values));
        }
        return new AiWorkflowRowScope(conditions);
    }

    /** 转成受控查询的行范围（R05/编译器强制拼进 WHERE）。 */
    public QueryScope toQueryScope() {
        List<QueryScope.Condition> conditions = new ArrayList<>();
        for (Condition condition : this.conditions) {
            conditions.add(
                    new QueryScope.Condition(condition.sourceColumn(), condition.operator(), condition.values()));
        }
        return new QueryScope(conditions);
    }

    private static List<Map<String, Object>> rawItems(Object raw) {
        List<?> items;
        if (raw instanceof String text) {
            if (!StringUtils.hasText(text) || text.length() > 2_000) {
                throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
            }
            try {
                items = JsonUtils.parseObject(text, List.class);
            } catch (IllegalArgumentException notAnArray) {
                throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
            }
        } else if (raw instanceof List<?> list) {
            items = list;
        } else {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        if (items == null || items.isEmpty() || items.size() > MAX_CONDITIONS) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
            }
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            map.forEach((key, value) -> entry.put(String.valueOf(key), value));
            result.add(entry);
        }
        return result;
    }

    private static List<Object> scalarValues(Object raw) {
        if (!(raw instanceof List<?> items) || items.isEmpty() || items.size() > MAX_VALUES) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        List<Object> values = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof String text
                    || item instanceof Boolean
                    || item instanceof BigDecimal
                    || item instanceof Integer
                    || item instanceof Long) {
                values.add(item);
            } else {
                // 只接受标量：嵌套结构进不了绑定参数
                throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
            }
        }
        return values;
    }
}
