package com.basicframework.module.ai.service.workflow;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_NODE_TYPE_MISMATCH;

import com.basicframework.module.ai.domain.workflow.AiWorkflowGraph;
import com.basicframework.module.ai.domain.workflow.AiWorkflowNodeType;
import com.basicframework.module.ai.domain.workflow.AiWorkflowRowScope;
import com.basicframework.module.ai.service.run.AiRunQueryExecutionService;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionFixedRequestDTO;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionResultDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 数据查询节点处理器（X08）：只经 R05 的受控查询（计划重校验 + 行范围强制拼 WHERE）。
 *
 * <p>数据集、版本与冻结计划在发布期核对；行范围是发布期冻结的**非空**约束，
 * 运行期由 R05 的编译器强制 AND 进语句——流程运行不能查询"没有行约束的全库"，
 * 也不能扩大计划引用的数据集。
 */
@Component
@RequiredArgsConstructor
public class AiWorkflowDataQueryNodeHandler implements AiWorkflowNodeHandler {

    /** 运行侧受控查询（R05）。 */
    private final AiRunQueryExecutionService queryExecutionService;

    @Override
    public AiWorkflowNodeType type() {
        return AiWorkflowNodeType.DATA_QUERY;
    }

    @Override
    public String execute(AiWorkflowGraph.Node node, String input, AiWorkflowNodeContext context) {
        Long datasetId = positiveLong(node.config().get("datasetId"));
        Long datasetVersionId = positiveLong(node.config().get("datasetVersionId"));
        String planJson =
                node.config().get("planJson") instanceof String text && StringUtils.hasText(text) ? text : null;
        if (datasetId == null || datasetVersionId == null || planJson == null) {
            // 发布期已校验；运行期防御（图快照被绕过写库时在这里拒绝）
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        AiWorkflowRowScope scope = AiWorkflowRowScope.parse(node.config().get("rowScope"));
        AiRunQueryExecutionResultDTO result =
                queryExecutionService.executeFixed(new AiRunQueryExecutionFixedRequestDTO()
                        .setDatasetId(datasetId)
                        .setDatasetVersionId(datasetVersionId)
                        .setPlanJson(planJson)
                        .setRowScope(scope.toQueryScope()));
        if (AiRunQueryExecutionResultDTO.KIND_CLARIFICATION.equals(result.getKind())) {
            // 澄清是受控结论：原样带回追问，不替调用方选口径执行
            return "clarification rows=0 question=" + result.getClarificationQuestion();
        }
        return "kind=" + result.getKind() + " rows=" + result.getRowCount() + " completeness="
                + result.getCompleteness();
    }

    private static Long positiveLong(Object value) {
        if (value instanceof Integer integer) {
            return integer > 0 ? integer.longValue() : null;
        }
        if (value instanceof Long longValue) {
            return longValue > 0 ? longValue : null;
        }
        return null;
    }
}
