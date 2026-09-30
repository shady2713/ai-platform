package com.basicframework.module.ai.service.tool;

/**
 * 工具来源准入守卫（X07）：非 HTTP 来源在**发布**时的额外判据端口。
 *
 * <p>沿用 D08 已有的端口惯例（{@code AiToolReferenceChecker}、D01 的 {@code AiConnectorReferenceChecker}）：
 * D08 的服务不认识具体来源，只在发布时问"这个来源还有谁要说话"，由各能力域注册实现。
 *
 * <p><b>为什么必须是窄端口而不是直接依赖 {@link AiMcpToolService}</b>：
 * MCP 服务需要调用 {@code AiToolService.createVersion} 完成注册，而发布路径又要问 MCP 守卫——
 * 直接互相依赖会构成 Spring 的**构造器注入循环**（两个 bean 都在对方构造期间被需要）。
 * 抽出只依赖草稿表的窄端口后，依赖方向是单向的：守卫 → 草稿表，发布路径 → 守卫。
 *
 * <p>默认拒绝由**业务分支**保证：{@code sourceKind} 不是 HTTP 来源时，必须存在一个
 * {@link #supports(String)} 该来源的守卫，否则发布直接拒绝——"没有守卫"等价于"没有判据"，
 * 等价于"不通过"，而不是"跳过检查"。
 */
public interface AiToolSourceGuard {

    /** 本守卫负责的来源类型（取值见 {@code AiToolSourceKind}）。 */
    String supportedSourceKind();

    /**
     * 发布前校验该来源的准入条件；不通过必须抛出带稳定错误码的异常。
     *
     * @param connectorId 连接器编号（来源连接器）
     * @param sourceRef   来源标识（MCP 场景下是上游工具名）
     */
    void requireApprovable(Long connectorId, String sourceRef);
}
