package com.basicframework.module.ai.service.knowledge.retrieval;

import com.basicframework.module.ai.service.knowledge.retrieval.dto.AiKnowledgeRetrievalResultDTO;

/**
 * 授权检索与引用读取（K06）。
 *
 * <p>五条不变量（对应卡片逐步实施）：
 * <ol>
 *   <li><b>过滤前置且由服务端生成</b>：向量检索的过滤条件只由**当前主体的 A03 授权**推导
 *       （知识库标识集合），调用方给的问题文本与参数不参与过滤条件构造——恶意取值改不了授权；</li>
 *   <li><b>候选复核</b>：向量服务返回的候选必须再经服务端复核（知识库启用、版本 READY、
 *       且是该文档当前的 active 版本），删除或换代后的索引残留因此不可见（AT-028）；</li>
 *   <li><b>引用来自候选</b>：引用只从本次检索候选中映射（文档/版本/切片/位置），模型无法自报来源（AT-026）；</li>
 *   <li><b>读取再鉴权</b>：读片段或原文都重新走 A03 判定 + A07 的业务文件权限 SPI，
 *       授权回收后立即读不到；</li>
 *   <li><b>没有证据就说没有</b>：授权范围内无可检索知识库或复核后无候选时，返回空结果并明确标注，
 *       不返回任何"看起来像答案"的内容。</li>
 * </ol>
 */
public interface AiKnowledgeRetrievalService {

    /** 按当前主体检索（topK 有上限；问题文本只用于向量化，不参与过滤条件构造）。 */
    AiKnowledgeRetrievalResultDTO search(String query, Integer topK);

    /**
     * 读取引用片段：重新鉴权后按引用位置从**原文**取回该片段正文。
     *
     * <p>为什么回到原文而不是读索引载荷：片段读取是"引用可核验"的一环，
     * 原文读取走同一业务文件权限 SPI（A07），授权回收后立即失败。
     */
    String readCitationSnippet(String citationId);

    /** 读取原文（整份文件内容）：重新鉴权后经 A07 读取。 */
    byte[] readOriginal(Long documentId);

    /** 解析引用标识（不存在或非法返回 null）。 */
    static CitationKey parseCitationId(String citationId) {
        if (citationId == null) {
            return null;
        }
        String[] parts = citationId.split(":");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new CitationKey(Long.parseLong(parts[0]), Long.parseLong(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /** 引用标识的组成：知识库编号、文档版本编号、切片序号。 */
    record CitationKey(Long knowledgeBaseId, Long documentVersionId, Integer chunkIndex) {}
}
