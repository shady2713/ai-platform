package com.basicframework.module.ai.service.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 把 MCP 草稿判据注册成工具来源守卫（X07）。
 *
 * <p>本类刻意很薄：它只回答"我负责 MCP_TOOL 这个来源"并转调
 * {@link AiMcpToolDraftChecker}。判据本身不写在这里，是为了让
 * {@code AiToolServiceImpl} 只依赖窄端口（{@link AiToolSourceGuard}），
 * 从而与依赖 {@code AiToolService} 的 MCP 服务之间不构成构造器注入循环。
 */
@Component
@RequiredArgsConstructor
public class AiMcpToolSourceGuard implements AiToolSourceGuard {

    private final AiMcpToolDraftChecker draftChecker;

    @Override
    public String supportedSourceKind() {
        return AiToolSourceKind.MCP_TOOL;
    }

    @Override
    public void requireApprovable(Long connectorId, String sourceRef) {
        draftChecker.requireApprovable(connectorId, sourceRef);
    }
}
