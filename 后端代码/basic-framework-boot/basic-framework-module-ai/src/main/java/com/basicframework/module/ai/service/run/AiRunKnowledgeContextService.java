package com.basicframework.module.ai.service.run;

import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceResourceMapper;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.knowledge.rag.AiKnowledgeRagStep;
import com.basicframework.module.ai.service.knowledge.rag.dto.AiKnowledgeAnswerDTO;
import com.basicframework.module.ai.service.knowledge.rag.dto.AiKnowledgeRagContextDTO;
import com.basicframework.module.ai.service.knowledge.retrieval.AiKnowledgeRetrievalService;
import com.basicframework.module.ai.service.knowledge.retrieval.dto.AiKnowledgeCitationDTO;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 运行链路的知识问答装配（K08）：把"服务发布版本绑定的授权知识库"变成运行上下文。
 *
 * <p>边界：
 * <ul>
 *   <li>知识库范围来自**发布版本**的资源绑定（`ai_service_resource`，资源类型 KNOWLEDGE_BASE），
 *       不是运行时的自由参数——运行不能自己扩大检索范围；</li>
 *   <li>检索仍走 K06（授权目录 + 候选复核），绑定只是"这次运行要用哪些库"的声明；</li>
 *   <li>上下文与引用同源（{@link AiKnowledgeRagStep}），文档正文标记为不可信数据。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiRunKnowledgeContextService {

    private final AiServiceResourceMapper serviceResourceMapper;

    private final AiKnowledgeRetrievalService retrievalService;

    private final AiKnowledgeRagStep ragStep;

    /** 发布版本绑定的知识库标识（去重、保持声明顺序）。 */
    public List<String> boundKnowledgeBases(Long releaseId) {
        if (releaseId == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (AiServiceResourceDO binding : serviceResourceMapper.selectActiveReleaseBindings(releaseId)) {
            if (AiResourceType.KNOWLEDGE_BASE.name().equals(binding.getResourceType())
                    && !keys.contains(binding.getResourceKey())) {
                keys.add(binding.getResourceKey());
            }
        }
        return keys;
    }

    /** 为一次运行装配知识上下文：检索（K06）→ 证据（K08）。 */
    public AiKnowledgeRagContextDTO assemble(Long releaseId, String question, Integer topK) {
        List<String> bound = boundKnowledgeBases(releaseId);
        if (bound.isEmpty()) {
            // 服务没有绑定知识库：明确"证据不足"，不检索也不编造
            return ragStep.buildContext(List.of());
        }
        List<AiKnowledgeCitationDTO> citations =
                retrievalService.search(question, topK).getCitations();
        AiKnowledgeRagContextDTO context = ragStep.buildContext(citations);
        log.debug(
                "[assemble][releaseId={}, boundKnowledgeBases={}, snippets={}, insufficient={}]",
                releaseId,
                bound.size(),
                context.getSnippets().size(),
                context.isInsufficientEvidence());
        return context;
    }

    /** 校验模型回答的引用（与装配共用同一上下文）。 */
    public AiKnowledgeAnswerDTO validateAnswer(String answer, AiKnowledgeRagContextDTO context) {
        return ragStep.validateAnswer(answer, context);
    }
}
