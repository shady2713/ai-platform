package com.basicframework.module.ai.service.knowledge.rag.dto;

import com.basicframework.module.ai.service.knowledge.retrieval.dto.AiKnowledgeCitationDTO;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * RAG 上下文证据（K08）：进入模型的片段 + 它们对应的引用 + 是否证据不足。
 *
 * <p>为什么把"片段"和"引用"放在一起返回：模型看到的正文与用户可点击的引用必须**同源**
 * （都来自本次检索候选）。分开传会出现"模型引用了没给用户看过的片段"或反之。
 *
 * <p>{@code untrusted=true} 是结构性标记：文档正文是**不可信数据**，
 * 其中的"忽略权限""调用某工具"等指令不构成系统操作（K08 的边界测试）。
 */
@Data
@Accessors(chain = true)
public class AiKnowledgeRagContextDTO {

    /** 进入模型的片段（序号即引用编号，从 1 开始） */
    private List<Snippet> snippets = List.of();

    /** 对应的引用（与片段一一对应，可回读原文） */
    private List<AiKnowledgeCitationDTO> citations = List.of();

    /** 是否证据不足（调用方据此明确说明"资料不足"，不能编造） */
    private boolean insufficientEvidence;

    /** 文档正文是不可信数据（固定为 true，供提示词与审计使用） */
    private boolean untrusted = true;

    /** 片段：编号 + 标题 + 位置 + 正文。 */
    @Data
    @Accessors(chain = true)
    public static class Snippet {

        /** 引用编号（从 1 开始，与模型回答里的 [n] 对应） */
        private int index;

        /** 文档标题 */
        private String title;

        /** 来源位置（第 N 页 / 段落 N） */
        private String locationRef;

        /** 片段正文（不可信数据） */
        private String text;
    }
}
