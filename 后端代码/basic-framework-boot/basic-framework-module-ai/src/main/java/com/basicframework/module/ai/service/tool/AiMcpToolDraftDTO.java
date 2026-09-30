package com.basicframework.module.ai.service.tool;

import java.time.LocalDateTime;

/**
 * MCP 工具草稿视图（X07）。
 *
 * <p>{@code description} 是**上游不可信文本**，字段注释已注明；它存在的唯一目的是让人工审批人
 * 读懂这个工具是干什么的。平台不解析它、不信任它、任何授权判定都不读它。
 *
 * <p>本 record 只承载服务层事实；控制器 VO 属于协议层，不在本卡范围（卡片 §2 未授权控制器路径）。
 */
public record AiMcpToolDraftDTO(
        Long id,
        Long connectorId,
        String upstreamToolName,
        String upstreamTitle,
        String upstreamDescription,
        String observedFingerprint,
        String approvedFingerprint,
        String status,
        Long toolId,
        Long toolVersionId,
        Integer blockedReasonCode,
        LocalDateTime firstDiscoveredAt,
        LocalDateTime lastDiscoveredAt,
        Integer version) {

    /** 是否处于"可执行"意义上的已审批态（仍要过 D08 政策闸门才算可执行）。 */
    public boolean approved() {
        return AiMcpToolDraftStatus.APPROVED.equals(status);
    }

    /** 是否已被上游漂移阻断。 */
    public boolean blocked() {
        return AiMcpToolDraftStatus.BLOCKED.equals(status);
    }
}
