package com.basicframework.module.ai.domain.workflow;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_GRAPH_CYCLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_GRAPH_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_GRAPH_NO_EXIT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_NODE_TYPE_MISMATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

/**
 * 流程图发布校验（X08 验收 1）：环、无出口、类型不匹配、引用不存在的节点一律拒绝发布。
 *
 * <p>每个用例都断言稳定错误码——拒绝必须可解释，不能只"抛了异常"。
 */
class AiWorkflowGraphValidatorTest {

    private final AiWorkflowGraphValidator validator = new AiWorkflowGraphValidator();

    /** 最小合法管线：开始 → 结束。 */
    private static final String SIMPLE_PIPELINE =
            """
            {"nodes":[{"key":"start","type":"START","name":"开始"},
                      {"key":"end","type":"END","name":"结束"}],
             "edges":[{"from":"start","to":"end"}]}
            """;

    @Test
    void minimalPipelinePasses() {
        AiWorkflowGraph graph = validator.validate(SIMPLE_PIPELINE);
        assertThat(graph.nodeCount()).isEqualTo(2);
        assertThat(graph.edgeCount()).isEqualTo(1);
    }

    @Test
    void fullPipelineWithConditionPasses() {
        String graphJson =
                """
            {"nodes":[{"key":"start","type":"START"},
                      {"key":"ask","type":"MODEL","config":{"endpointId":1,"promptTemplate":"总结 {input}"}}],
             "edges":[]}
            """;
        // 上面没有 END：应被拒绝（先验证本用例的图不完整时确实失败）
        assertThatThrownBy(() -> validator.validate(graphJson))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
        String full =
                """
            {"nodes":[{"key":"start","type":"START"},
                      {"key":"ask","type":"MODEL","config":{"endpointId":1,"promptTemplate":"总结 {input}"}},
                      {"key":"route","type":"CONDITION","config":{"ref":"ask","operator":"CONTAINS","value":"完成"}},
                      {"key":"ok","type":"END"},
                      {"key":"retry","type":"TOOL","config":{"toolCode":"crm_query_order"}},
                      {"key":"fail","type":"END"}],
             "edges":[{"from":"start","to":"ask"},
                      {"from":"ask","to":"route"},
                      {"from":"route","to":"ok","branch":"TRUE"},
                      {"from":"route","to":"retry","branch":"FALSE"},
                      {"from":"retry","to":"fail"}]}
            """;
        AiWorkflowGraph graph = validator.validate(full);
        assertThat(graph.nodeCount()).isEqualTo(6);
    }

    @Test
    void cycleIsRejected() {
        String graphJson =
                """
            {"nodes":[{"key":"start","type":"START"},
                      {"key":"end","type":"END"},
                      {"key":"a","type":"MODEL","config":{"endpointId":1,"promptTemplate":"p"}},
                      {"key":"b","type":"TOOL","config":{"toolCode":"crm_query_order"}}],
             "edges":[{"from":"start","to":"end"},{"from":"a","to":"b"},{"from":"b","to":"a"}]}
            """;
        assertThatThrownBy(() -> validator.validate(graphJson))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_GRAPH_CYCLE.getCode()));
    }

    @Test
    void selfLoopIsRejectedAsCycle() {
        String graphJson =
                """
            {"nodes":[{"key":"start","type":"START"},
                      {"key":"end","type":"END"},
                      {"key":"a","type":"MODEL","config":{"endpointId":1,"promptTemplate":"p"}}],
             "edges":[{"from":"start","to":"end"},{"from":"a","to":"a"}]}
            """;
        assertThatThrownBy(() -> validator.validate(graphJson))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_GRAPH_CYCLE.getCode()));
    }

    @Test
    void nodeWithoutPathToEndIsRejected() {
        // 链条停在工具节点、从未到达结束：无出口
        String graphJson =
                """
            {"nodes":[{"key":"start","type":"START"},
                      {"key":"a","type":"MODEL","config":{"endpointId":1,"promptTemplate":"p"}},
                      {"key":"retry","type":"TOOL","config":{"toolCode":"crm_query_order"}},
                      {"key":"end","type":"END"}],
             "edges":[{"from":"start","to":"a"},{"from":"a","to":"retry"}]}
            """;
        assertThatThrownBy(() -> validator.validate(graphJson))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_GRAPH_NO_EXIT.getCode()));
    }

    @Test
    void unreachableNodeIsRejected() {
        // orphan 节点从开始不可达（也不通向结束）
        String graphJson =
                """
            {"nodes":[{"key":"start","type":"START"},
                      {"key":"end","type":"END"},
                      {"key":"orphan","type":"MODEL","config":{"endpointId":1,"promptTemplate":"p"}}],
             "edges":[{"from":"start","to":"end"}]}
            """;
        assertThatThrownBy(() -> validator.validate(graphJson))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_GRAPH_NO_EXIT.getCode()));
    }

    @Test
    void missingOrDuplicateStartIsRejected() {
        String noStart =
                """
            {"nodes":[{"key":"a","type":"MODEL","config":{"endpointId":1,"promptTemplate":"p"}},
                      {"key":"end","type":"END"}],
             "edges":[{"from":"a","to":"end"}]}
            """;
        assertThatThrownBy(() -> validator.validate(noStart))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
        String twoStarts =
                """
            {"nodes":[{"key":"start","type":"START"},{"key":"start2","type":"START"},
                      {"key":"end","type":"END"}],
             "edges":[{"from":"start","to":"end"},{"from":"start2","to":"end"}]}
            """;
        assertThatThrownBy(() -> validator.validate(twoStarts))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
    }

    @Test
    void startWithIncomingEdgeIsRejected() {
        String graphJson =
                """
            {"nodes":[{"key":"start","type":"START"},
                      {"key":"a","type":"MODEL","config":{"endpointId":1,"promptTemplate":"p"}},
                      {"key":"end","type":"END"}],
             "edges":[{"from":"start","to":"a"},{"from":"a","to":"end"},{"from":"a","to":"start"}]}
            """;
        assertThatThrownBy(() -> validator.validate(graphJson))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
    }

    @Test
    void endWithOutgoingEdgeIsRejected() {
        String graphJson =
                """
            {"nodes":[{"key":"start","type":"START"},
                      {"key":"a","type":"MODEL","config":{"endpointId":1,"promptTemplate":"p"}},
                      {"key":"end","type":"END"}],
             "edges":[{"from":"start","to":"a"},{"from":"a","to":"end"},{"from":"end","to":"a"}]}
            """;
        assertThatThrownBy(() -> validator.validate(graphJson))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
    }

    @Test
    void conditionWithoutBothBranchesIsRejected() {
        String graphJson =
                """
            {"nodes":[{"key":"start","type":"START"},
                      {"key":"ask","type":"MODEL","config":{"endpointId":1,"promptTemplate":"p"}},
                      {"key":"route","type":"CONDITION","config":{"ref":"ask","operator":"CONTAINS","value":"x"}},
                      {"key":"end","type":"END"}],
             "edges":[{"from":"start","to":"ask"},{"from":"ask","to":"route"},{"from":"route","to":"end","branch":"TRUE"}]}
            """;
        assertThatThrownBy(() -> validator.validate(graphJson))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
    }

    @Test
    void plainNodeWithBranchLabelOrTwoOutgoingIsRejected() {
        String withBranch =
                """
            {"nodes":[{"key":"start","type":"START"},{"key":"end","type":"END"}],
             "edges":[{"from":"start","to":"end","branch":"TRUE"}]}
            """;
        assertThatThrownBy(() -> validator.validate(withBranch))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
        String twoOutgoing =
                """
            {"nodes":[{"key":"start","type":"START"},
                      {"key":"a","type":"MODEL","config":{"endpointId":1,"promptTemplate":"p"}},
                      {"key":"end","type":"END"}],
             "edges":[{"from":"start","to":"a"},{"from":"a","to":"end"},{"from":"a","to":"start"}]}
            """;
        assertThatThrownBy(() -> validator.validate(twoOutgoing))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
    }

    @Test
    void conditionReferencingForwardOrNonOutputNodeIsRejected() {
        // 前向引用：引用的是条件节点自己后面的节点（运行期拿不到输出）
        String forwardRef =
                """
            {"nodes":[{"key":"start","type":"START"},
                      {"key":"route","type":"CONDITION","config":{"ref":"ask","operator":"CONTAINS","value":"x"}},
                      {"key":"ask","type":"MODEL","config":{"endpointId":1,"promptTemplate":"p"}},
                      {"key":"end","type":"END"}],
             "edges":[{"from":"start","to":"route"},{"from":"route","to":"ask","branch":"TRUE"},
                      {"from":"route","to":"end","branch":"FALSE"},{"from":"ask","to":"end"}]}
            """;
        assertThatThrownBy(() -> validator.validate(forwardRef))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
        // 引用开始节点：开始不产出输出
        String refStart =
                """
            {"nodes":[{"key":"start","type":"START"},
                      {"key":"route","type":"CONDITION","config":{"ref":"start","operator":"CONTAINS","value":"x"}},
                      {"key":"end","type":"END"}],
             "edges":[{"from":"start","to":"route"},{"from":"route","to":"end","branch":"TRUE"},
                      {"from":"route","to":"end","branch":"FALSE"}]}
            """;
        assertThatThrownBy(() -> validator.validate(refStart))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
    }

    @Test
    void edgeToUnknownNodeAndUnknownTypeAreRejected() {
        String unknownNode =
                """
            {"nodes":[{"key":"start","type":"START"},{"key":"end","type":"END"}],
             "edges":[{"from":"start","to":"ghost"}]}
            """;
        assertThatThrownBy(() -> validator.validate(unknownNode))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_GRAPH_INVALID.getCode()));
        String unknownType =
                """
            {"nodes":[{"key":"start","type":"START"},{"key":"x","type":"SCRIPT"},{"key":"end","type":"END"}],
             "edges":[{"from":"start","to":"x"},{"from":"x","to":"end"}]}
            """;
        assertThatThrownBy(() -> validator.validate(unknownType))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_GRAPH_INVALID.getCode()));
    }

    @Test
    void nodeConfigShapeIsEnforced() {
        // 模型节点缺端点
        assertThatThrownBy(() -> validator.validate(pipelineWithNodeConfig("MODEL", "{\"promptTemplate\":\"p\"}")))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
        // 数据查询节点：行范围缺失/为空一律拒绝（空范围不是"不过滤"）
        assertThatThrownBy(() -> validator.validate(pipelineWithNodeConfig(
                        "DATA_QUERY",
                        "{\"datasetId\":1,\"datasetVersionId\":2,\"planJson\":\"{\\\"planHash\\\":\\\"abc\\\"}\","
                                + "\"rowScope\":[]}")))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
        // 工具节点缺 toolCode
        assertThatThrownBy(() -> validator.validate(pipelineWithNodeConfig("TOOL", "{}")))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
        // 条件节点操作符不受支持（无脚本、无表达式）
        assertThatThrownBy(() -> validator.validate(conditionPipeline("EXEC_CALC")))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
    }

    @Test
    void startNodeMustNotCarryConfig() {
        String graphJson =
                """
            {"nodes":[{"key":"start","type":"START","config":{"script":"x"}},
                      {"key":"end","type":"END"}],
             "edges":[{"from":"start","to":"end"}]}
            """;
        assertThatThrownBy(() -> validator.validate(graphJson))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
    }

    @Test
    void emptyRowScopeNeverPassesAsDataQueryConfig() {
        String graphJson =
                """
            {"nodes":[{"key":"start","type":"START"},
                      {"key":"q","type":"DATA_QUERY","config":{"datasetId":1,"datasetVersionId":2,
                        "planJson":"{\\"planHash\\":\\"abc\\"}","rowScope":[{"sourceColumn":"dept_id","operator":"IN","values":[]}]}},
                      {"key":"end","type":"END"}],
             "edges":[{"from":"start","to":"q"},{"from":"q","to":"end"}]}
            """;
        assertThatThrownBy(() -> validator.validate(graphJson))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
    }

    private static String pipelineWithNodeConfig(String type, String config) {
        return "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\"},"
                + "{\"key\":\"a\",\"type\":\"" + type + "\",\"config\":" + config + "},"
                + "{\"key\":\"end\",\"type\":\"END\"}],"
                + "\"edges\":[{\"from\":\"start\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"end\"}]}";
    }

    private static String conditionPipeline(String operatorExpression) {
        return "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\"},"
                + "{\"key\":\"a\",\"type\":\"MODEL\",\"config\":{\"endpointId\":1,\"promptTemplate\":\"p\"}},"
                + "{\"key\":\"route\",\"type\":\"CONDITION\",\"config\":{\"ref\":\"a\",\"operator\":\""
                + operatorExpression + "\",\"value\":\"x\"}},"
                + "{\"key\":\"end\",\"type\":\"END\"}],"
                + "\"edges\":[{\"from\":\"start\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"route\"},"
                + "{\"from\":\"route\",\"to\":\"end\",\"branch\":\"TRUE\"},"
                + "{\"from\":\"route\",\"to\":\"end\",\"branch\":\"FALSE\"}]}";
    }
}
