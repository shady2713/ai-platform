package com.basicframework.module.ai.service.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.mcp.AiMcpToolDraftDO;
import com.basicframework.module.ai.dal.mysql.mcp.AiMcpToolDraftMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import org.junit.jupiter.api.Test;

/**
 * MCP 来源守卫与草稿判据。
 *
 * <p>这两个类很薄，但它们承载"发布路径凭什么相信一个 MCP 工具"这一判断，
 * 因此单独钉住：守卫必须声明自己负责 {@code MCP_TOOL}，并把判据原样转交，
 * 不自己发明第二套判定。
 */
class AiMcpToolSourceGuardTest {

    private final AiMcpToolDraftMapper draftMapper = mock(AiMcpToolDraftMapper.class);

    private final AiMcpToolDraftChecker checker = new AiMcpToolDraftChecker(draftMapper);

    private final AiMcpToolSourceGuard guard = new AiMcpToolSourceGuard(checker);

    private static AiMcpToolDraftDO draft(String status, String observed, String approved) {
        return new AiMcpToolDraftDO()
                .setId(1L)
                .setConnectorId(501L)
                .setUpstreamToolName("search_orders")
                .setObservedFingerprint(observed)
                .setApprovedFingerprint(approved)
                .setStatus(status);
    }

    @Test
    void guardClaimsTheMcpToolSourceKind() {
        assertThat(guard.supportedSourceKind()).isEqualTo(AiToolSourceKind.MCP_TOOL);
        assertThat(AiToolSourceKind.MCP_TOOL).isEqualTo("MCP_TOOL");
    }

    @Test
    void guardDelegatesToTheSharedCheckerWithoutInventingItsOwnRule() {
        when(draftMapper.selectByUpstream(501L, "search_orders"))
                .thenReturn(draft(AiMcpToolDraftStatus.APPROVED, "fp-1", "fp-1"));

        guard.requireApprovable(501L, "search_orders");

        verify(draftMapper).selectByUpstream(501L, "search_orders");
    }

    @Test
    void checkerRejectsUnknownUnapprovedBlockedAndMismatchedDrafts() {
        when(draftMapper.selectByUpstream(501L, "missing")).thenReturn(null);
        assertThatThrownBy(() -> checker.requireApprovable(501L, "missing"))
                .isInstanceOf(ServiceException.class)
                .extracting(t -> ((ServiceException) t).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MCP_TOOL_NOT_APPROVED.getCode());

        when(draftMapper.selectByUpstream(501L, "blocked"))
                .thenReturn(draft(AiMcpToolDraftStatus.BLOCKED, "fp-2", null));
        assertThatThrownBy(() -> checker.requireApprovable(501L, "blocked"))
                .isInstanceOf(ServiceException.class)
                .extracting(t -> ((ServiceException) t).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MCP_TOOL_SCHEMA_DRIFT_CONFLICT.getCode());

        when(draftMapper.selectByUpstream(501L, "mismatch"))
                .thenReturn(draft(AiMcpToolDraftStatus.APPROVED, "fp-new", "fp-old"));
        assertThatThrownBy(() -> checker.requireApprovable(501L, "mismatch"))
                .isInstanceOf(ServiceException.class)
                .extracting(t -> ((ServiceException) t).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MCP_TOOL_NOT_APPROVED.getCode());
    }

    @Test
    void draftDtoExposesApprovalAndBlockedFlags() {
        AiMcpToolDraftDTO approved = new AiMcpToolDraftDTO(
                1L, 501L, "t", "title", "d", "fp", "fp", AiMcpToolDraftStatus.APPROVED, 9L, 10L, null, null, null, 0);
        assertThat(approved.approved()).isTrue();
        assertThat(approved.blocked()).isFalse();

        AiMcpToolDraftDTO blocked = new AiMcpToolDraftDTO(
                1L,
                501L,
                "t",
                "title",
                "d",
                "fp",
                null,
                AiMcpToolDraftStatus.BLOCKED,
                null,
                null,
                1003019007,
                null,
                null,
                1);
        assertThat(blocked.approved()).isFalse();
        assertThat(blocked.blocked()).isTrue();
    }
}
