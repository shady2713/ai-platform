package com.basicframework.module.ai.service.knowledge.ingestion;

import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionStepOutcome;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionTaskLeaseDTO;

/**
 * 入库步骤端口（K03 定义，K04/K05 实现）。
 *
 * <p>入库 Job 只负责"领取 → 校验文件引用 → 推进文档状态 → 调用步骤 → 写终态"，
 * 解析（K04）与切分/向量化（K05）通过本端口接入。没有实现时 Job **不会假装成功**：
 * 任务以稳定原因码 `parser-unavailable` 失败，文档与版本同样标失败（旧可用版本不受影响）。
 */
public interface AiKnowledgeIngestionStep {

    /** 步骤名（写入日志与失败原因，不含正文）。 */
    String name();

    /** 处理一个已领取的任务。 */
    AiKnowledgeIngestionStepOutcome process(AiKnowledgeIngestionTaskLeaseDTO lease);
}
