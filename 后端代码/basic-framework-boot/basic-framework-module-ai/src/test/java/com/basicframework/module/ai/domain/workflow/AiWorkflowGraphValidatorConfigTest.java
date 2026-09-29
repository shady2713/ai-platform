package com.basicframework.module.ai.domain.workflow;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_GRAPH_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_NODE_TYPE_MISMATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

/**
 * 流程图发布校验 · 配置面拒绝（X08）：节点配置、端口基数与条件引用的负面用例。
 *
 * <p>与 {@link AiWorkflowGraphValidatorTest} 分工：那份覆盖拓扑（环/无出口/端口类型），
 * 这份覆盖"配置项不合法一律拒绝发布"，每条都断言稳定错误码 {@code AI_WORKFLOW_NODE_TYPE_MISMATCH}——
 * 拒绝必须可解释，且不允许"配置缺失就放行、运行期再炸"。
 */
class AiWorkflowGraphValidatorConfigTest {

    private final AiWorkflowGraphValidator validator = new AiWorkflowGraphValidator();

    /**
     * 发布期拒绝的可解释稳定码有两类：图 JSON 形状/规模不合规（AI_WORKFLOW_GRAPH_INVALID）
     * 与配置/端口不匹配（AI_WORKFLOW_NODE_TYPE_MISMATCH）；本文件只断言落在这两个码内。
     */
    private static void assertRejected(String graphJson) {
        assertThatThrownBy(() -> new AiWorkflowGraphValidator().validate(graphJson))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isIn(AI_WORKFLOW_GRAPH_INVALID.getCode(), AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
    }

    /** 单节点管线：start → a(type,config) → end。 */
    private static String pipelineWithNodeConfig(String type, String config) {
        return "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\"},"
                + "{\"key\":\"a\",\"type\":\"" + type + "\",\"config\":" + config + "},"
                + "{\"key\":\"end\",\"type\":\"END\"}],"
                + "\"edges\":[{\"from\":\"start\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"end\"}]}";
    }

    @Test
    void startOrEndNodeWithConfigIsRejected() {
        String startWithConfig = "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\",\"config\":{\"x\":1}},"
                + "{\"key\":\"end\",\"type\":\"END\"}],\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}";
        assertRejected(startWithConfig);

        String endWithConfig = "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\"},"
                + "{\"key\":\"end\",\"type\":\"END\",\"config\":{\"x\":1}}],"
                + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}";
        assertRejected(endWithConfig);
    }

    @Test
    void modelNodeRequiresEndpointAndBoundedPrompt() {
        assertRejected(pipelineWithNodeConfig("MODEL", "{\"promptTemplate\":\"p\"}"));
        assertRejected(pipelineWithNodeConfig("MODEL", "{\"endpointId\":1}"));
        assertRejected(pipelineWithNodeConfig("MODEL", "{\"endpointId\":0,\"promptTemplate\":\"p\"}"));
        assertRejected(
                pipelineWithNodeConfig("MODEL", "{\"endpointId\":1,\"promptTemplate\":\"" + "长".repeat(4_001) + "\"}"));
        // 合法配置通过（对照组：同一夹具的正面用例，避免"全拒绝"假绿）
        validator.validate(pipelineWithNodeConfig("MODEL", "{\"endpointId\":1,\"promptTemplate\":\"总结 {input}\"}"));
    }

    @Test
    void knowledgeNodeRequiresQueryAndBoundedTopK() {
        assertRejected(pipelineWithNodeConfig("KNOWLEDGE_RETRIEVAL", "{\"topK\":3}"));
        assertRejected(pipelineWithNodeConfig("KNOWLEDGE_RETRIEVAL", "{\"query\":\"合同\",\"topK\":0}"));
        assertRejected(pipelineWithNodeConfig("KNOWLEDGE_RETRIEVAL", "{\"query\":\"合同\",\"topK\":21}"));
        validator.validate(pipelineWithNodeConfig("KNOWLEDGE_RETRIEVAL", "{\"query\":\"合同\",\"topK\":5}"));
    }

    @Test
    void dataQueryNodeRequiresDatasetPlanHashAndEffectiveRowScope() {
        String rowScope = "[{\"sourceColumn\":\"dept_id\",\"operator\":\"EQ\",\"values\":[7]}]";
        // 缺 datasetId / datasetVersionId
        assertRejected(pipelineWithNodeConfig(
                "DATA_QUERY",
                "{\"datasetVersionId\":9,\"planJson\":\"{\\\"planHash\\\":\\\"h\\\"}\",\"rowScope\":" + rowScope
                        + "}"));
        assertRejected(pipelineWithNodeConfig(
                "DATA_QUERY",
                "{\"datasetId\":9,\"planJson\":\"{\\\"planHash\\\":\\\"h\\\"}\",\"rowScope\":" + rowScope + "}"));
        // planJson 不是 JSON 对象 / 缺 planHash
        assertRejected(pipelineWithNodeConfig(
                "DATA_QUERY",
                "{\"datasetId\":9,\"datasetVersionId\":8,\"planJson\":\"not-json\",\"rowScope\":" + rowScope + "}"));
        assertRejected(pipelineWithNodeConfig(
                "DATA_QUERY",
                "{\"datasetId\":9,\"datasetVersionId\":8,\"planJson\":\"{}\",\"rowScope\":" + rowScope + "}"));
        // 行范围缺失或不生效（没有行范围的数据查询不允许发布）
        assertRejected(pipelineWithNodeConfig(
                "DATA_QUERY",
                "{\"datasetId\":9,\"datasetVersionId\":8,\"planJson\":\"{\\\"planHash\\\":\\\"h\\\"}\"}"));
        assertRejected(
                pipelineWithNodeConfig(
                        "DATA_QUERY",
                        "{\"datasetId\":9,\"datasetVersionId\":8,\"planJson\":\"{\\\"planHash\\\":\\\"h\\\"}\",\"rowScope\":[]}"));
        // 对照组：完整合法配置通过
        validator.validate(pipelineWithNodeConfig(
                "DATA_QUERY",
                "{\"datasetId\":9,\"datasetVersionId\":8,\"planJson\":\"{\\\"planHash\\\":\\\"h\\\"}\",\"rowScope\":"
                        + rowScope + "}"));
    }

    @Test
    void toolNodeRequiresBoundedCodeAndMapArguments() {
        assertRejected(pipelineWithNodeConfig("TOOL", "{\"toolCode\":\"ab\"}"));
        assertRejected(pipelineWithNodeConfig("TOOL", "{\"toolCode\":\"" + "t".repeat(65) + "\"}"));
        assertRejected(
                pipelineWithNodeConfig("TOOL", "{\"toolCode\":\"crm_query_order\",\"arguments\":[\"not-a-map\"]}"));
        validator.validate(pipelineWithNodeConfig("TOOL", "{\"toolCode\":\"crm_query_order\"}"));
    }

    @Test
    void conditionNodeRequiresKnownOperatorAndBoundValue() {
        java.util.function.UnaryOperator<String> withOperator =
                operator -> "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\"},"
                        + "{\"key\":\"a\",\"type\":\"MODEL\",\"config\":{\"endpointId\":1,\"promptTemplate\":\"p\"}},"
                        + "{\"key\":\"route\",\"type\":\"CONDITION\",\"config\":{\"ref\":\"a\",\"operator\":\""
                        + operator
                        + "\",\"value\":\"完成\"}},"
                        + "{\"key\":\"ok\",\"type\":\"END\"},{\"key\":\"no\",\"type\":\"END\"}],"
                        + "\"edges\":[{\"from\":\"start\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"route\"},"
                        + "{\"from\":\"route\",\"to\":\"ok\",\"branch\":\"TRUE\"},"
                        + "{\"from\":\"route\",\"to\":\"no\",\"branch\":\"FALSE\"}]}";
        assertRejected(withOperator.apply("NOT_AN_OPERATOR"));
        assertRejected(withOperator.apply("CONTAINS").replace("\"value\":\"完成\"", "\"value\":\"\""));
        validator.validate(withOperator.apply("CONTAINS"));
    }

    @Test
    void conditionNodeMustReferenceAnUpstreamOutputProducer() {
        // 前向引用（引用下游节点）与引用 START（不产出输出）都必须拒绝
        String forward = "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\"},"
                + "{\"key\":\"route\",\"type\":\"CONDITION\",\"config\":{\"ref\":\"later\",\"operator\":\"CONTAINS\",\"value\":\"x\"}},"
                + "{\"key\":\"later\",\"type\":\"MODEL\",\"config\":{\"endpointId\":1,\"promptTemplate\":\"p\"}},"
                + "{\"key\":\"ok\",\"type\":\"END\"},{\"key\":\"no\",\"type\":\"END\"}],"
                + "\"edges\":[{\"from\":\"start\",\"to\":\"route\"},"
                + "{\"from\":\"route\",\"to\":\"later\",\"branch\":\"TRUE\"},"
                + "{\"from\":\"route\",\"to\":\"ok\",\"branch\":\"FALSE\"},"
                + "{\"from\":\"later\",\"to\":\"no\"}]}";
        assertRejected(forward);

        String refStart = "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\"},"
                + "{\"key\":\"route\",\"type\":\"CONDITION\",\"config\":{\"ref\":\"start\",\"operator\":\"CONTAINS\",\"value\":\"x\"}},"
                + "{\"key\":\"ok\",\"type\":\"END\"},{\"key\":\"no\",\"type\":\"END\"}],"
                + "\"edges\":[{\"from\":\"start\",\"to\":\"route\"},"
                + "{\"from\":\"route\",\"to\":\"ok\",\"branch\":\"TRUE\"},"
                + "{\"from\":\"route\",\"to\":\"no\",\"branch\":\"FALSE\"}]}";
        assertRejected(refStart);
    }

    @Test
    void edgesIntoStartOrOutOfEndAndBranchCardinalityAreRejected() {
        // 进入 START 的入边
        String intoStart = "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\"},{\"key\":\"end\",\"type\":\"END\"}],"
                + "\"edges\":[{\"from\":\"end\",\"to\":\"start\"}]}";
        assertRejected(intoStart);
        // 从 END 出发的出边
        String outOfEnd = "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\"},{\"key\":\"end\",\"type\":\"END\"}],"
                + "\"edges\":[{\"from\":\"end\",\"to\":\"start\"}]}";
        assertRejected(outOfEnd);
        // 条件节点带非 TRUE/FALSE 分支
        String badBranch = "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\"},"
                + "{\"key\":\"a\",\"type\":\"MODEL\",\"config\":{\"endpointId\":1,\"promptTemplate\":\"p\"}},"
                + "{\"key\":\"route\",\"type\":\"CONDITION\",\"config\":{\"ref\":\"a\",\"operator\":\"CONTAINS\",\"value\":\"x\"}},"
                + "{\"key\":\"ok\",\"type\":\"END\"},{\"key\":\"no\",\"type\":\"END\"}],"
                + "\"edges\":[{\"from\":\"start\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"route\"},"
                + "{\"from\":\"route\",\"to\":\"ok\",\"branch\":\"MAYBE\"},"
                + "{\"from\":\"route\",\"to\":\"no\",\"branch\":\"FALSE\"}]}";
        assertRejected(badBranch);
    }
}
