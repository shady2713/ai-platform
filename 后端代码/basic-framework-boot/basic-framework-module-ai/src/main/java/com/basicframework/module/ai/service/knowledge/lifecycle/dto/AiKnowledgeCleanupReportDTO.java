package com.basicframework.module.ai.service.knowledge.lifecycle.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 清理/孤儿检查报告（K07）：每一步做了什么、还剩什么。
 *
 * <p>报告是"可恢复"的证据：清理是**分步幂等**的（撤可见性 → 删切片 → 删向量 → 释放文件引用 → 软删除行），
 * 任何一步失败后重跑都从当前状态继续，报告里能看出进度（`pendingDocuments` 是还没清理完的文档数）。
 */
@Data
@Accessors(chain = true)
public class AiKnowledgeCleanupReportDTO {

    /** 本次处理的文档数 */
    private int processedDocuments;

    /** 本次删除的切片行数 */
    private int deletedChunks;

    /** 本次删除的向量点数 */
    private long deletedVectors;

    /** 本次释放的文件引用数 */
    private int releasedFiles;

    /** 仍未清理完的文档数（删除中） */
    private int pendingDocuments;

    /** 孤儿项（切片/向量/版本指向已删除对象）描述 */
    private List<String> orphans = List.of();

    /** 是否还有待处理项（调用方据此决定是否再跑一轮） */
    public boolean hasPending() {
        return pendingDocuments > 0 || !orphans.isEmpty();
    }
}
