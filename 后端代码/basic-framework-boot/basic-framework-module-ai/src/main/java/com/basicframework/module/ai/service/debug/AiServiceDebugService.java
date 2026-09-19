package com.basicframework.module.ai.service.debug;

import com.basicframework.module.ai.service.debug.dto.AiServiceDebugResultDTO;
import com.basicframework.module.ai.service.debug.dto.AiServiceDebugRunDTO;

/**
 * 服务调试（S04）：用显式测试主体跑一次真实运行链路，并只返回阶段摘要与证据。
 *
 * <p>约束：
 * <ul>
 *   <li>调试版本取**当前生效版本**（别名解析），与线上新运行的解析路径完全一致；</li>
 *   <li>授权按**显式测试主体**的当前授权逐条判定发布版本绑定的资源动作：
 *       测试主体没有的权限，调试也拿不到（调试不能越权）；</li>
 *   <li>上下文按 {@link com.basicframework.module.ai.service.context.AiContextBuilder} 的固定分区与预算规则拼装；</li>
 *   <li>模型调用走统一调用编排（外发策略 → 端点解析 → 调用 → 计量），上游失败按稳定错误结束，不返回假成功；</li>
 *   <li>结果只包含阶段摘要、分区统计、用量与可见输出，不回显提示词正文，也不记录隐藏推理。</li>
 * </ul>
 */
public interface AiServiceDebugService {

    /** 执行一次调试运行。 */
    AiServiceDebugResultDTO debugRun(AiServiceDebugRunDTO request);
}
