package com.basicframework.module.ai.service.knowledge.rag;

import com.basicframework.module.ai.service.knowledge.rag.dto.AiKnowledgeAnswerDTO;
import com.basicframework.module.ai.service.knowledge.rag.dto.AiKnowledgeRagContextDTO;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识问答运行步骤（K08）：检索 → 形成上下文证据 → 校验模型回答的引用。
 *
 * <p>三步的安全语义：
 * <ol>
 *   <li><b>证据来自授权检索</b>：片段与引用都来自 K06 的本次检索候选（同一授权范围），
 *       文档正文只作为**不可信数据**进入上下文；</li>
 *   <li><b>回答与引用分离校验</b>：回答正文不改写，只校验其中的 {@code [n]} 是否落在候选范围内；
 *       编造的引用被丢弃并计数，证据不足时结论是"资料不足"（不给伪引用，AT-026）；</li>
 *   <li><b>文档指令不改变权限</b>：文档里的"忽略权限/调用某工具"只是文本——
 *       工具权限由工具注册表与政策门（D08）决定，本步骤不读取也不解释文档里的指令
 *       （边界测试用注入样本证明工具判定不变）。</li>
 * </ol>
 */
public class AiKnowledgeRagStep {

    /** 引用标记：[1]、[2]……（只接受 1-99 的十进制编号）。 */
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[(\\d{1,2})]");

    /** 单次进入模型的片段数上限（与 K06 的 topK 对齐）。 */
    public static final int MAX_SNIPPETS = 20;

    /**
     * 把检索结果变成上下文证据：片段编号从 1 开始，与引用一一对应。
     * 检索为空（或全部候选被复核丢弃）时返回"证据不足"，**不生成任何片段**。
     */
    public AiKnowledgeRagContextDTO buildContext(
            List<com.basicframework.module.ai.service.knowledge.retrieval.dto.AiKnowledgeCitationDTO> citations) {
        List<com.basicframework.module.ai.service.knowledge.retrieval.dto.AiKnowledgeCitationDTO> usable =
                citations == null ? List.of() : citations;
        if (usable.isEmpty()) {
            return new AiKnowledgeRagContextDTO().setInsufficientEvidence(true);
        }
        List<com.basicframework.module.ai.service.knowledge.retrieval.dto.AiKnowledgeCitationDTO> limited =
                usable.size() > MAX_SNIPPETS ? usable.subList(0, MAX_SNIPPETS) : usable;
        List<AiKnowledgeRagContextDTO.Snippet> snippets = new ArrayList<>();
        for (int index = 0; index < limited.size(); index++) {
            var citation = limited.get(index);
            snippets.add(new AiKnowledgeRagContextDTO.Snippet()
                    .setIndex(index + 1)
                    .setTitle(citation.getTitle())
                    .setLocationRef(citation.getLocationRef())
                    .setText(citation.getSnippet()));
        }
        return new AiKnowledgeRagContextDTO()
                .setSnippets(List.copyOf(snippets))
                .setCitations(List.copyOf(limited))
                .setInsufficientEvidence(false)
                .setUntrusted(true);
    }

    /**
     * 校验模型回答里的引用：只保留落在候选范围内的编号；证据不足或没有有效引用时标记"资料不足"。
     *
     * <p>回答正文保持原样（不改写、不删句），调用方据 {@code requiresDisclaimer()} 决定是否加免责说明。
     */
    public AiKnowledgeAnswerDTO validateAnswer(String answer, AiKnowledgeRagContextDTO context) {
        String text = answer == null ? "" : answer;
        Set<Integer> available = new LinkedHashSet<>();
        if (context != null && context.getSnippets() != null) {
            context.getSnippets().forEach(snippet -> available.add(snippet.getIndex()));
        }
        Set<Integer> valid = new LinkedHashSet<>();
        Set<Integer> invalid = new LinkedHashSet<>();
        Matcher matcher = CITATION_MARKER.matcher(text);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (available.contains(index)) {
                valid.add(index);
            } else {
                invalid.add(index);
            }
        }
        boolean insufficient = (context == null || context.isInsufficientEvidence()) || valid.isEmpty();
        return new AiKnowledgeAnswerDTO()
                .setAnswer(text)
                .setValidCitations(List.copyOf(valid))
                .setInvalidCitations(List.copyOf(invalid))
                .setInsufficientEvidence(insufficient);
    }
}
