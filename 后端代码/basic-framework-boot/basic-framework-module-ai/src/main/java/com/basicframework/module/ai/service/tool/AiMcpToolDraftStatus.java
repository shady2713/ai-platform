package com.basicframework.module.ai.service.tool;

/**
 * MCP 草稿审批状态（X07）。
 *
 * <p>用独立常量持有者而不是字符串散落在各处，是为了让"状态机"在一处可见：
 * {@code DRAFT → APPROVED}、{@code APPROVED → BLOCKED}、{@code BLOCKED → DRAFT}（重新发现后）。
 *
 * <p><b>没有</b> {@code DISABLED}/{@code DELETED} 之类的"软"状态：草稿要么待审批、要么已审批、
 * 要么被上游改动阻断，三者对"能不能进执行面"的结论各不相同，多一个状态就多一个
 * "忘了处理"而放行的缝隙。
 *
 * <p>声明为接口（与 {@code AiErrorCodeConstants} 等登记册同形）而不是带私有构造器的类：
 * 前者不产生任何需要覆盖的实例化代码。
 */
public interface AiMcpToolDraftStatus {

    /** 已发现、待审批：<b>不可</b>进入执行面。 */
    String DRAFT = "DRAFT";

    /** 已审批：可以进入 D08 注册与政策流程（不等于可执行）。 */
    String APPROVED = "APPROVED";

    /** 已阻断：上游结构漂移导致旧审批失效，必须重新发现 + 重新审批。 */
    String BLOCKED = "BLOCKED";
}
