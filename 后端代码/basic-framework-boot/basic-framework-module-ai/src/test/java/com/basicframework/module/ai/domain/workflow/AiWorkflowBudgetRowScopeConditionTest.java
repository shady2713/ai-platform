package com.basicframework.module.ai.domain.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 流程域小组件：预算封顶、冻结行范围、条件操作符与受理摘要。
 */
class AiWorkflowBudgetRowScopeConditionTest {

    // ---------- 预算 ----------

    @Test
    void budgetCapsAreEnforcedAtConstruction() {
        assertThatThrownBy(() -> AiWorkflowBudget.of(AiWorkflowGraph.MAX_NODES + 1, 60_000L))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> AiWorkflowBudget.of(1, AiWorkflowBudget.MAX_DURATION_CAP + 1))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> AiWorkflowBudget.of(0, 60_000L)).isInstanceOf(ServiceException.class);
        AiWorkflowBudget budget = AiWorkflowBudget.of(null, null);
        assertThat(budget.getMaxSteps()).isEqualTo(AiWorkflowBudget.DEFAULT_MAX_STEPS);
        assertThat(budget.getMaxDurationMillis()).isEqualTo(AiWorkflowBudget.DEFAULT_MAX_DURATION_MILLIS);
        assertThat(budget.hasStepLeft(AiWorkflowBudget.DEFAULT_MAX_STEPS - 1)).isTrue();
        assertThat(budget.hasStepLeft(AiWorkflowBudget.DEFAULT_MAX_STEPS)).isFalse();
        assertThat(budget.durationExceeded(60_001L)).isTrue();
    }

    // ---------- 冻结行范围 ----------

    @Test
    void rowScopeRejectsOperatorsOutsideWhitelistAndNonScalars() {
        assertThatThrownBy(() -> AiWorkflowRowScope.parse(
                        List.of(Map.of("sourceColumn", "a", "operator", "LIKE", "values", List.of("x")))))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> AiWorkflowRowScope.parse(
                        List.of(Map.of("sourceColumn", "a", "operator", "IN", "values", List.of(Map.of("nested", 1))))))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> AiWorkflowRowScope.parse(List.of())).isInstanceOf(ServiceException.class);
    }

    @Test
    void rowScopeEffectivenessRequiresCompleteConditions() {
        AiWorkflowRowScope effective = AiWorkflowRowScope.parse(
                List.of(Map.of("sourceColumn", "dept_id", "operator", "IN", "values", List.of(1, 2))));
        assertThat(effective.isEffective()).isTrue();
        assertThat(effective.toQueryScope().isEffective()).isTrue();
        // EQ 只允许一个取值；空取值不是"不过滤"
        assertThat(new AiWorkflowRowScope(List.of(new AiWorkflowRowScope.Condition("a", "EQ", List.of(1, 2))))
                        .isEffective())
                .isFalse();
        assertThat(new AiWorkflowRowScope(List.of(new AiWorkflowRowScope.Condition("a", "IN", List.of()))))
                .extracting(AiWorkflowRowScope::isEffective)
                .isEqualTo(false);
        assertThat(new AiWorkflowRowScope(List.of(new AiWorkflowRowScope.Condition(null, "IN", List.of(1))))
                        .isEffective())
                .isFalse();
    }

    // ---------- 条件操作符 ----------

    @Test
    void conditionOperatorsAreControlledAndDeterministic() {
        assertThat(AiWorkflowConditionOperator.parse("contains")).isPresent();
        assertThat(AiWorkflowConditionOperator.parse("EXEC(1)")).isEmpty();
        assertThat(AiWorkflowConditionOperator.parse(null)).isEmpty();
        assertThat(AiWorkflowConditionOperator.EQ.evaluate("ok", "ok")).isTrue();
        assertThat(AiWorkflowConditionOperator.NE.evaluate(null, "ok")).isTrue();
        assertThat(AiWorkflowConditionOperator.CONTAINS.evaluate("审核通过", "通过")).isTrue();
        assertThat(AiWorkflowConditionOperator.NOT_CONTAINS.evaluate("审核通过", "拒绝"))
                .isTrue();
        assertThat(AiWorkflowConditionOperator.GT.evaluate("12.5", "10")).isTrue();
        assertThat(AiWorkflowConditionOperator.LT.evaluate("3", "10")).isTrue();
        // 不可比较是确定性 FALSE 分支，不是运行错误
        assertThat(AiWorkflowConditionOperator.GT.evaluate("无法解析", "10")).isFalse();
        assertThat(AiWorkflowConditionOperator.GT.evaluate("10", "无法解析")).isFalse();
    }

    // ---------- 受理摘要 ----------

    @Test
    void digestCoversEveryAcceptedFieldAndGraphHashIsStable() {
        String base = AiWorkflowGraph.runDigest(1L, "L2_INTERNAL", "输入", 16, 60_000L);
        assertThat(base).hasSize(64);
        assertThat(base).isEqualTo(AiWorkflowGraph.runDigest(1L, "L2_INTERNAL", "输入", 16, 60_000L));
        assertThat(base).isNotEqualTo(AiWorkflowGraph.runDigest(1L, "L1_PUBLIC", "输入", 16, 60_000L));
        assertThat(base).isNotEqualTo(AiWorkflowGraph.runDigest(1L, "L2_INTERNAL", "别的输入", 16, 60_000L));
        assertThat(base).isNotEqualTo(AiWorkflowGraph.runDigest(1L, "L2_INTERNAL", "输入", 8, 60_000L));
        assertThat(base).isNotEqualTo(AiWorkflowGraph.runDigest(2L, "L2_INTERNAL", "输入", 16, 60_000L));
        assertThat(AiWorkflowGraph.graphHash("{}"))
                .isEqualTo(AiWorkflowGraph.graphHash("{}"))
                .hasSize(64)
                .isNotEqualTo(base);
    }

    @Test
    void nodeTypeParseIsClosed() {
        assertThat(AiWorkflowNodeType.parse("tool")).isPresent();
        assertThat(AiWorkflowNodeType.parse("SCRIPT")).isEmpty();
        assertThat(AiWorkflowNodeType.parse(" ")).isEmpty();
        assertThat(AiWorkflowNodeType.MODEL.producesOutput()).isTrue();
        assertThat(AiWorkflowNodeType.CONDITION.producesOutput()).isFalse();
        assertThat(AiErrorCodeConstants.AI_WORKFLOW_NOT_FOUND.getCode()).isEqualTo(1_003_012_000);
    }
}
