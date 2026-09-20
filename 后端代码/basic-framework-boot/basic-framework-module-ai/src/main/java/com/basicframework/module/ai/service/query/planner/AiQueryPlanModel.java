package com.basicframework.module.ai.service.query.planner;

/**
 * 查询计划模型端口（D05）：规划器与"具体怎么调模型"之间的唯一接缝。
 *
 * <p>为什么单独抽象一层：规划器的安全语义（只给授权摘要、只收结构化计划、修复不扩大范围）
 * 必须能用**固定夹具**离线验证——真实模型不可复现，也不该出现在单元测试里。
 * 生产实现走 M05 的调用编排（策略先于网络调用），测试实现返回固定 JSON。
 */
public interface AiQueryPlanModel {

    /**
     * 请求模型给出 PLAN 或 CLARIFICATION。
     *
     * @param endpointId 模型端点编号（由调用方解析并授权）
     * @param prompt     提示词（只含授权摘要与输出契约）
     * @param jsonSchema 输出契约（JSON Schema 文本；平台侧仍会二次校验）
     * @return 模型返回的 JSON 对象文本
     */
    String propose(Long endpointId, String prompt, String jsonSchema);
}
