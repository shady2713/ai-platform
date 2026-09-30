/**
 * 受控 MCP 客户端接缝（X07）。
 *
 * <p>本包是全平台<b>唯一</b>允许直接引用 {@code io.modelcontextprotocol.sdk} 的区域
 * （与 {@code provider.springai} 对 Spring AI 的做法同形）：业务模块只消费本包定义的
 * 平台自有契约（{@link com.basicframework.framework.ai.provider.mcp.McpClientSession}、
 * {@link com.basicframework.framework.ai.provider.mcp.McpDiscoverySnapshot} 等），
 * 不直接依赖厂商类型，将来更换 SDK 只改本包。
 *
 * <p>本卡<b>只做客户端</b>：建立连接、{@code initialize}、{@code tools/list}、报告断线与版本漂移。
 * 刻意不做的事：不做服务端 MCP Server、不代执行远程工具、不把工具注册成模型 callback。
 * 原因写在卡片 §2.1：Spring AI 的 MCP client starter 正是"callback 直喂模型"路线，
 * 与本卡"发现只生成待审批草稿 + 未知新工具默认拒绝"的核心不变量相反。
 */
package com.basicframework.framework.ai.provider.mcp;
