package com.basicframework.module.ai.domain.workflow;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_GRAPH_CYCLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_GRAPH_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_GRAPH_NO_EXIT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_NODE_TYPE_MISMATCH;

import com.basicframework.framework.common.util.json.JsonUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 流程图发布期结构校验（X08）：环、无出口、类型不匹配、端口与配置形状——全部在发布前拒绝。
 *
 * <p>校验顺序即错误语义（先形状后拓扑）：
 * <ol>
 *   <li>形状：唯一开始、至少一个结束、开始无入边、结束无出边（类型/端口 → 类型不匹配）；</li>
 *   <li>节点配置：按类型判定（配置缺失/越界/取值非法 → 类型不匹配；数据库引用在服务层另行核对）；</li>
 *   <li>拓扑：无环（DFS 三色）→ 无不可达节点（从开始正向可达）→ 无无出口节点（反向可达结束）。</li>
 * </ol>
 * 通过本校验的图必然是"从开始到结束的有界 DAG"，运行循环因此天然终止。
 */
public final class AiWorkflowGraphValidator {

    /** 模型提示词模板长度上限。 */
    public static final int MAX_PROMPT_TEMPLATE_LENGTH = 2_000;

    /** 知识检索问题模板长度上限。 */
    public static final int MAX_QUERY_TEMPLATE_LENGTH = 1_000;

    /** 数据查询冻结计划 JSON 长度上限（与 D05 计划规模一致）。 */
    public static final int MAX_PLAN_JSON_LENGTH = 3_500;

    /** 工具参数 JSON 长度上限（运行期还会按版本 schema 重校验）。 */
    public static final int MAX_TOOL_ARGUMENTS_LENGTH = 2_000;

    /** 条件字面量长度上限。 */
    public static final int MAX_CONDITION_VALUE_LENGTH = 256;

    /** 校验并返回通过的图（结构判定只依赖图本身，不做数据库引用核对）。 */
    public AiWorkflowGraph validate(String graphJson) {
        AiWorkflowGraph graph = AiWorkflowGraph.parse(graphJson);
        validateShape(graph);
        validateNodeConfigs(graph);
        validateTopology(graph);
        return graph;
    }

    // ---------- 形状与端口 ----------

    private void validateShape(AiWorkflowGraph graph) {
        List<AiWorkflowGraph.Node> starts = nodesOfType(graph, AiWorkflowNodeType.START);
        if (starts.size() != 1) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        if (nodesOfType(graph, AiWorkflowNodeType.END).isEmpty()) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        Map<String, List<AiWorkflowGraph.Edge>> incoming = incomingOf(graph);
        for (AiWorkflowGraph.Edge edge : graph.edges()) {
            AiWorkflowGraph.Node from = requireNode(graph, edge.from());
            AiWorkflowGraph.Node to = requireNode(graph, edge.to());
            // 开始节点不允许有入边，结束节点不允许有出边
            if (to.type() == AiWorkflowNodeType.START || from.type() == AiWorkflowNodeType.END) {
                throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
            }
            if (from.type() == AiWorkflowNodeType.CONDITION) {
                // 条件节点：恰好两条出边，TRUE/FALSE 各一条、不带其余分支
                if (edge.branch() == null) {
                    throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
                }
            } else {
                // 非条件节点：单出边且不带分支
                if (edge.branch() != null) {
                    throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
                }
            }
        }
        // 端口基数：条件节点 TRUE/FALSE 恰好各一条；其余节点最多一条出边；所有节点最多一条入边
        // （汇聚在 V1.2 不允许：每个节点的输入是唯一上游的输出，多入边让"引用哪个上游"产生歧义）
        for (AiWorkflowGraph.Node node : graph.nodes()) {
            List<AiWorkflowGraph.Edge> outgoing = graph.outgoing(node.key());
            if (node.type() == AiWorkflowNodeType.CONDITION) {
                Set<String> branches = new LinkedHashSet<>();
                outgoing.forEach(edge -> branches.add(edge.branch()));
                if (outgoing.size() != 2
                        || !branches.containsAll(Set.of(AiWorkflowGraph.BRANCH_TRUE, AiWorkflowGraph.BRANCH_FALSE))) {
                    throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
                }
            } else if (outgoing.size() > 1) {
                throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
            }
            if (incoming.getOrDefault(node.key(), List.of()).size() > 1) {
                throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
            }
        }
        // 条件节点的比较引用：必须是产出输出、且可达（祖先链上）的节点——
        // 前向引用在运行期拿不到输出，等于引用了一个"还不存在的节点"
        for (AiWorkflowGraph.Node node : nodesOfType(graph, AiWorkflowNodeType.CONDITION)) {
            String ref = text(node.config().get("ref"));
            AiWorkflowGraph.Node referenced = ref == null ? null : graph.node(ref);
            if (referenced == null
                    || !referenced.type().producesOutput()
                    || !ancestorsOf(incoming, node.key()).contains(ref)) {
                throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
            }
        }
    }

    /** 祖先链（入度 ≤ 1 的图里是一条链）：条件只能比较已经执行过的输出。 */
    private Set<String> ancestorsOf(Map<String, List<AiWorkflowGraph.Edge>> incoming, String key) {
        Set<String> ancestors = new HashSet<>();
        String cursor = key;
        while (true) {
            List<AiWorkflowGraph.Edge> edges = incoming.get(cursor);
            if (edges == null || edges.isEmpty()) {
                return ancestors;
            }
            cursor = edges.get(0).from();
            if (!ancestors.add(cursor)) {
                // 环在拓扑阶段才判（形状先于拓扑），这里防御性终止
                return ancestors;
            }
        }
    }

    // ---------- 节点配置（形状；数据库引用在服务层核对） ----------

    private void validateNodeConfigs(AiWorkflowGraph graph) {
        for (AiWorkflowGraph.Node node : graph.nodes()) {
            switch (node.type()) {
                case START, END -> requireEmptyConfig(node);
                case MODEL -> validateModelConfig(node);
                case KNOWLEDGE_RETRIEVAL -> validateKnowledgeConfig(node);
                case DATA_QUERY -> validateDataQueryConfig(node);
                case TOOL -> validateToolConfig(node);
                case CONDITION -> validateConditionConfig(node);
            }
        }
    }

    private void validateModelConfig(AiWorkflowGraph.Node node) {
        Long endpointId = positiveLong(node.config().get("endpointId"));
        if (endpointId == null) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        String prompt = text(node.config().get("promptTemplate"));
        if (prompt == null || prompt.length() > MAX_PROMPT_TEMPLATE_LENGTH) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
    }

    private void validateKnowledgeConfig(AiWorkflowGraph.Node node) {
        String query = text(node.config().get("query"));
        if (query == null || query.length() > MAX_QUERY_TEMPLATE_LENGTH) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        Integer topK = intOf(node.config().get("topK"));
        if (topK != null && (topK < 1 || topK > 20)) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        // 显式给出却不是合法正整数（0/负数/非数字）时拒绝：不能静默当成"未提供"，
        // 否则图里写下的取值与运行期实际取值不一致（发布校验必须能解释）
        if (node.config().containsKey("topK") && topK == null) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
    }

    private void validateDataQueryConfig(AiWorkflowGraph.Node node) {
        if (positiveLong(node.config().get("datasetId")) == null
                || positiveLong(node.config().get("datasetVersionId")) == null) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        String planJson = text(node.config().get("planJson"));
        if (planJson == null || planJson.length() > MAX_PLAN_JSON_LENGTH) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        Map<?, ?> plan;
        try {
            plan = JsonUtils.parseObject(planJson, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        if (plan == null || !(plan.get("planHash") instanceof String planHash) || planHash.isBlank()) {
            // 冻结计划必须带计划哈希（运行期 revalidate 以它判定"同一份计划"）
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        AiWorkflowRowScope scope = AiWorkflowRowScope.parse(node.config().get("rowScope"));
        if (!scope.isEffective()) {
            // 行范围是数据查询节点的安全底线：空范围（或形状不完整）的节点不能发布
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
    }

    private void validateToolConfig(AiWorkflowGraph.Node node) {
        String toolCode = text(node.config().get("toolCode"));
        if (toolCode == null || toolCode.length() < 3 || toolCode.length() > 64) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        Object arguments = node.config().get("arguments");
        if (arguments == null) {
            return;
        }
        String argumentsJson = JsonUtils.toJsonString(arguments);
        if (!(arguments instanceof Map<?, ?>) || argumentsJson.length() > MAX_TOOL_ARGUMENTS_LENGTH) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
    }

    private void validateConditionConfig(AiWorkflowGraph.Node node) {
        String operator = text(node.config().get("operator"));
        if (operator == null || AiWorkflowConditionOperator.parse(operator).isEmpty()) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        String value = node.config().get("value") == null
                ? null
                : String.valueOf(node.config().get("value"));
        if (value == null || value.isBlank() || value.length() > MAX_CONDITION_VALUE_LENGTH) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
    }

    private void requireEmptyConfig(AiWorkflowGraph.Node node) {
        if (!node.config().isEmpty()) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
    }

    // ---------- 拓扑：无环 + 双向可达 ----------

    private void validateTopology(AiWorkflowGraph graph) {
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        graph.nodes().forEach(node -> adjacency.put(node.key(), new ArrayList<>()));
        graph.edges().forEach(edge -> adjacency.get(edge.from()).add(edge.to()));
        detectCycle(graph, adjacency);
        String start = nodesOfType(graph, AiWorkflowNodeType.START).get(0).key();
        Set<String> forward = reachable(adjacency, List.of(start));
        Map<String, List<String>> reverse = new LinkedHashMap<>();
        graph.nodes().forEach(node -> reverse.put(node.key(), new ArrayList<>()));
        graph.edges().forEach(edge -> reverse.get(edge.to()).add(edge.from()));
        // 反向可达的种子是**全部**结束节点（多结束的图里，节点只需通向其中之一）
        Set<String> backward = reachable(
                reverse,
                nodesOfType(graph, AiWorkflowNodeType.END).stream()
                        .map(AiWorkflowGraph.Node::key)
                        .toList());
        for (AiWorkflowGraph.Node node : graph.nodes()) {
            if (!forward.contains(node.key()) || !backward.contains(node.key())) {
                // 不可达（正向）或无出口（反向）：两个方向都必须在"开始 → 结束"的路径上
                throw exception(AI_WORKFLOW_GRAPH_NO_EXIT);
            }
        }
    }

    /** 三色 DFS：灰回边即环。 */
    private void detectCycle(AiWorkflowGraph graph, Map<String, List<String>> adjacency) {
        Map<String, Integer> color = new LinkedHashMap<>();
        graph.nodes().forEach(node -> color.put(node.key(), 0));
        for (AiWorkflowGraph.Node node : graph.nodes()) {
            if (color.get(node.key()) == 0 && hasGrayEdge(node.key(), adjacency, color)) {
                throw exception(AI_WORKFLOW_GRAPH_CYCLE);
            }
        }
    }

    private boolean hasGrayEdge(String key, Map<String, List<String>> adjacency, Map<String, Integer> color) {
        color.put(key, 1);
        try {
            for (String next : adjacency.get(key)) {
                int state = color.get(next);
                if (state == 1) {
                    return true;
                }
                if (state == 0 && hasGrayEdge(next, adjacency, color)) {
                    return true;
                }
            }
            return false;
        } finally {
            color.put(key, 2);
        }
    }

    private Set<String> reachable(Map<String, List<String>> adjacency, List<String> seeds) {
        Set<String> seen = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String seed : seeds) {
            if (seen.add(seed)) {
                stack.push(seed);
            }
        }
        while (!stack.isEmpty()) {
            for (String next : adjacency.get(stack.pop())) {
                if (seen.add(next)) {
                    stack.push(next);
                }
            }
        }
        return seen;
    }

    // ---------- 小工具 ----------

    private Map<String, List<AiWorkflowGraph.Edge>> incomingOf(AiWorkflowGraph graph) {
        Map<String, List<AiWorkflowGraph.Edge>> incoming = new LinkedHashMap<>();
        graph.nodes().forEach(node -> incoming.put(node.key(), new ArrayList<>()));
        graph.edges().forEach(edge -> incoming.get(edge.to()).add(edge));
        return incoming;
    }

    private AiWorkflowGraph.Node requireNode(AiWorkflowGraph graph, String key) {
        AiWorkflowGraph.Node node = graph.node(key);
        if (node == null) {
            // 解析已保证边连接已声明节点；这里是防御（图不可能构造出未知键）
            throw exception(AI_WORKFLOW_GRAPH_INVALID);
        }
        return node;
    }

    private List<AiWorkflowGraph.Node> nodesOfType(AiWorkflowGraph graph, AiWorkflowNodeType type) {
        return graph.nodes().stream().filter(node -> node.type() == type).toList();
    }

    private static String text(Object value) {
        if (!(value instanceof String textValue) || !StringUtils.hasText(textValue)) {
            return null;
        }
        return textValue.trim();
    }

    private static Long positiveLong(Object value) {
        if (value instanceof Integer integer) {
            return integer > 0 ? integer.longValue() : null;
        }
        if (value instanceof Long longValue) {
            return longValue > 0 ? longValue : null;
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text.trim()) > 0 ? Long.parseLong(text.trim()) : null;
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return null;
    }

    private static Integer intOf(Object value) {
        Long parsed = positiveLong(value);
        if (parsed == null || parsed > Integer.MAX_VALUE) {
            return null;
        }
        return parsed.intValue();
    }
}
