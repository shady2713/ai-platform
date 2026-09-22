package com.basicframework.module.ai.service.knowledge.ingestion.dto;

import java.time.LocalDateTime;

/**
 * 入库任务租约（服务层 DTO）：worker 执行与落库都必须带上 (owner, epoch)。
 *
 * <p>租约是"谁在跑"的唯一凭据：租约过期被接管后 epoch 递增，旧 worker 的续租与落库都会命中 0 行，
 * 因此不会覆盖新 worker 的结果（与 O03 的 ai_run_task 同一语义）。
 */
public record AiKnowledgeIngestionTaskLeaseDTO(
        Long taskId,
        Long knowledgeBaseId,
        Long documentId,
        Long documentVersionId,
        String taskKind,
        String owner,
        Integer epoch,
        LocalDateTime leaseExpiresTime) {}
