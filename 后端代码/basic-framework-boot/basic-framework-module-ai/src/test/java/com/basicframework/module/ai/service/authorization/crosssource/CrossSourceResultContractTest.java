package com.basicframework.module.ai.service.authorization.crosssource;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceResultContract.SourceAmount;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Y07 跨源结果契约的 fail-closed 规则：<b>口径恒非空，且口径决定数字出不出</b>。
 *
 * <p>本类钉的是 AT-071 专项一（字段缺失按 {@code WITHHELD}）与专项二
 * （无权来源不可反推明细）在<b>服务端</b>的落点。渲染层的对应断言在
 * {@code y07-cross-source-result-contract.test.ts}。
 *
 * <p>用例刻意分成两组：<b>正常路径</b>证明契约确实能放行，
 * <b>反向路径</b>证明每一条"缺字段/带矛盾字段"的输入都落到 {@code WITHHELD}。
 * 只跑正常路径的话，本类里最关键的三条归一分支（null 状态、未知状态、
 * {@code COMPLETE} 带理由）一条都不会被执行到。
 */
class CrossSourceResultContractTest {

    private static final String EXECUTION_KEY = "y07-exec-1";

    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 3, 1, 10, 0);

    private static List<SourceAmount> sources() {
        return List.of(new SourceAmount("orders", new BigDecimal("100.00")));
    }

    /** 一条"一切正常、全部有权"的放行响应，用作反向用例的对照组。 */
    private static CrossSourceResultContract disclosable(CrossSourceIntegrity integrity) {
        return CrossSourceResultContract.disclosable(
                EXECUTION_KEY,
                "net_revenue",
                "CNY",
                new BigDecimal("100.00"),
                1,
                sources(),
                AS_OF,
                0L,
                true,
                integrity);
    }

    @Test
    @DisplayName("AT-071 正向：全部来源有权时出具合计、来源计数与分来源明细")
    void disclosableResultCarriesEveryNumber() {
        CrossSourceResultContract contract = disclosable(CrossSourceIntegrity.complete());

        assertThat(contract.integrity().state()).isEqualTo(CrossSourceIntegrity.STATE_COMPLETE);
        assertThat(contract.integrity().reason()).isNull();
        assertThat(contract.rendersNothing()).isFalse();
        assertThat(contract.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(contract.sourceCount()).isEqualTo(1);
        assertThat(contract.sources()).hasSize(1);
        assertThat(contract.consistencyAsOf()).isEqualTo(AS_OF);
    }

    @Test
    @DisplayName("AT-071 正向：PARTIAL 仍然出合计（角色只允许看合计，不允许看分来源明细）")
    void partialStillCarriesTheTotal() {
        CrossSourceResultContract contract = disclosable(CrossSourceIntegrity.partial("调用方角色不允许查看分来源明细"));

        assertThat(contract.integrity().state()).isEqualTo(CrossSourceIntegrity.STATE_PARTIAL);
        assertThat(contract.rendersNothing()).isFalse();
        assertThat(contract.totalAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("AT-071 专项二 不可出具：一个数字都不出（合计、来源计数、明细、时间点全空）")
    void withheldResultCarriesNoNumberAtAll() {
        CrossSourceResultContract contract = CrossSourceResultContract.withheld(EXECUTION_KEY, "本次跨源执行未产出可交付的完整结果");

        assertThat(contract.rendersNothing()).isTrue();
        assertThat(contract.integrity().state()).isEqualTo(CrossSourceIntegrity.STATE_WITHHELD);
        // 差额可解这条腿：没有合计，两次相减解不出被禁来源
        assertThat(contract.totalAmount()).isNull();
        // 条数可数这条腿：没有来源计数、也没有来源明细
        assertThat(contract.sourceCount()).isNull();
        assertThat(contract.sources()).isEmpty();
        assertThat(contract.consistencyAsOf()).isNull();
        assertThat(contract.maxSkewMillis()).isNull();
        // 只保留执行键，让调用方知道自己在问哪一次执行
        assertThat(contract.executionKey()).isEqualTo(EXECUTION_KEY);
    }

    @Test
    @DisplayName("AT-071 专项一（反向）：直接 new 且口径为 null 时按 WITHHELD，绝不按 COMPLETE")
    void nullIntegrityIsTreatedAsWithheldNotComplete() {
        // 这是整条验收在服务端的落点：绕过工厂方法、直接用 null 口径构造，
        // 得到的仍必须是一个不出数的响应。若这里是 COMPLETE，前端的
        // "缺失即 WITHHELD" 就只是掩耳盗铃——服务端已经在放行了。
        CrossSourceResultContract contract = new CrossSourceResultContract(
                EXECUTION_KEY, "net_revenue", "CNY", new BigDecimal("100.00"), 1, sources(), AS_OF, 0L, true, null);

        assertThat(contract.integrity().state()).isEqualTo(CrossSourceIntegrity.STATE_WITHHELD);
        assertThat(contract.integrity().reason()).isEqualTo(CrossSourceIntegrity.MISSING_REASON);
        assertThat(contract.rendersNothing()).isTrue();
        assertThat(contract.totalAmount()).isNull();
        assertThat(contract.sourceCount()).isNull();
        assertThat(contract.sources()).isEmpty();
    }

    @Test
    @DisplayName("AT-071 专项一（反向）：缺省工厂 missingIntegrity() 同样出 WITHHELD")
    void missingIntegrityFactoryEmitsWithheld() {
        CrossSourceResultContract contract = CrossSourceResultContract.missingIntegrity(EXECUTION_KEY);

        assertThat(contract.integrity().state()).isEqualTo(CrossSourceIntegrity.STATE_WITHHELD);
        assertThat(contract.integrity().reason()).isEqualTo(CrossSourceIntegrity.MISSING_REASON);
        assertThat(contract.totalAmount()).isNull();
        assertThat(contract.sourceCount()).isNull();
    }

    @Test
    @DisplayName("AT-071 专项一（反向）：未知状态按 WITHHELD，不降级成 COMPLETE")
    void unknownStateNeverBecomesComplete() {
        CrossSourceIntegrity integrity = new CrossSourceIntegrity("SUPER_VISIBLE", "看起来你有权");

        assertThat(integrity.state()).isEqualTo(CrossSourceIntegrity.STATE_WITHHELD);
        assertThat(integrity.disclosesAll()).isFalse();
    }

    @Test
    @DisplayName("AT-071 专项一（反向）：COMPLETE 不得携带理由（放行没有理由可编）")
    void completeNeverCarriesAReason() {
        // 前端排他联合里 COMPLETE 变体没有 reason 字段可填；
        // 服务端若带出去，两侧对不上会让前端解析整块失败。
        CrossSourceIntegrity integrity = new CrossSourceIntegrity(CrossSourceIntegrity.STATE_COMPLETE, "随便编一个理由");

        assertThat(integrity.reason()).isNull();
        assertThat(integrity.disclosesAll()).isTrue();
    }

    @Test
    @DisplayName("AT-071 专项一（反向）：非 COMPLETE 缺理由时退回静态文案（不留空）")
    void nonCompleteAlwaysCarriesAReason() {
        assertThat(new CrossSourceIntegrity(CrossSourceIntegrity.STATE_WITHHELD, null).reason())
                .isEqualTo(CrossSourceIntegrity.MISSING_REASON);
        assertThat(new CrossSourceIntegrity(CrossSourceIntegrity.STATE_PARTIAL, "  ").reason())
                .isEqualTo(CrossSourceIntegrity.MISSING_REASON);
    }

    @Test
    @DisplayName("AT-071 专项一（反向）：空白状态按 WITHHELD 归一，不按 COMPLETE 归一")
    void blankStateIsNormalisedToWithheld() {
        assertThat(new CrossSourceIntegrity("  ", null).state()).isEqualTo(CrossSourceIntegrity.STATE_WITHHELD);
    }

    @Test
    @DisplayName("契约不可变：来源明细按不可变副本持有")
    void sourcesAreDefensivelyCopied() {
        List<SourceAmount> mutable = new ArrayList<>(sources());

        CrossSourceResultContract contract = disclosable(CrossSourceIntegrity.complete());
        mutable.clear();

        assertThat(contract.sources()).hasSize(1);
    }

    @Test
    @DisplayName("口径状态大小写与空白归一（前后端字面量逐字一致，不做隐式容错）")
    void stateIsTrimmedAndUpperCased() {
        assertThat(new CrossSourceIntegrity(" complete ", null).state()).isEqualTo(CrossSourceIntegrity.STATE_COMPLETE);
        assertThat(new CrossSourceIntegrity("partIAL", "只能看合计").state()).isEqualTo(CrossSourceIntegrity.STATE_PARTIAL);
    }

    @Test
    @DisplayName("AT-071 专项一（反向）：状态字面量本身为 null 时也归一到 WITHHELD（最容易被漏的一条分支）")
    void nullStateLiteralIsNormalisedToWithheld() {
        // 这是整条 fail-closed 链条上最底层的兜底：连状态字面量都没给。
        // 归一目标只能是 WITHHELD——任何"没给状态就当放行"的默认值
        // 都会让一次字段遗漏直接变成一次越权披露。
        CrossSourceIntegrity integrity = new CrossSourceIntegrity(null, "看起来你有权");

        assertThat(integrity.state()).isEqualTo(CrossSourceIntegrity.STATE_WITHHELD);
        assertThat(integrity.disclosesAll()).isFalse();
        assertThat(integrity.rendersNothing()).isTrue();
        // 归一后带上了原 reason：状态被降级，但调用方仍知道发生了什么
        assertThat(integrity.reason()).isEqualTo("看起来你有权");
    }
}
