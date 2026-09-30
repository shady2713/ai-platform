package com.basicframework.module.ai.service.tool;

import com.basicframework.framework.ai.provider.mcp.McpEndpointPolicy;
import java.util.List;

/**
 * MCP 工具发现与审批（X07）服务契约。
 *
 * <p>本服务是"MCP 世界"与"D08 工具注册世界"之间唯一的接缝。它<b>不</b>发明第二套审批管线：
 * 发现只产出 {@code ai_mcp_tool_draft} 草稿与一条发现留痕；真正的工具注册、版本、政策与执行
 * 仍然由 D08 的 {@link AiToolService} / {@link AiToolPolicyGate} / {@link AiToolExecutor} 承担。
 *
 * <p>因此本接口刻意<b>不</b>提供任何"执行 MCP 工具"的方法：端口上不存在执行入口。
 */
public interface AiMcpToolService {

    /**
     * 对一个已登记的 MCP 连接器执行一次工具发现。
     *
     * <p>语义（验收 1/2/3/4 的共同落点）：
     * <ul>
     *   <li>新工具 → 只生成**待审批草稿**，不进入注册表、不可执行；</li>
     *   <li>已知且结构未变 → 原样保留既有审批，不重复打扰审批人；</li>
     *   <li>已知但结构已变（上游升级）→ <b>阻断</b>该工具的旧发布并记留痕，必须重新审批；</li>
     *   <li>断线 → 有界重试后<b>明确终止</b>并记留痕，绝不退化成"上游没有工具"。</li>
     * </ul>
     *
     * @return 本次发现摘要（新工具数、漂移阻断数、实际尝试次数与终止结果）
     */
    AiMcpDiscoveryResultDTO discover(Long connectorId);

    /**
     * 人工审批一个 MCP 工具草稿。
     *
     * <p>只有"当前观察到的指纹"与"审批时再次核对的指纹"一致时才可能成功；
     * 已被上游改动（{@code BLOCKED}）的草稿必须先重新发现以刷新指纹，再重新审批。
     */
    AiMcpToolApprovalDTO approve(Long draftId, Integer version);

    /** 查询草稿。 */
    AiMcpToolDraftDTO getDraft(Long connectorId, String upstreamToolName);

    /** 列出某连接器下的全部草稿。 */
    List<AiMcpToolDraftDTO> listDrafts(Long connectorId);

    /**
     * MCP 工具进入执行面前的绑定闸门（验收 4 的执行侧）。
     *
     * <p>由 D08 的工具注册服务在创建/发布 MCP 来源工具版本时调用：草稿未审批、
     * 审批指纹与当前观察指纹不符、或草稿已阻断，一律拒绝。
     */
    void requireApprovable(Long connectorId, String upstreamToolName);

    /** 构造出站准入策略（平台配置 → 策略对象）。 */
    McpEndpointPolicy endpointPolicy();
}
