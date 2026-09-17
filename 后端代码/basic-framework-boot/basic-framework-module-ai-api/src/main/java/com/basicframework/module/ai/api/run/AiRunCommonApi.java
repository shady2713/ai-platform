package com.basicframework.module.ai.api.run;

/**
 * AI 运行薄契约：供平台内其它模块跨模块调用运行服务。
 *
 * <p>v1 只发布只读状态查询：跨模块调用方按运行业务键（{@code run_xxx}）读取当前状态，
 * 不接触 Mapper、DO 或 Controller VO。运行受理、事件与取消在 O 系列任务中按同一接口扩展，
 * 扩展保持向后兼容；调用方不得复制一份平行契约。
 *
 * <p>实现由 {@code basic-framework-module-ai} 提供；消费方包括未来的管理端聚合视图与
 * 跨模块运行观测，当前仓库内暂无消费点（见 docs/adr/0028-framework-seams-may-have-zero-in-repo-consumers.md
 * 对零消费者能力缝的约束：README 必须说明启用条件，契约测试钉住行为）。
 */
public interface AiRunCommonApi {

    /**
     * 查询运行当前状态。
     *
     * @param runKey 运行业务键，形如 {@code run_xxx}，不能为空
     * @return 运行状态；运行业务键不存在时返回 {@code null}，由调用方决定如何处理
     */
    AiRunStatusEnum getRunStatus(String runKey);
}
