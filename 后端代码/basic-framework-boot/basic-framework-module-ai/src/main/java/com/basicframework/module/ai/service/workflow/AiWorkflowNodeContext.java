package com.basicframework.module.ai.service.workflow;

import com.basicframework.module.ai.domain.policy.AiOutboundLevel;
import java.time.Duration;

/**
 * 节点执行上下文（X08）：执行器传给节点处理器的运行期事实。
 *
 * <p>处理器从这里拿**运行级**的约束（外发等级、剩余耗时预算），节点级配置从图节点里解析——
 * 两类信息分开，处理器不能越过执行器改预算或等级。
 *
 * @param dataLevel        运行数据等级（模型节点的外发等级）
 * @param remainingTimeout 剩余耗时预算（模型调用的超时上限）
 */
public record AiWorkflowNodeContext(AiOutboundLevel dataLevel, Duration remainingTimeout) {}
