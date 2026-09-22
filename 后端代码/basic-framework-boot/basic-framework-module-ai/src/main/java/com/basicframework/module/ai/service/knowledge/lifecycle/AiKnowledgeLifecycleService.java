package com.basicframework.module.ai.service.knowledge.lifecycle;

import com.basicframework.module.ai.service.knowledge.lifecycle.dto.AiKnowledgeCleanupReportDTO;
import com.basicframework.module.ai.service.knowledge.lifecycle.dto.AiKnowledgeSyncResultDTO;

/**
 * 文档同步、撤销与清理（K07）。
 *
 * <p>三条不变量（对应卡片逐步实施）：
 * <ol>
 *   <li><b>同步按 sourceKey 收敛</b>：外部来源用 sourceKey 幂等更新（复用/新版本由指纹决定），
 *       来源标识（sourceType/sourceRef）随版本记录，便于追溯"这条内容从哪来"；</li>
 *   <li><b>先撤可见性，再清理</b>：删除是两段式——先把文档置 DELETING（检索立即不可见，K06 的候选复核
 *       要求 READY + active 版本），再回收切片、向量与文件引用，最后软删除行。
 *       顺序反过来会出现"内容已删但引用仍可见"的窗口；</li>
 *   <li><b>清理分步幂等、可断点续跑</b>：每一步都按当前状态判断（切片按版本删、向量按版本过滤删、
 *       文件引用按版本释放、行按编号软删），进程中断后重跑从当前状态继续；</li>
 *   <li><b>共享文件不误删</b>：文件引用通过 A07 的释放语义处理——只有最后一个引用释放时才真正删文件，
 *       本卡只调用释放、不直接删文件。</li>
 * </ol>
 */
public interface AiKnowledgeLifecycleService {

    /** 按 sourceKey 同步（复用既有文档或生成新版本），并记录来源与入库任务。 */
    AiKnowledgeSyncResultDTO sync(
            Long knowledgeBaseId, String sourceKey, String title, String sourceRef, Long fileId, String contentHash);

    /** 撤销可见性（置 DELETING）：检索立即不可见，随后由清理步骤回收资源。 */
    void revoke(Long documentId);

    /** 清理单个文档：切片 → 向量 → 文件引用 → 软删除行（分步幂等，可重复调用）。 */
    AiKnowledgeCleanupReportDTO cleanup(Long documentId);

    /** 批量推进未完成的清理（供 Job 调用；返回本次报告）。 */
    AiKnowledgeCleanupReportDTO processPendingCleanups(int limit);

    /** 重建索引代（把当前生效的文档版本重新索引到新一代；旧代保留作为回退窗）。 */
    int rebuildGeneration(Long knowledgeBaseId);

    /** 孤儿检查与回收：切片/版本指向已删除对象、向量残留等；返回报告（含孤儿描述）。 */
    AiKnowledgeCleanupReportDTO auditOrphans(Long knowledgeBaseId, boolean cleanup);
}
