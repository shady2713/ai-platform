package com.basicframework.module.ai.service.tool;

import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import java.util.Map;

/**
 * 工具执行判定（D08）：政策矩阵的结果 + 可直接执行的上下文。
 *
 * <p>只有 {@link Outcome#EXECUTE} 的判定才能进执行器；{@link Outcome#CONFIRM} 必须经确认流程
 * （D09 编排）后重新判定——判定对象本身不是"执行许可"，执行器会再次检查 outcome。
 */
public record AiToolDecision(
        Outcome outcome,
        AiToolVersionDO version,
        Long connectorId,
        String operationKey,
        Map<String, Object> arguments) {

    /** 判定结果。 */
    public enum Outcome {
        /** 可直接执行（政策 AUTO 且参数校验通过）。 */
        EXECUTE,
        /** 需要人工确认（政策 CONFIRM）。 */
        CONFIRM
    }

    public AiToolDecision {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    /** 是否可执行（执行器只接受这一种判定）。 */
    public boolean executable() {
        return outcome == Outcome.EXECUTE;
    }
}
