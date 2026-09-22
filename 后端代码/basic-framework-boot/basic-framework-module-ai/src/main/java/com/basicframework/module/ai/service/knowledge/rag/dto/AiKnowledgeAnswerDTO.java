package com.basicframework.module.ai.service.knowledge.rag.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 模型回答的引用校验结论（K08）：把"回答"与"引用"分开校验。
 *
 * <p>校验规则：
 * <ul>
 *   <li>回答里出现的 {@code [n]} 只有在本次检索候选范围内才保留（其余被丢弃并计数）；</li>
 *   <li>没有有效引用且证据不足时，结论是"证据不足"，调用方必须如实说明而不是照抄模型措辞；</li>
 *   <li>校验不修改回答正文（只报告有效/无效引用），避免"改写模型输出"带来的失真。</li>
 * </ul>
 */
@Data
@Accessors(chain = true)
public class AiKnowledgeAnswerDTO {

    /** 模型回答原文（不做改写） */
    private String answer;

    /** 有效引用编号（升序，去重） */
    private List<Integer> validCitations = List.of();

    /** 无效引用编号（模型编造或超出候选范围） */
    private List<Integer> invalidCitations = List.of();

    /** 是否证据不足（无检索候选，或回答没有有效引用） */
    private boolean insufficientEvidence;

    /** 是否需要向用户说明"资料不足" */
    public boolean requiresDisclaimer() {
        return insufficientEvidence;
    }
}
