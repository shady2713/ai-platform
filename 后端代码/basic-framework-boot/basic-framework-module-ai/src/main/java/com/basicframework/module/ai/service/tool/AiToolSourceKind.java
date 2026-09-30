package com.basicframework.module.ai.service.tool;

/**
 * 工具来源类型（X07 增补 MCP）。
 *
 * <p>D08 的 {@code AiToolVersionDO} 只登记了 {@code HTTP_OPERATION} 一个来源；本卡为 MCP
 * 工具增加 {@link #MCP_TOOL}。声明为接口常量持有者而不是往 DO 里加静态字段，
 * 是为了不改动 D08 已冻结的数据对象契约（DO 的常量属于对外可见的持久层 API）。
 *
 * <p>两个来源在 D08 的发布路径上<b>分叉</b>：HTTP 来源要求来源 operation 已发布；
 * MCP 来源没有 operation，其准入判据是 {@link AiMcpToolService#requireApprovable}。
 * 这个分叉是本卡"不复用 HTTP 发布检查，也不绕过审批"的落点。
 */
public interface AiToolSourceKind {

    /** MCP 工具（MCP {@code tools/list} 发现，经人工审批后登记）。 */
    String MCP_TOOL = "MCP_TOOL";
}
