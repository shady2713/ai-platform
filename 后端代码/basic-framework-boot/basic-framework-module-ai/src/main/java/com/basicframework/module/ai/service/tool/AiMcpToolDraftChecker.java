package com.basicframework.module.ai.service.tool;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MCP_TOOL_NOT_APPROVED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MCP_TOOL_SCHEMA_DRIFT_CONFLICT;

import com.basicframework.module.ai.dal.dataobject.mcp.AiMcpToolDraftDO;
import com.basicframework.module.ai.dal.mysql.mcp.AiMcpToolDraftMapper;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * MCP 草稿准入判据（X07）：只依赖草稿表，因此不与工具注册服务形成依赖环。
 *
 * <p>把判据单独抽成一类，是为了让"同一套判据"被两处复用而只有一份实现：
 * 一是发布路径（{@link AiToolSourceGuard} 实现），二是 MCP 服务自身的
 * {@link AiMcpToolService#requireApprovable}。两处若各写一份，迟早会漂移成
 * "发布时判得严、别处判得松"或反之。
 */
@Component
@RequiredArgsConstructor
public class AiMcpToolDraftChecker {

    private final AiMcpToolDraftMapper draftMapper;

    /**
     * 判定某个上游工具当前是否可被登记/发布。
     *
     * <p>三种拒绝互不替代：<b>没有草稿</b>（404 语义之外，一律按"未审批"拒绝）、
     * <b>已漂移阻断</b>（409，与已发布项冲突）、<b>未审批或审批指纹与观察指纹不符</b>（422）。
     */
    public void requireApprovable(Long connectorId, String upstreamToolName) {
        AiMcpToolDraftDO draft = draftMapper.selectByUpstream(connectorId, upstreamToolName);
        if (draft == null) {
            throw exception(AI_MCP_TOOL_NOT_APPROVED);
        }
        if (AiMcpToolDraftStatus.BLOCKED.equals(draft.getStatus())) {
            // 旧发布已被上游改动阻断：即使之前审批过，现在也一律拒绝
            throw exception(AI_MCP_TOOL_SCHEMA_DRIFT_CONFLICT);
        }
        if (!AiMcpToolDraftStatus.APPROVED.equals(draft.getStatus())
                || !Objects.equals(draft.getApprovedFingerprint(), draft.getObservedFingerprint())) {
            throw exception(AI_MCP_TOOL_NOT_APPROVED);
        }
    }
}
