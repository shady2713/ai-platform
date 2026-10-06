package com.basicframework.module.ai.domain.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 计划哈希的回归测试。
 *
 * <p>为什么单独一个类：此前 {@code planHash()} 用 {@code List.of(...)} 拼时间窗口的规范化
 * 字段，而 {@code List.of} **遇 null 直接抛 NPE**。粒度在数据集未声明时间语义时就是
 * {@code null}（{@code AiQueryPlanValidator} 按声明取值：没声明就是 null），
 * 于是一条完全合法的时间窗口会仅仅因为"算哈希"而崩成 {@code NullPointerException}，
 * 调用方拿不到稳定错误码，只看到一个与问题无关的栈。
 */
class ValidatedQueryPlanHashTest {

    private static ValidatedQueryPlan planWith(ValidatedQueryPlan.TimeWindow window) {
        return new ValidatedQueryPlan(
                1L,
                "orders",
                2L,
                3,
                "a".repeat(64),
                "order",
                List.of(new ValidatedQueryPlan.Metric("amount", "amt", "SUM", "CNY")),
                List.of(),
                List.of(),
                window,
                List.of(),
                100);
    }

    /**
     * 数据集声明了时间语义（有粒度）：这是原先就正常的路径，顺带钉住哈希的稳定性。
     */
    @Test
    void hashesWindowThatDeclaresGranularity() {
        ValidatedQueryPlan plan = planWith(new ValidatedQueryPlan.TimeWindow(
                "created_at", "created", "2026-01-01T00:00:00", "2026-02-01T00:00:00", "Asia/Shanghai", "DAY"));

        assertThat(plan.planHash()).hasSize(64);
        // 同一份计划永远同一个哈希：这是幂等键的基础
        assertThat(plan.planHash())
                .isEqualTo(planWith(new ValidatedQueryPlan.TimeWindow(
                                "created_at",
                                "created",
                                "2026-01-01T00:00:00",
                                "2026-02-01T00:00:00",
                                "Asia/Shanghai",
                                "DAY"))
                        .planHash());
    }

    /**
     * 数据集**未**声明时间语义 ⇒ 粒度为 null。
     *
     * <p>这是本测试存在的全部理由：修复前这里抛 NPE。
     */
    @Test
    void hashesWindowWithoutDeclaredGranularityInsteadOfThrowing() {
        ValidatedQueryPlan plan = planWith(new ValidatedQueryPlan.TimeWindow(
                "created_at", "created", "2026-01-01T00:00:00", "2026-02-01T00:00:00", "Asia/Shanghai", null));

        assertThatCode(plan::planHash).doesNotThrowAnyException();
        assertThat(plan.planHash()).hasSize(64);
    }

    /**
     * 有粒度与无粒度必须算出**不同**的哈希。
     *
     * <p>否则"声明了粒度"与"没声明"会撞成同一份计划哈希，幂等键就分不清二者——
     * 这正是把 null 塞进哈希列表时要小心的地方：允许 null 不等于允许丢失区分度。
     */
    @Test
    void distinguishesDeclaredFromUndeclaredGranularity() {
        String withGranularity = planWith(new ValidatedQueryPlan.TimeWindow(
                        "created_at", "created", "2026-01-01T00:00:00", "2026-02-01T00:00:00", "Asia/Shanghai", "DAY"))
                .planHash();
        String withoutGranularity = planWith(new ValidatedQueryPlan.TimeWindow(
                        "created_at", "created", "2026-01-01T00:00:00", "2026-02-01T00:00:00", "Asia/Shanghai", null))
                .planHash();

        assertThat(withoutGranularity).isNotEqualTo(withGranularity);
    }
}
