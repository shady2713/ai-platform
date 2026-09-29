package com.basicframework.module.ai.service.workflow;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_NODE_TYPE_MISMATCH;

import com.basicframework.module.ai.domain.workflow.AiWorkflowGraph;
import com.basicframework.module.ai.domain.workflow.AiWorkflowNodeType;
import com.basicframework.module.ai.service.knowledge.retrieval.AiKnowledgeRetrievalService;
import com.basicframework.module.ai.service.knowledge.retrieval.dto.AiKnowledgeRetrievalResultDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 知识检索节点处理器（X08）：只经 K06 的授权检索，不自建向量/索引路径。
 *
 * <p>K06 的授权范围来自**当前主体**的 A03 授权目录——检索范围是授权推导的，不是节点配置声明的。
 * 管理端受控运行没有应用主体会话，K06 会按 ACL 拒绝（fail-closed，节点以稳定错误码失败）：
 * 这正是"节点不能绕开数据 ACL"在知识检索上的表现；不存在绕过主体直接检索的通道。
 */
@Component
@RequiredArgsConstructor
public class AiWorkflowKnowledgeNodeHandler implements AiWorkflowNodeHandler {

    /** 默认返回条数（与 K06 默认一致）。 */
    private static final int DEFAULT_TOP_K = 5;

    /** 授权检索与引用读取（K06）。 */
    private final AiKnowledgeRetrievalService retrievalService;

    @Override
    public AiWorkflowNodeType type() {
        return AiWorkflowNodeType.KNOWLEDGE_RETRIEVAL;
    }

    @Override
    public String execute(AiWorkflowGraph.Node node, String input, AiWorkflowNodeContext context) {
        String template = node.config().get("query") instanceof String text && StringUtils.hasText(text) ? text : null;
        if (template == null) {
            // 发布期已校验；运行期防御（图快照被绕过写库时在这里拒绝）
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        Integer topK = node.config().get("topK") instanceof Integer requested ? requested : DEFAULT_TOP_K;
        String query = template.replace("{input}", input == null ? "" : input);
        AiKnowledgeRetrievalResultDTO result = retrievalService.search(query, topK);
        int citations =
                result.getCitations() == null ? 0 : result.getCitations().size();
        if (citations == 0) {
            // "没有证据就说没有"：引用数与过滤计数都进输出摘要，不编造内容
            return "citations=0 candidates=" + result.getCandidateCount() + " filteredOut="
                    + result.getFilteredOutCount();
        }
        return "citations=" + citations + " first="
                + result.getCitations().get(0).getCitationId();
    }
}
