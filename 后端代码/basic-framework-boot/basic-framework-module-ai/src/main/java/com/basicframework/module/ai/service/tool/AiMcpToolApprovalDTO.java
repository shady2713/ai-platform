package com.basicframework.module.ai.service.tool;

/**
 * MCP 工具审批结果（X07）。
 *
 * <p>审批<b>不</b>等于可执行：它只表示"这条上游工具的结构与描述已被人工确认"，
 * 之后它仍要走 D08 的工具注册、版本发布与政策闸门（默认 DENY）才可能执行。
 * 因此这里刻意不返回"可执行"字样，避免调用方把审批结果当成执行许可。
 *
 * @param draftId          草稿编号
 * @param toolId           平台工具编号（注册进 ai_tool 后）
 * @param toolVersionId    平台工具版本编号（草稿版本）
 * @param policy           落到 D08 版本上的执行政策（默认 DENY）
 * @param approvedFingerprint 本次审批锁定的结构指纹
 */
public record AiMcpToolApprovalDTO(
        Long draftId, Long toolId, Long toolVersionId, String policy, String approvedFingerprint) {}
