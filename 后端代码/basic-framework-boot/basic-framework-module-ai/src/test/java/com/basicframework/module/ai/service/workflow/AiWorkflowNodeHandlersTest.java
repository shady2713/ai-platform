package com.basicframework.module.ai.service.workflow;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_NODE_TYPE_MISMATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.domain.policy.AiOutboundLevel;
import com.basicframework.module.ai.domain.workflow.AiWorkflowGraph;
import com.basicframework.module.ai.domain.workflow.AiWorkflowNodeType;
import com.basicframework.module.ai.service.knowledge.retrieval.AiKnowledgeRetrievalService;
import com.basicframework.module.ai.service.knowledge.retrieval.dto.AiKnowledgeCitationDTO;
import com.basicframework.module.ai.service.knowledge.retrieval.dto.AiKnowledgeRetrievalResultDTO;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.AiModelInvocationResult;
import com.basicframework.module.ai.service.model.AiModelInvocationService;
import com.basicframework.module.ai.service.run.AiRunQueryExecutionService;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionFixedRequestDTO;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionResultDTO;
import com.basicframework.module.ai.service.tool.AiToolDecision;
import com.basicframework.module.ai.service.tool.AiToolExecutor;
import com.basicframework.module.ai.service.tool.AiToolPolicyGate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 节点处理器（X08）：每个"做实事"的节点都只经既有受控入口，稳定错误码原样向上传播。
 */
@ExtendWith(MockitoExtension.class)
class AiWorkflowNodeHandlersTest {

    private static final AiWorkflowGraph.Node MODEL_NODE = new AiWorkflowGraph.Node(
            "ask",
            "模型",
            com.basicframework.module.ai.domain.workflow.AiWorkflowNodeType.MODEL,
            Map.of("endpointId", 1, "promptTemplate", "总结 {input}"));

    private static final AiWorkflowGraph.Node TOOL_NODE = new AiWorkflowGraph.Node(
            "call",
            "工具",
            com.basicframework.module.ai.domain.workflow.AiWorkflowNodeType.TOOL,
            Map.of("toolCode", "crm_query_order", "arguments", Map.of("orderId", "A-1")));

    private static final AiWorkflowNodeContext CONTEXT =
            new AiWorkflowNodeContext(AiOutboundLevel.L2_INTERNAL, Duration.ofSeconds(30));

    @Mock
    private AiModelInvocationService invocationService;

    @Mock
    private AiModelEndpointService endpointService;

    @Mock
    private AiKnowledgeRetrievalService retrievalService;

    @Mock
    private AiRunQueryExecutionService queryExecutionService;

    @Mock
    private AiToolPolicyGate policyGate;

    @Mock
    private AiToolExecutor toolExecutor;

    @Test
    void modelNodeGoesThroughControlledInvocationWithReplacedInput() {
        when(endpointService.getRevisions(1L))
                .thenReturn(List.of(new AiModelEndpointRevisionDO().setModelId("qwen-max")));
        when(invocationService.generate(eq(1L), any(ModelRequest.class), eq(AiOutboundLevel.L2_INTERNAL)))
                .thenReturn(new AiModelInvocationResult<>(
                        null, new ModelResponse("结论文本", ModelUsage.UNKNOWN, List.of(), "qwen-max", null)));
        AiWorkflowModelNodeHandler handler = new AiWorkflowModelNodeHandler(invocationService, endpointService);

        String output = handler.execute(MODEL_NODE, "订单输入", CONTEXT);

        assertThat(output).isEqualTo("结论文本");
        ArgumentCaptor<ModelRequest> request = ArgumentCaptor.forClass(ModelRequest.class);
        verify(invocationService).generate(eq(1L), request.capture(), eq(AiOutboundLevel.L2_INTERNAL));
        assertThat(request.getValue().prompt()).isEqualTo("总结 订单输入");
        assertThat(request.getValue().timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void modelNodeMapsUpstreamFailureToStableCode() {
        when(endpointService.getRevisions(1L))
                .thenReturn(List.of(new AiModelEndpointRevisionDO().setModelId("qwen-max")));
        when(invocationService.generate(any(), any(), any()))
                .thenThrow(new ModelException(ModelException.Reason.CAPABILITY_UNSUPPORTED, "boom with secret"));
        AiWorkflowModelNodeHandler handler = new AiWorkflowModelNodeHandler(invocationService, endpointService);

        assertThatThrownBy(() -> handler.execute(MODEL_NODE, "输入", CONTEXT)).isInstanceOf(ServiceException.class);
    }

    @Test
    void toolNodeWithoutConfirmationNeverExecutes() {
        // CONFIRM 政策在闸门被拒：流程节点不能为写调用开第二通道
        when(policyGate.decide(eq("crm_query_order"), any()))
                .thenThrow(new ServiceException(
                        com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_CONFIRMATION_REQUIRED));
        AiWorkflowToolNodeHandler handler = new AiWorkflowToolNodeHandler(policyGate, toolExecutor);

        assertThatThrownBy(() -> handler.execute(TOOL_NODE, "输入", CONTEXT))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(
                                com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_CONFIRMATION_REQUIRED
                                        .getCode()));
        verify(toolExecutor, org.mockito.Mockito.never()).execute(any());
    }

    @Test
    void toolNodeExecutesOnlyAutoPolicyDecisions() {
        when(policyGate.decide(eq("crm_query_order"), any()))
                .thenReturn(new AiToolDecision(
                        AiToolDecision.Outcome.EXECUTE,
                        new AiToolVersionDO(),
                        5L,
                        "listOrders",
                        Map.of("orderId", "A-1")));
        when(toolExecutor.execute(any()))
                .thenReturn(new AiConnectorExecutionResultDTO()
                        .setStatus("SUCCEEDED")
                        .setItemCount(3));
        AiWorkflowToolNodeHandler handler = new AiWorkflowToolNodeHandler(policyGate, toolExecutor);

        String output = handler.execute(TOOL_NODE, "输入", CONTEXT);

        assertThat(output).isEqualTo("status=SUCCEEDED items=3");
        verify(toolExecutor).execute(any());
    }

    @Test
    void toolNodeRejectsMissingToolCodeAtExecution() {
        AiWorkflowGraph.Node noToolCode = new AiWorkflowGraph.Node(
                "t", "工具", AiWorkflowNodeType.TOOL, java.util.Map.of("arguments", java.util.Map.of("x", 1)));
        AiWorkflowToolNodeHandler handler = new AiWorkflowToolNodeHandler(policyGate, toolExecutor);
        assertThatThrownBy(() -> handler.execute(noToolCode, "输入", CONTEXT))
                .as("工具节点运行期无 toolCode 拒绝（与发布校验同语义）")
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
    }

    @Test
    void toolNodeRejectsNonMapArgumentsAtExecution() {
        AiWorkflowGraph.Node listArgs = new AiWorkflowGraph.Node(
                "t",
                "工具",
                AiWorkflowNodeType.TOOL,
                java.util.Map.of("toolCode", "crm_query_order", "arguments", java.util.List.of("oops")));
        AiWorkflowToolNodeHandler handler = new AiWorkflowToolNodeHandler(policyGate, toolExecutor);
        assertThatThrownBy(() -> handler.execute(listArgs, "输入", CONTEXT))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
    }

    @Test
    void toolNodeRejectsMissingToolCodeOrNonMapArgumentsAtExecution() {
        // 无 toolCode：运行期防御（与发布校验同稳定码）
        AiWorkflowGraph.Node noToolCode = new AiWorkflowGraph.Node(
                "t", "工具", AiWorkflowNodeType.TOOL, java.util.Map.of("arguments", java.util.Map.of("x", 1)));
        AiWorkflowToolNodeHandler handler = new AiWorkflowToolNodeHandler(policyGate, toolExecutor);
        assertThatThrownBy(() -> handler.execute(noToolCode, "输入", CONTEXT))
                .as("工具节点运行期无 toolCode 拒绝")
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
        // arguments 不是 Map：运行期防御
        AiWorkflowGraph.Node listArgs = new AiWorkflowGraph.Node(
                "t",
                "工具",
                AiWorkflowNodeType.TOOL,
                java.util.Map.of("toolCode", "crm_query_order", "arguments", java.util.List.of("oops")));
        assertThatThrownBy(() -> handler.execute(listArgs, "输入", CONTEXT))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
    }

    @Test
    void knowledgeNodeDelegatesToAuthorizedRetrievalOnly() {
        AiWorkflowGraph.Node node = new AiWorkflowGraph.Node(
                "kb",
                "检索",
                com.basicframework.module.ai.domain.workflow.AiWorkflowNodeType.KNOWLEDGE_RETRIEVAL,
                Map.of("query", "关于 {input} 的资料", "topK", 3));
        when(retrievalService.search("关于 订单输入 的资料", 3))
                .thenReturn(new AiKnowledgeRetrievalResultDTO()
                        .setCitations(List.of(new AiKnowledgeCitationDTO().setCitationId("1:2:3"))));
        AiWorkflowKnowledgeNodeHandler handler = new AiWorkflowKnowledgeNodeHandler(retrievalService);

        String output = handler.execute(node, "订单输入", CONTEXT);

        assertThat(output).isEqualTo("citations=1 first=1:2:3");
    }

    @Test
    void knowledgeNodeWithoutEvidenceReportsItExplicitly() {
        AiWorkflowGraph.Node node = new AiWorkflowGraph.Node(
                "kb",
                "检索",
                com.basicframework.module.ai.domain.workflow.AiWorkflowNodeType.KNOWLEDGE_RETRIEVAL,
                Map.of("query", "问题"));
        when(retrievalService.search(eq("问题"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(
                        new AiKnowledgeRetrievalResultDTO().setCandidateCount(7).setFilteredOutCount(2));
        AiWorkflowKnowledgeNodeHandler handler = new AiWorkflowKnowledgeNodeHandler(retrievalService);

        assertThat(handler.execute(node, "输入", CONTEXT)).isEqualTo("citations=0 candidates=7 filteredOut=2");
    }

    @Test
    void dataQueryNodeFreezesNonEmptyRowScopeIntoControlledExecution() {
        AiWorkflowGraph.Node node = new AiWorkflowGraph.Node(
                "q",
                "查询",
                com.basicframework.module.ai.domain.workflow.AiWorkflowNodeType.DATA_QUERY,
                Map.of(
                        "datasetId",
                        1,
                        "datasetVersionId",
                        2,
                        "planJson",
                        "{\"planHash\":\"abc\"}",
                        "rowScope",
                        List.of(Map.of("sourceColumn", "dept_id", "operator", "IN", "values", List.of(3)))));
        when(queryExecutionService.executeFixed(any()))
                .thenReturn(new AiRunQueryExecutionResultDTO()
                        .setKind(AiRunQueryExecutionResultDTO.KIND_PLAN)
                        .setRowCount(12)
                        .setCompleteness(AiRunQueryExecutionResultDTO.COMPLETE));
        AiWorkflowDataQueryNodeHandler handler = new AiWorkflowDataQueryNodeHandler(queryExecutionService);

        String output = handler.execute(node, "输入", CONTEXT);

        assertThat(output).isEqualTo("kind=PLAN rows=12 completeness=COMPLETE");
        ArgumentCaptor<AiRunQueryExecutionFixedRequestDTO> request =
                ArgumentCaptor.forClass(AiRunQueryExecutionFixedRequestDTO.class);
        verify(queryExecutionService).executeFixed(request.capture());
        assertThat(request.getValue().getRowScope().isEffective()).isTrue();
        assertThat(request.getValue().getDatasetId()).isEqualTo(1L);
    }

    @Test
    void dataQueryNodeSurfacesClarificationInsteadOfGuessing() {
        AiWorkflowGraph.Node node = new AiWorkflowGraph.Node(
                "q",
                "查询",
                com.basicframework.module.ai.domain.workflow.AiWorkflowNodeType.DATA_QUERY,
                Map.of(
                        "datasetId",
                        1,
                        "datasetVersionId",
                        2,
                        "planJson",
                        "{\"planHash\":\"abc\"}",
                        "rowScope",
                        List.of(Map.of("sourceColumn", "dept_id", "operator", "EQ", "values", List.of(3)))));
        when(queryExecutionService.executeFixed(any()))
                .thenReturn(new AiRunQueryExecutionResultDTO()
                        .setKind(AiRunQueryExecutionResultDTO.KIND_CLARIFICATION)
                        .setClarificationQuestion("统计哪个月？"));
        AiWorkflowDataQueryNodeHandler handler = new AiWorkflowDataQueryNodeHandler(queryExecutionService);

        assertThat(handler.execute(node, "输入", CONTEXT)).isEqualTo("clarification rows=0 question=统计哪个月？");
    }
}
