package com.basicframework.module.ai.service.workflow;

import com.basicframework.module.ai.domain.workflow.AiWorkflowGraph;
import com.basicframework.module.ai.domain.workflow.AiWorkflowNodeType;

/**
 * 流程节点处理器（X08）：一种需要"做实事"的节点一个处理器。
 *
 * <p>边界：处理器只能经**既有受控入口**执行动作（模型走 M05、检索走 K06、查询走 R05、
 * 工具走 D08 闸门 + D02 执行器），不允许自建网络/数据库路径。失败以 {@code ServiceException}
 * 抛出（稳定错误码），由执行器统一记录并受控结束运行——处理器自己不写运行/节点行。
 */
public interface AiWorkflowNodeHandler {

    /** 处理的节点类型。 */
    AiWorkflowNodeType type();

    /**
     * 执行节点。
     *
     * @param node   图节点（config 已在发布期校验形状；运行期按同一规则解析取值）
     * @param input  唯一上游的输出文本（开始节点的直接后继拿到运行输入）
     * @param context 运行级约束
     * @return 节点输出文本（进入下游与留痕摘要）
     */
    String execute(AiWorkflowGraph.Node node, String input, AiWorkflowNodeContext context);
}
