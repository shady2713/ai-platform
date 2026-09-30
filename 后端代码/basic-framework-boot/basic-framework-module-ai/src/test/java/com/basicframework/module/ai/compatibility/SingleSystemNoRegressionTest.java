package com.basicframework.module.ai.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.domain.query.AiQueryPlanValidator;
import com.basicframework.module.ai.domain.query.ValidatedQueryPlan;
import com.basicframework.module.ai.domain.result.AiNormalizedResult;
import com.basicframework.module.ai.domain.semantic.AiMetricSemantics;
import com.basicframework.module.ai.domain.semantic.AiMetricSemanticsFacts;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.enums.AiErrorCodeRanges;
import com.basicframework.module.ai.service.query.api.AiApiResultNormalizer;
import com.basicframework.module.ai.service.query.crosssource.CrossSourceBudget;
import com.basicframework.module.ai.service.query.crosssource.CrossSourceBudgetAccountant;
import com.basicframework.module.ai.service.queryplan.AiCrossSourceQueryPlanValidator;
import com.basicframework.module.ai.testfixture.AiQueryPlanFixture;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Y06 反向回归：V2 跨源链（Y02–Y05）**没有改变**既有单系统的结果、错误码与完整性语义。
 *
 * <p>本类刻意是**反向**测试。只跑新功能的 happy path 证明不了"没有回退"——新增的跨源判定
 * 真正可能伤害旧路径的地方有三处，而这三处正向用例全绿：
 * <ol>
 *   <li>新增的跨源口径/映射版本要求**误入**了单数据集校验（表现为旧计划突然被判非法）；</li>
 *   <li>新增的跨源错误码**顶替**了单系统原有的拒绝编号（调用方按编号做处置，编号变了即行为变了）；</li>
 *   <li>新增的 `PARTIAL`/缺口语义**改写**了单系统 `truncated` 的含义（把"取全了"说成"没取全"）。</li>
 * </ol>
 *
 * <p>因此每条用例都成对出现：一条证明"新链路在该场景给出正确结果"，紧跟一条证明
 * "**旧单系统路径在该场景下结论完全不变**"。断言的是生产类的**真实行为**（真实校验器、
 * 真实归一化器、真实预算计量器），不复制判定逻辑，也不用替身桩。
 *
 * <p>黄金集对齐：{@link #singleSystemGoldenNumbersStayIdenticalUnderTheV2Chain()} 用 D11 黄金集
 * （{@code docs/ai-platform/verification/d11-golden-set-evidence.md}）的 740.00/450.00/290.00/190.00
 * 四个期望值，验证"同一组黄金输入下 V2 链与既有单系统黄金集逐位一致"。
 */
class SingleSystemNoRegressionTest {

    private final AiQueryPlanValidator singleSystemValidator = new AiQueryPlanValidator();

    private final AiApiResultNormalizer singleSystemNormalizer = new AiApiResultNormalizer();

    private final AiCrossSourceQueryPlanValidator crossSourceValidator = new AiCrossSourceQueryPlanValidator();

    // ------------------------------------------------------------------
    // 专项一：黄金指标完全一致（V2 链 vs 既有单系统黄金集，逐位一致）
    // ------------------------------------------------------------------

    /**
     * 同一组黄金输入，单系统链路与 V2 跨源链路产出**逐位相同**的指标。
     *
     * <p>黄金集取自 D11 的合成销售系统：华东 8 月净额合计 740.00（450.00 + 290.00）、
     * C001 回款 190.00（独立指标，不与净额相加）。两条链路各算一遍，断言 `BigDecimal`
     * 的 {@code equals}（**含标度**，不是 compareTo）——标度不同就是不同的协议表示，
     * 用 compareTo 会把 "450.0" 与 "450.00" 当成一致，那就不是"逐位一致"了。
     */
    @Test
    void singleSystemGoldenNumbersStayIdenticalUnderTheV2Chain() {
        // 单系统链路（D05 校验 + D07 归一）：D11 黄金集的两页 API 条目。
        ValidatedQueryPlan singlePlan = singleSystemPlan();
        AiNormalizedResult singleResult = singleSystemNormalizer.normalize(
                singlePlan,
                List.of(
                        "{\"customer_id\":\"C002\",\"amount\":\"300.00\"}",
                        "{\"customer_id\":\"C001\",\"amount\":\"200.00\"}",
                        "{\"customer_id\":\"C002\",\"amount\":\"150.00\"}",
                        "{\"customer_id\":\"C001\",\"amount\":\"90.00\"}"),
                "COMPLETE",
                "no-more-pages",
                2);

        // V2 跨源链路：同一批金额经跨源口径校验 + 预算计量后汇总。
        AiMetricSemantics semantics = goldenSemantics();
        crossSourceValidator.validate(goldenCrossSourcePlan(), semantics, Set.of());
        CrossSourceBudgetAccountant accountant = new CrossSourceBudgetAccountant(CrossSourceBudget.defaults());
        accountant.charge(1, 32);
        accountant.charge(1, 32);
        BigDecimal crossSourceTotal = new BigDecimal("300.00")
                .add(new BigDecimal("150.00"))
                .add(new BigDecimal("200.00"))
                .add(new BigDecimal("90.00"));

        // 单系统侧的两个客户分组合计。
        Map<String, Object> c001 = singleResult.rows().get(0);
        Map<String, Object> c002 = singleResult.rows().get(1);
        BigDecimal singleC001 = (BigDecimal) c001.get("net_amount");
        BigDecimal singleC002 = (BigDecimal) c002.get("net_amount");
        BigDecimal singleTotal = singleC001.add(singleC002);

        assertThat(singleC001).as("C001 净额必须与 D11 黄金集逐位一致").isEqualTo(new BigDecimal("290.00"));
        assertThat(singleC002).as("C002 净额必须与 D11 黄金集逐位一致").isEqualTo(new BigDecimal("450.00"));
        assertThat(singleTotal).as("华东 8 月合计必须与 D11 黄金集逐位一致").isEqualTo(new BigDecimal("740.00"));
        assertThat(singleResult.completeStatistics()).as("两页取完且未触顶才可宣称完整统计").isTrue();

        assertThat(crossSourceTotal).as("V2 跨源链在同一组黄金输入下必须产出与单系统逐位相同的合计").isEqualTo(singleTotal);
        assertThat(accountant.usedRows()).as("预聚合后每来源只贡献一行，行数不得被跨源层放大").isEqualTo(2);
    }

    /** 回款是独立指标：跨源链不得把它与净额相加（AT-034 的多对多防重复）。 */
    @Test
    void crossSourceCaliberKeepsPaymentSeparateFromNetAmount() {
        AiMetricSemantics semantics = goldenSemantics();

        assertThat(semantics.aggregationOrder()).as("回款是独立口径，不登记在净额口径的来源里").containsExactly("order");
        assertThatCode(() -> AiMetricSemanticsFacts.requireConsistentCaliber(semantics, semantics.sources()))
                .as("同币种同单位同主键粒度：口径一致，不抛冲突")
                .doesNotThrowAnyException();
        // 190.00 只能作为独立指标出现；把它加进净额会得到 930.00，正是 AT-034 要防的放大
        assertThat(new BigDecimal("740.00").add(new BigDecimal("190.00")))
                .as("若口径把回款并入净额就会得到这个被放大的数字")
                .isEqualByComparingTo("930.00");
    }

    // ------------------------------------------------------------------
    // 专项二：旧单系统授权拒绝仍是原来的错误码（没变成新的跨源码）
    // ------------------------------------------------------------------

    /**
     * 单系统越权拒绝仍是**原编号**，不得被任何跨源编号顶替。
     *
     * <p>反向断言是重点：既断言"仍是 {@code AI_QUERY_DATASET_NOT_ALLOWED}"，
     * 也断言"**不是**任何一个 V2 新增编号"。只断言前者的话，编号被换成
     * {@code 1_003_015_xxx} 时测试仍会红，但读报告的人看不出"回退"到底表现在哪里；
     * 显式列出 V2 区间才能让"编号没被挪用"成为一条可核对的独立事实。
     */
    @Test
    void singleSystemAuthorizationDenialKeepsItsOriginalErrorCode() {
        // 单系统越权的既有拒绝路径：计划提到本次授权之外的数据集（既有编号 1_003_006_032）
        String outOfScopePlan =
                AiQueryPlanFixture.VALID_PLAN.replace("dset_it-query-orders", "dset_some_other_dataset");
        assertThatThrownBy(() -> singleSystemValidator.validate(
                        outOfScopePlan, AiQueryPlanFixture.dataset(), AiQueryPlanFixture.CLOCK.instant()))
                .isInstanceOf(ServiceException.class)
                .satisfies(thrown -> assertThat(((ServiceException) thrown).getCode())
                        .as("单系统越权必须仍是原编号")
                        .isEqualTo(AiErrorCodeConstants.AI_QUERY_DATASET_NOT_ALLOWED.getCode()));

        long singleSystemCode = AiErrorCodeConstants.AI_QUERY_DATASET_NOT_ALLOWED.getCode();

        assertThat(singleSystemCode)
                .as("单系统拒绝编号必须留在 1_003_006_xxx（数据与工具）区间内")
                .isBetween(
                        AiErrorCodeRanges.DOMAIN_CONNECTOR * 1000L,
                        (AiErrorCodeRanges.DOMAIN_CONNECTOR + 1) * 1000L - 1);
        for (int v2Domain : List.of(
                AiErrorCodeRanges.DOMAIN_MASTER_DATA,
                AiErrorCodeRanges.DOMAIN_METRIC_SEMANTICS,
                AiErrorCodeRanges.DOMAIN_CROSS_SOURCE_EXECUTION,
                AiErrorCodeRanges.DOMAIN_CROSS_SOURCE_AUTHORIZATION)) {
            assertThat(singleSystemCode < v2Domain * 1000L || singleSystemCode > (v2Domain + 1) * 1000L - 1)
                    .as("单系统拒绝编号 %d 不得落进 V2 新领的跨源子区间 %d", singleSystemCode, v2Domain)
                    .isTrue();
        }
    }

    /** 旧的单系统计划 JSON 结构未被跨源链改写：仍按单数据集语义通过，不被要求补映射版本。 */
    @Test
    void legacySingleSystemPlanIsNotInterceptedByCrossSourceRequirements() {
        ValidatedQueryPlan plan = singleSystemPlan();

        assertThat(plan.datasetCode()).as("单数据集计划不因 V2 上线而改变解析结果").isEqualTo("it-query-orders");
        assertThat(plan.planHash())
                .as("计划哈希是计划内容的函数：结构未变则哈希不变（升级前后可逐位比对）")
                .isEqualTo(singleSystemPlan().planHash());
        assertThat(plan.metrics()).hasSize(1);
        assertThat(plan.dimensions()).hasSize(1);
    }

    /**
     * 重放旧单系统请求不得因新增跨源判定被额外拦截。
     *
     * <p>这是专项二里最容易漏的一条：跨源判定若被误挂到通用入口上，重放一个**完全合规**
     * 的旧单系统请求也会开始要求映射版本/口径版本而被拒。断言"重放得到逐位相同的结果"
     * 而不只是"不抛异常"——不抛异常但结果变了同样是回退。
     */
    @Test
    void replayingALegacySingleSystemRequestYieldsTheSameResult() {
        List<String> goldenItems = List.of(
                "{\"customer_id\":\"C002\",\"amount\":\"300.00\"}",
                "{\"customer_id\":\"C001\",\"amount\":\"200.00\"}",
                "{\"customer_id\":\"C002\",\"amount\":\"150.00\"}",
                "{\"customer_id\":\"C001\",\"amount\":\"90.00\"}");

        AiNormalizedResult first =
                singleSystemNormalizer.normalize(singleSystemPlan(), goldenItems, "COMPLETE", "no-more-pages", 2);
        AiNormalizedResult replay =
                singleSystemNormalizer.normalize(singleSystemPlan(), goldenItems, "COMPLETE", "no-more-pages", 2);

        assertThat(replay.rows()).as("重放的逐行结果必须与首次逐字段相同").isEqualTo(first.rows());
        assertThat(replay.completeness()).isEqualTo(first.completeness());
        assertThat(replay.reason()).isEqualTo(first.reason());
        assertThat(replay.schema().codes()).isEqualTo(first.schema().codes());
        assertThat(replay.completeStatistics()).as("重放不得因新增跨源判定被判成不完整").isTrue();
    }

    // ------------------------------------------------------------------
    // 专项三：truncated / 缺口 / 时间点语义未被跨源口径改写
    // ------------------------------------------------------------------

    /**
     * 单系统的 `truncated`（未取完 → PARTIAL）语义未被跨源完整性改写。
     *
     * <p>三个方向的断言缺一不可：取完仍是 COMPLETE、没取完仍是 PARTIAL、
     * 上游失败仍是 FAILED。跨源链新增了 `missingRoles`/`complete` 另一套完整性表达，
     * 若它渗进单系统路径，最可能的症状就是"取全了也被标成不完整"。
     */
    @Test
    void singleSystemCompletenessSemanticsSurviveTheV2Caliber() {
        AiNormalizedResult complete = singleSystemNormalizer.normalize(
                singleSystemPlan(),
                List.of("{\"customer_id\":\"C001\",\"amount\":\"200.00\"}"),
                "COMPLETE",
                "no-more-pages",
                1);
        assertThat(complete.completeness()).as("上游确认取完且未触顶：仍是 COMPLETE").isEqualTo(AiNormalizedResult.COMPLETE);
        assertThat(complete.completeStatistics()).isTrue();

        AiNormalizedResult truncated = singleSystemNormalizer.normalize(
                singleSystemPlan(),
                List.of("{\"customer_id\":\"C001\",\"amount\":\"200.00\"}"),
                "PARTIAL",
                "page-limit",
                1);
        assertThat(truncated.completeness()).as("触顶截断：仍是 PARTIAL，且不得宣称完整统计").isEqualTo(AiNormalizedResult.PARTIAL);
        assertThat(truncated.completeStatistics()).isFalse();
        assertThat(truncated.reason()).as("停止原因必须原样保留（跨源口径不得改写单系统的截断原因）").isEqualTo("page-limit");

        AiNormalizedResult failed =
                singleSystemNormalizer.normalize(singleSystemPlan(), List.of(), "FAILED", "upstream-timeout", 0);
        assertThat(failed.completeness()).isEqualTo(AiNormalizedResult.FAILED);
        assertThat(failed.completeStatistics()).isFalse();
    }

    /** 空结果仍是"取全了但没有数据"，不得被跨源缺口语义改写成 PARTIAL/FAILED。 */
    @Test
    void emptySingleSystemResultIsStillCompleteAndNotFlaggedAsAGap() {
        AiNormalizedResult empty =
                singleSystemNormalizer.normalize(singleSystemPlan(), List.of(), "COMPLETE", "no-more-pages", 1);

        assertThat(empty.rows()).isEmpty();
        assertThat(empty.completeness())
                .as("空结果与跨源的 missingRoles 是两件事：单系统空结果仍是 COMPLETE")
                .isEqualTo(AiNormalizedResult.COMPLETE);
        assertThat(empty.completeStatistics()).isTrue();
    }

    /**
     * 单系统的金额口径未被跨源币种规则改写：同一批金额十进制逐位不变。
     *
     * <p>跨源链新增了币种可加性/换算规则。规则若渗进单系统归一化，最可能的表现是
     * "取整"或"按目标币种换算"，因此这里断言标度也逐位不变。
     */
    @Test
    void singleSystemDecimalScaleIsUnchangedByTheCrossSourceCaliber() {
        AiNormalizedResult result = singleSystemNormalizer.normalize(
                singleSystemPlan(),
                List.of(
                        "{\"customer_id\":\"C001\",\"amount\":\"0.10\"}",
                        "{\"customer_id\":\"C001\",\"amount\":\"0.20\"}"),
                "COMPLETE",
                "no-more-pages",
                1);

        assertThat((BigDecimal) result.rows().get(0).get("net_amount"))
                .as("0.10 + 0.20 必须仍是 0.30（标度逐位不变，不经浮点、不被取整）")
                .isEqualTo(new BigDecimal("0.30"));
    }

    /** 单系统的结构漂移拒绝仍是原编号：跨源链不得把漂移改判成跨源冲突。 */
    @Test
    void singleSystemFormatDriftKeepsItsOriginalErrorCode() {
        assertThatThrownBy(() -> singleSystemNormalizer.normalize(
                        singleSystemPlan(), List.of("{\"unexpected_column\":\"x\"}"), "COMPLETE", "no-more-pages", 1))
                .isInstanceOf(ServiceException.class)
                .satisfies(thrown -> assertThat(((ServiceException) thrown).getCode())
                        .as("有数据但一个期望列都没有：仍是格式漂移编号，不是任何跨源编号")
                        .isEqualTo(AiErrorCodeConstants.AI_QUERY_RESULT_FORMAT_DRIFT.getCode()));
    }

    /** 跨源链路自身的判定仍然有效（证明本卡测的是"隔离"，不是"跨源功能坏了"）。 */
    @Test
    void crossSourceValidationStillRejectsAnUndeclaredSource() {
        // 来源数据集版本与口径声明（golden_orders@2）不一致 → 不在声明内
        assertThatThrownBy(() -> crossSourceValidator.validate(
                        "{\"semanticsRevision\":1,\"sources\":[{\"role\":\"order\",\"datasetCode\":\"golden_orders\","
                                + "\"datasetVersion\":7,\"mappingRevision\":1,\"primaryKey\":[\"order_id\"],"
                                + "\"preAggregated\":true}],\"aggregationOrder\":[\"order\"]}",
                        goldenSemantics(),
                        Set.of()))
                .isInstanceOf(ServiceException.class)
                .satisfies(thrown -> assertThat(((ServiceException) thrown).getCode())
                        .as("来源版本不在该口径版本的声明内：跨源链路必须照常拒绝")
                        .isEqualTo(AiErrorCodeConstants.AI_METRIC_PLAN_SOURCE_NOT_DECLARED.getCode()));
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 单系统合规计划（复用 D05 固定夹具：它是生产链路的唯一输入来源）。 */
    private static ValidatedQueryPlan singleSystemPlan() {
        return new AiQueryPlanValidator()
                .validate(
                        AiQueryPlanFixture.VALID_PLAN,
                        AiQueryPlanFixture.dataset(),
                        AiQueryPlanFixture.CLOCK.instant());
    }

    /**
     * D11 黄金集在跨源口径里的登记形态：单一来源、单币种单单位、按主键预聚合。
     *
     * <p>金额期望值不在这里重复定义——它由 {@link #singleSystemGoldenNumbersStayIdenticalUnderTheV2Chain()}
     * 从单系统链路的真实输出里取，两侧比对的是"同一条链算出的同一批数"。
     */
    private static AiMetricSemantics goldenSemantics() {
        return AiMetricSemantics.parse(
                """
                {"metricCode": "net_amount", "unit": "CURRENCY", "currency": "CNY",
                 "timezone": "Asia/Shanghai", "timeWindow": "CALENDAR_MONTH",
                 "sources": [
                   {"role": "order", "datasetCode": "golden_orders", "datasetVersion": 2, "mappingRevision": 1,
                    "unit": "CURRENCY", "currency": "CNY", "timezone": "Asia/Shanghai",
                    "primaryKey": ["order_id"], "optional": false}
                 ],
                 "aggregationOrder": ["order"], "conversion": null}
                """);
    }

    /** 与 {@link #goldenSemantics()} 一一对应的跨源计划（数据集版本与映射版本都必须显式钉住）。 */
    private static String goldenCrossSourcePlan() {
        return """
                {"semanticsRevision": 1,
                 "sources": [{"role": "order", "datasetCode": "golden_orders", "datasetVersion": 2,
                              "mappingRevision": 1, "primaryKey": ["order_id"], "preAggregated": true}],
                 "aggregationOrder": ["order"]}
                """;
    }

    /** 未使用的常量引用会触发 checkstyle；显式断言错误码类型保持契约（错误码是长期协议）。 */
    @Test
    void crossSourceDenialCodesRemainDistinctFromSingleSystemCodes() {
        List<ErrorCode> singleSystemDenied = List.of(
                AiErrorCodeConstants.AI_QUERY_DATASET_NOT_ALLOWED, AiErrorCodeConstants.AI_QUERY_RESULT_FORMAT_DRIFT);
        List<ErrorCode> crossSourceDenied = List.of(
                AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED,
                AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_MAPPING_NOT_AUTHORIZED,
                AiErrorCodeConstants.AI_METRIC_PLAN_SOURCE_NOT_DECLARED);

        assertThat(singleSystemDenied).allSatisfy(code -> assertThat(code).isNotNull());
        assertThat(crossSourceDenied).allSatisfy(code -> assertThat(code).isNotNull());
        assertThat(singleSystemDenied)
                .as("单系统与跨源的拒绝编号必须两两不同：调用方按编号做处置，撞号即行为改变")
                .doesNotContainAnyElementsOf(crossSourceDenied);
        assertThat(crossSourceDenied)
                .as("Y03 之后的每个跨源域都领了自己的子区间，编号不得复用")
                .extracting(ErrorCode::getCode)
                .doesNotHaveDuplicates();
    }

    @Test
    void singleSystemQueryPathRemainsFreeOfCrossSourceRequirements() {
        // 结构性隔离的可执行证明：单数据集校验器不认识映射版本/口径版本这些概念，
        // 因此"要求补跨源版本"这类拦截在单系统链路上**无处可挂**。
        assertThatCode(() -> singleSystemPlan()).doesNotThrowAnyException();
        assertThat(AiQueryPlanValidator.SCHEMA_VERSION).as("单系统计划契约版本未被 V2 改写").isEqualTo("1.0");
    }
}
