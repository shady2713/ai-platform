package com.basicframework.module.ai.service.knowledge.retrieval.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 检索结论（K06）：命中引用 + 被过滤掉的候选数 + 是否"没有可用证据"。
 *
 * <p>为什么记录"被过滤掉的候选数"：检索前置过滤（向量服务侧）与候选复核（服务端）是两道防线，
 * 两者各自挡掉多少必须可观测——否则"过滤生效"无法验证（本卡的验收证据之一）。
 */
@Data
@Accessors(chain = true)
public class AiKnowledgeRetrievalResultDTO {

    /** 命中引用（按相关度降序） */
    private List<AiKnowledgeCitationDTO> citations = List.of();

    /** 检索候选总数（向量服务返回） */
    private int candidateCount;

    /** 复核后被丢弃的候选数（状态/权限/版本不再有效） */
    private int filteredOutCount;

    /** 参与检索的知识库数（授权范围内、启用中、有生效索引代） */
    private int searchedKnowledgeBaseCount;

    /** 是否没有可用证据（调用方据此明确说明"资料不足"，不能编造） */
    public boolean noEvidence() {
        return citations == null || citations.isEmpty();
    }
}
