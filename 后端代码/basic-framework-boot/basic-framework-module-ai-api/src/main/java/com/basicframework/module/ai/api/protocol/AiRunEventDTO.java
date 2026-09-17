package com.basicframework.module.ai.api.protocol;

import java.util.Set;

/**
 * RunEvent v1 的 Java 映射（对应 docs/contracts/ai/run-event.schema.json）。
 *
 * <p>未知 schemaVersion 必须拒绝：协议升级要先改 Schema 与样例，再改两侧实现。
 */
public record AiRunEventDTO(
        String schemaVersion, Integer seq, String runId, String status, AiResultBlockDTO block, String createdAt) {

    private static final Set<String> SUPPORTED_STATUS =
            Set.of("QUEUED", "RUNNING", "WAITING_INPUT", "WAITING_CONFIRMATION", "SUCCEEDED", "FAILED", "CANCELLED");

    /** 协议级校验：版本、序号、运行业务键与状态都必须合法。 */
    public void validate() {
        if (!"1.0".equals(schemaVersion)) {
            throw new IllegalArgumentException("未知协议版本：" + schemaVersion);
        }
        if (seq == null || seq < 1) {
            throw new IllegalArgumentException("事件序号必须为正整数");
        }
        if (runId == null || !runId.matches("^run_[A-Za-z0-9_-]{3,35}$")) {
            throw new IllegalArgumentException("运行业务键不合法：" + runId);
        }
        if (status == null || !SUPPORTED_STATUS.contains(status)) {
            throw new IllegalArgumentException("未知运行状态：" + status);
        }
        if (createdAt == null || createdAt.isBlank()) {
            throw new IllegalArgumentException("事件缺少创建时间");
        }
        if (block != null) {
            block.validate();
        }
    }
}
