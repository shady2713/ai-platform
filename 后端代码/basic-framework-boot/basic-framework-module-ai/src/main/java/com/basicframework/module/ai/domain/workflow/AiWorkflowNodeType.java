package com.basicframework.module.ai.domain.workflow;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 流程节点类型（X08 冻结契约，FR-39）。
 *
 * <p>有界 DAG：只有这七种节点，禁止任意脚本节点（扩展必须改本契约并同步两侧校验）。
 * 产出输出的节点只有四种（{@link #producesOutput}）：模型 / 知识检索 / 数据查询 / 工具——
 * 条件节点的比较引用只能指向它们。
 */
public enum AiWorkflowNodeType {

    /** 开始节点：无入边、无配置，输出为运行输入文本。 */
    START,

    /** 模型节点：经 M05 受控调用（外发策略 + 计量），端点在发布时冻结。 */
    MODEL,

    /** 知识检索节点：经 K06 按当前主体授权检索（管理端受控运行无主体会话时按 ACL 拒绝）。 */
    KNOWLEDGE_RETRIEVAL,

    /** 数据查询节点：经 R05 受控执行（冻结计划 + 非空行范围强制拼 WHERE）。 */
    DATA_QUERY,

    /** 工具节点：经 D08 政策闸门判定 + D02 受控执行；CONFIRM/DENY 政策受控结束。 */
    TOOL,

    /** 条件节点：受控比较表达式（无脚本），按 TRUE/FALSE 分支出边。 */
    CONDITION,

    /** 结束节点：无出边，上游输出成为运行输出。 */
    END;

    /** 产出文本输出的节点类型（条件节点的合法比较引用）。 */
    public static final Set<AiWorkflowNodeType> OUTPUT_PRODUCING = Set.of(MODEL, KNOWLEDGE_RETRIEVAL, DATA_QUERY, TOOL);

    /** 是否产出文本输出。 */
    public boolean producesOutput() {
        return OUTPUT_PRODUCING.contains(this);
    }

    /** 解析节点类型；未知类型返回空（调用方按"类型不匹配"拒绝）。 */
    public static Optional<AiWorkflowNodeType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknownType) {
            return Optional.empty();
        }
    }
}
