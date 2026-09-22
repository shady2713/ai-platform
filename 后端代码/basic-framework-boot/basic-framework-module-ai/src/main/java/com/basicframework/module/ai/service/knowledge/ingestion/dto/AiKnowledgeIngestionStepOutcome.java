package com.basicframework.module.ai.service.knowledge.ingestion.dto;

/**
 * 入库步骤结论（服务层 DTO）：成功 / 失败（稳定原因码）/ 未处理（无实现，留给后续步骤）。
 */
public record AiKnowledgeIngestionStepOutcome(String status, String reasonCode) {

    /** 成功。 */
    public static AiKnowledgeIngestionStepOutcome succeeded() {
        return new AiKnowledgeIngestionStepOutcome("SUCCEEDED", null);
    }

    /** 失败（原因码必须脱敏且稳定）。 */
    public static AiKnowledgeIngestionStepOutcome failed(String reasonCode) {
        return new AiKnowledgeIngestionStepOutcome("FAILED", reasonCode);
    }

    /** 未处理：当前没有可执行的实现（例如解析器尚未接入）。 */
    public static AiKnowledgeIngestionStepOutcome unhandled(String reasonCode) {
        return new AiKnowledgeIngestionStepOutcome("UNHANDLED", reasonCode);
    }

    public boolean isSucceeded() {
        return "SUCCEEDED".equals(status);
    }

    public boolean isFailed() {
        return "FAILED".equals(status);
    }
}
