package com.basicframework.module.ai.domain.workflow;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_GRAPH_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 流程图受控契约（X08）：结构化 JSON 的唯一解析入口（编辑端与运行端共用）。
 *
 * <p>解析只做**形状与规模**判定（上限见常量；键模式、节点/边字段、分支取值），
 * 超出即整体拒绝——运行端拿到的图永远先经过这里，坏图不会进入执行循环。
 * 拓扑判定（环、无出口、端口类型）属于发布期校验，见 {@link AiWorkflowGraphValidator}。
 */
public final class AiWorkflowGraph {

    /** 节点数上限（发布期与解析期一致；运行步数预算由此天然有界）。 */
    public static final int MAX_NODES = 32;

    /** 边数上限。 */
    public static final int MAX_EDGES = 64;

    /** 图 JSON 长度上限（与列宽一致）。 */
    public static final int MAX_GRAPH_JSON_LENGTH = 16_000;

    /** 节点配置 JSON 长度上限。 */
    public static final int MAX_NODE_CONFIG_LENGTH = 4_000;

    /** 节点键模式：字母开头，字母数字下划线，1..64。 */
    public static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    /** 节点名称长度上限。 */
    public static final int MAX_NODE_NAME_LENGTH = 128;

    /** 条件分支取值。 */
    public static final String BRANCH_TRUE = "TRUE";

    public static final String BRANCH_FALSE = "FALSE";

    private final List<Node> nodes;

    private final List<Edge> edges;

    private final Map<String, Node> nodesByKey;

    private AiWorkflowGraph(List<Node> nodes, List<Edge> edges, Map<String, Node> nodesByKey) {
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.nodesByKey = Map.copyOf(nodesByKey);
    }

    /** 解析图 JSON；形状/规模/键/分支任何一处不合规都整体拒绝（不"尽力修复"）。 */
    public static AiWorkflowGraph parse(String graphJson) {
        if (!StringUtils.hasText(graphJson) || graphJson.length() > MAX_GRAPH_JSON_LENGTH) {
            throw exception(AI_WORKFLOW_GRAPH_INVALID);
        }
        Map<?, ?> root;
        try {
            root = JsonUtils.parseObject(graphJson, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_WORKFLOW_GRAPH_INVALID);
        }
        if (root == null) {
            throw exception(AI_WORKFLOW_GRAPH_INVALID);
        }
        List<Node> nodes = parseNodes(root.get("nodes"));
        List<Edge> edges = parseEdges(root.get("edges"), nodes);
        Map<String, Node> byKey = new LinkedHashMap<>();
        nodes.forEach(node -> byKey.put(node.key(), node));
        return new AiWorkflowGraph(nodes, edges, byKey);
    }

    /** 全部节点（保持声明顺序）。 */
    public List<Node> nodes() {
        return nodes;
    }

    /** 全部边（保持声明顺序）。 */
    public List<Edge> edges() {
        return edges;
    }

    /** 按键取节点；未知键返回 null（拓扑问题由发布期校验拒绝，运行期防御性返回）。 */
    public Node node(String key) {
        return key == null ? null : nodesByKey.get(key);
    }

    /** 节点的出边（保持声明顺序）。 */
    public List<Edge> outgoing(String key) {
        List<Edge> result = new ArrayList<>();
        for (Edge edge : edges) {
            if (key.equals(edge.from())) {
                result.add(edge);
            }
        }
        return result;
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    private static List<Node> parseNodes(Object raw) {
        if (!(raw instanceof List<?> items) || items.isEmpty() || items.size() > MAX_NODES) {
            throw exception(AI_WORKFLOW_GRAPH_INVALID);
        }
        Map<String, Node> seen = new LinkedHashMap<>();
        List<Node> nodes = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> rawNode)) {
                throw exception(AI_WORKFLOW_GRAPH_INVALID);
            }
            String key = stringField(rawNode, "key");
            if (key == null || !KEY_PATTERN.matcher(key).matches() || seen.containsKey(key)) {
                throw exception(AI_WORKFLOW_GRAPH_INVALID);
            }
            String rawType = stringField(rawNode, "type");
            AiWorkflowNodeType type = AiWorkflowNodeType.parse(rawType)
                    .orElseThrow(() ->
                            // 未知类型 = 类型不匹配在解析层的形状：整体拒绝
                            exception(AI_WORKFLOW_GRAPH_INVALID));
            String name = stringField(rawNode, "name");
            if (name != null && name.length() > MAX_NODE_NAME_LENGTH) {
                throw exception(AI_WORKFLOW_GRAPH_INVALID);
            }
            Map<String, Object> config = parseConfig(rawNode.get("config"));
            Node node = new Node(key, name == null ? type.name() : name, type, config);
            seen.put(key, node);
            nodes.add(node);
        }
        return nodes;
    }

    private static List<Edge> parseEdges(Object raw, List<Node> nodes) {
        if (!(raw instanceof List<?> items) || items.size() > MAX_EDGES) {
            throw exception(AI_WORKFLOW_GRAPH_INVALID);
        }
        Map<String, Node> byKey = new LinkedHashMap<>();
        nodes.forEach(node -> byKey.put(node.key(), node));
        List<Edge> edges = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> rawEdge)) {
                throw exception(AI_WORKFLOW_GRAPH_INVALID);
            }
            String from = stringField(rawEdge, "from");
            String to = stringField(rawEdge, "to");
            // 边必须连接**已声明**的节点：引用不存在的节点在解析层即拒绝
            if (from == null || to == null || !byKey.containsKey(from) || !byKey.containsKey(to)) {
                throw exception(AI_WORKFLOW_GRAPH_INVALID);
            }
            String branch = stringField(rawEdge, "branch");
            if (branch != null) {
                branch = branch.trim().toUpperCase(Locale.ROOT);
                if (!BRANCH_TRUE.equals(branch) && !BRANCH_FALSE.equals(branch)) {
                    throw exception(AI_WORKFLOW_GRAPH_INVALID);
                }
            }
            edges.add(new Edge(from, to, branch));
        }
        return edges;
    }

    /** 节点配置必须是 JSON 对象且长度有界（具体字段由发布期按节点类型校验）。 */
    private static Map<String, Object> parseConfig(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw exception(AI_WORKFLOW_GRAPH_INVALID);
        }
        String configJson = JsonUtils.toJsonString(map);
        if (configJson.length() > MAX_NODE_CONFIG_LENGTH) {
            throw exception(AI_WORKFLOW_GRAPH_INVALID);
        }
        Map<String, Object> config = new LinkedHashMap<>();
        map.forEach((key, value) -> config.put(String.valueOf(key), value));
        return config;
    }

    private static String stringField(Map<?, ?> raw, String name) {
        Object value = raw.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            throw exception(AI_WORKFLOW_GRAPH_INVALID);
        }
        return text.trim();
    }

    /** 节点（图内键唯一）。 */
    public record Node(String key, String name, AiWorkflowNodeType type, Map<String, Object> config) {

        public Node {
            config = config == null ? Map.of() : Map.copyOf(config);
        }
    }

    /** 有向边（条件节点的出边必须带 TRUE/FALSE 分支；其余节点出边不带分支）。 */
    public record Edge(String from, String to, String branch) {}

    /** 规范化字符串的 SHA-256 摘要（图哈希与运行幂等摘要共用同一口径）。 */
    public static String sha256Hex(String value) {
        try {
            byte[] hashed = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hashed);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    /** 图内容摘要（发布时冻结的 graph_hash）：只对去空白后的图 JSON 取值。 */
    public static String graphHash(String graphJson) {
        return sha256Hex(graphJson == null ? "" : graphJson.trim());
    }

    /** 运行受理幂等摘要：固定（流程 + 数据分级 + 输入 + 预算）的规范化拼接。 */
    public static String runDigest(
            Long workflowId, String dataLevel, String inputText, int maxSteps, long maxDurationMillis) {
        String canonical = workflowId + "|" + dataLevel + "|" + (inputText == null ? "" : inputText) + "|" + maxSteps
                + "|" + maxDurationMillis;
        return sha256Hex(canonical);
    }
}
