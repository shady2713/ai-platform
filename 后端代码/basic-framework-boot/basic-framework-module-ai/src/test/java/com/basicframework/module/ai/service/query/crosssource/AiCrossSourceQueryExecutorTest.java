package com.basicframework.module.ai.service.query.crosssource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.crosssource.AiCrossSourceExecutionDO;
import com.basicframework.module.ai.dal.dataobject.crosssource.AiCrossSourceExecutionSourceDO;
import com.basicframework.module.ai.dal.mysql.crosssource.AiCrossSourceExecutionMapper;
import com.basicframework.module.ai.dal.mysql.crosssource.AiCrossSourceSourceContributionMapper;
import com.basicframework.module.ai.domain.query.CompiledQuery;
import com.basicframework.module.ai.domain.query.SqlParameter;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.queryplan.CrossSourceQueryPlan;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 跨源执行器的编排语义（Y04 专项二与三：时间点偏移可见、重试不重复汇总）。
 *
 * <p>这里用受控的取数替身驱动执行器，把三条不变量钉在**编排层**：
 * 预聚合后按版本化实体键求和、合计只由"已计入的行"得到、必需来源失败整体受控结束。
 * 真实 MySQL 上的端到端行为由 {@code AiCrossSourceExecutionAcceptanceIT} 覆盖。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiCrossSourceQueryExecutorTest {

    private static final LocalDateTime SAME_TIME = LocalDateTime.of(2026, 9, 20, 10, 0);
    private static final LocalDateTime EARLY_TIME = LocalDateTime.of(2026, 9, 20, 8, 0);
    private static final long ONE_MB = 1024L * 1024L;

    @Mock
    private CrossSourceSourceFetcher fetcher;

    @Mock
    private AiCrossSourceExecutionMapper executionMapper;

    @Mock
    private AiCrossSourceSourceContributionMapper contributionMapper;

    private AiCrossSourceQueryExecutor executor;

    private final AtomicInteger generatedIds = new AtomicInteger(100);

    @BeforeEach
    void setUp() {
        executor = new AiCrossSourceQueryExecutor(fetcher, executionMapper, contributionMapper);
        when(executionMapper.insert(any(AiCrossSourceExecutionDO.class))).thenAnswer(invocation -> {
            AiCrossSourceExecutionDO execution = invocation.getArgument(0);
            execution.setId((long) generatedIds.incrementAndGet());
            return 1;
        });
        // 终态 CAS 默认命中（未命中路径单独构造）
        when(executionMapper.updateStatusWithVersion(
                        anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                        anyInt()))
                .thenReturn(1);
    }

    @Test
    void sumsPreAggregatedAmountsAndReportsTheEarliestSourceTimePoint() {
        // 专项二：各源数据时间点不同，结果的一致性时间点取最小值（唯一"都成立"的时刻）
        when(fetcher.fetch(any(), any()))
                .thenReturn(rows("C-1", "100.00", SAME_TIME))
                .thenReturn(rows("C-1", "25.00", EARLY_TIME));
        givenCountedSources(counted(1L, "invoice", "25.00", EARLY_TIME), counted(1L, "order", "100.00", SAME_TIME));

        // 容忍 3 小时：2 小时偏移在窗口内，放行并把偏移如实写进结果
        CrossSourceExecutionResult result = executor.execute(
                plan(), List.of(request("invoice", false), request("order", false)), budget(10_800), "exec-1");

        assertThat(result.totalAmount()).isEqualByComparingTo("125.00");
        assertThat(result.consistencyAsOf()).isEqualTo(EARLY_TIME);
        assertThat(result.maxSkewMillis()).isEqualTo(2 * 60 * 60 * 1000L);
        assertThat(result.skew()).isEqualTo(java.time.Duration.ofHours(2));
        assertThat(result.complete()).isTrue();
        assertThat(result.usable()).isTrue();
        assertThat(result.describe()).contains("total=125.00", "skew=7200000ms");
    }

    @Test
    void refusesToSumWhenSourceTimePointsAreTooFarApart() {
        // 偏移 2 小时、容忍 60 秒：跨源合计失去意义，受控结束而不是假装同一时刻
        when(fetcher.fetch(any(), any()))
                .thenReturn(rows("C-1", "100.00", SAME_TIME))
                .thenReturn(rows("C-1", "25.00", EARLY_TIME));
        givenCountedSources(counted(1L, "invoice", "25.00", EARLY_TIME), counted(1L, "order", "100.00", SAME_TIME));

        assertCode(
                () -> executor.execute(
                        plan(), List.of(request("invoice", false), request("order", false)), budget(60), "exec-1"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_CONSISTENCY_SKEW_CONFLICT);
    }

    @Test
    void refusesTruncatedSourceResultsInsteadOfSummingIncompleteData() {
        // 源内已预聚合的 SUM 被截断后仍是"合法数字"但偏小：宁可阻断也不给偏小的合计
        when(fetcher.fetch(any(), any()))
                .thenReturn(new CrossSourceSourceFetcher.FetchedRows(
                        List.of(new CrossSourceSourceFetcher.PreAggregatedRow(
                                new CrossSourceEntityKey("C-1", 1L), new BigDecimal("100.00"))),
                        true,
                        100,
                        SAME_TIME,
                        5));

        assertCode(
                () -> executor.execute(plan(), List.of(request("order", false)), budget(60), "exec-1"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_RESULT_TRUNCATED_CONFLICT);
    }

    @Test
    void refusesWhenBudgetIsExceededBeforeTheAmountIsCounted() {
        // 专项一：预算越界在计入之前发生，因此受控结束后没有部分写入的合计
        when(fetcher.fetch(any(), any()))
                .thenReturn(new CrossSourceSourceFetcher.FetchedRows(
                        List.of(new CrossSourceSourceFetcher.PreAggregatedRow(
                                new CrossSourceEntityKey("C-1", 1L), new BigDecimal("100.00"))),
                        false,
                        4 * ONE_MB,
                        SAME_TIME,
                        5));

        assertCode(
                () -> executor.execute(plan(), List.of(request("order", false)), budget(60), "exec-1"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_RESULT_TOO_LARGE);
        // 越界发生在**计入之前**：没有任何来源行进入 COUNTED（金额没有进合计），
        // 只留一行 FAILED 标记说明是哪个来源坏了
        verify(contributionMapper, times(0))
                .insert(argThat((AiCrossSourceExecutionSourceDO row) ->
                        AiCrossSourceExecutionSourceDO.STATUS_COUNTED.equals(row.getStatus())));
    }

    @Test
    void aFailingRequiredSourceEndsTheWholeExecutionAndKeepsTheActionableCause() {
        when(fetcher.fetch(any(), any()))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_CROSS_SOURCE_SOURCE_FAILED_CONFLICT));

        assertCode(
                () -> executor.execute(plan(), List.of(request("order", false)), budget(60), "exec-1"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_SOURCE_FAILED_CONFLICT);
        // 跨源侧的信息写进台账：标出是哪个必需来源坏了
        verify(contributionMapper)
                .insert(argThat((AiCrossSourceExecutionSourceDO row) ->
                        AiCrossSourceExecutionSourceDO.STATUS_FAILED.equals(row.getStatus())));
        // 受控结束落 FAILED 终态并带稳定失败编号
        verify(executionMapper)
                .updateStatusWithVersion(
                        anyLong(),
                        eq(AiCrossSourceExecutionDO.STATUS_RUNNING),
                        eq(AiCrossSourceExecutionDO.STATUS_FAILED),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(AiErrorCodeConstants.AI_CROSS_SOURCE_SOURCE_FAILED_CONFLICT.getCode()),
                        anyInt());
    }

    @Test
    void aFailingRequiredSourceRethrowsTheUpstreamCauseInsteadOfMaskingIt() {
        // 包一层会把"对象未授权 / 连接不可达"这类可操作信息抹掉：跨源层不吞掉原因
        when(fetcher.fetch(any(), any()))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_CONNECTOR_QUERY_FAILED));

        assertCode(
                () -> executor.execute(plan(), List.of(request("order", false)), budget(60), "exec-1"),
                AiErrorCodeConstants.AI_CONNECTOR_QUERY_FAILED);
    }

    @Test
    void aFailingOptionalSourceIsRecordedAsMissingRatherThanZero() {
        when(fetcher.fetch(any(), any()))
                .thenReturn(rows("C-1", "100.00", SAME_TIME))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_CROSS_SOURCE_SOURCE_FAILED_CONFLICT));
        givenCountedSources(counted(1L, "order", "100.00", SAME_TIME));

        CrossSourceExecutionResult result = executor.execute(
                plan(), List.of(request("order", false), request("payment", true)), budget(60), "exec-1");

        assertThat(result.missingRoles()).containsExactly("payment");
        assertThat(result.complete()).isFalse();
        // 缺失不是 0：合计只有实际取到的那个来源
        assertThat(result.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(result.usable()).isFalse();
    }

    @Test
    void aRetryOverwritesTheSourceRowInsteadOfAddingASecondContribution() {
        // 专项三：同一来源重取时走 CAS 覆盖（amount 赋值），库里始终只有一份金额
        when(fetcher.fetch(any(), any())).thenReturn(rows("C-1", "100.00", SAME_TIME));
        AiCrossSourceExecutionSourceDO existing = counted(1L, "order", "100.00", SAME_TIME);
        existing.setVersion(3);
        when(contributionMapper.selectByRole(anyLong(), eq("order"))).thenReturn(existing);
        when(contributionMapper.overwriteCountedWithVersion(
                        anyLong(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(1);
        givenCountedSources(existing);

        CrossSourceExecutionResult result =
                executor.execute(plan(), List.of(request("order", false)), budget(60), "exec-1");

        verify(contributionMapper, times(0)).insert(any(AiCrossSourceExecutionSourceDO.class));
        verify(contributionMapper)
                .overwriteCountedWithVersion(
                        anyLong(), eq("order"), eq(new BigDecimal("100.00")), any(), any(), any(), any(), eq(3));
        assertThat(result.totalAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void aRetryThatLostTheOptimisticLockDoesNotCreateASecondContribution() {
        // 并发重试：CAS 返回 0 且重读仍是 COUNTED → 视为同一份贡献，绝不再插一行
        when(fetcher.fetch(any(), any())).thenReturn(rows("C-1", "100.00", SAME_TIME));
        AiCrossSourceExecutionSourceDO existing = counted(1L, "order", "100.00", SAME_TIME);
        when(contributionMapper.selectByRole(anyLong(), eq("order"))).thenReturn(existing);
        when(contributionMapper.overwriteCountedWithVersion(
                        anyLong(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(0);
        givenCountedSources(existing);

        CrossSourceExecutionResult result =
                executor.execute(plan(), List.of(request("order", false)), budget(60), "exec-1");

        verify(contributionMapper, times(0)).insert(any(AiCrossSourceExecutionSourceDO.class));
        assertThat(result.totalAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void aRetryThatLostTheLockOnANonCountedRowIsRefused() {
        when(fetcher.fetch(any(), any())).thenReturn(rows("C-1", "100.00", SAME_TIME));
        AiCrossSourceExecutionSourceDO missing =
                counted(1L, "order", "100.00", SAME_TIME).setStatus(AiCrossSourceExecutionSourceDO.STATUS_MISSING);
        when(contributionMapper.selectByRole(anyLong(), eq("order"))).thenReturn(missing);
        when(contributionMapper.overwriteCountedWithVersion(
                        anyLong(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(0);
        givenCountedSources(missing);

        assertCode(
                () -> executor.execute(plan(), List.of(request("order", false)), budget(60), "exec-1"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ALREADY_COUNTED_CONFLICT);
    }

    @Test
    void refusesTheSameExecutionKeyWithADifferentPlan() {
        when(executionMapper.selectByExecutionKey("exec-1"))
                .thenReturn(
                        new AiCrossSourceExecutionDO().setExecutionKey("exec-1").setPlanHash("other-plan"));

        assertCode(
                () -> executor.execute(plan(), List.of(request("order", false)), budget(60), "exec-1"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_EXECUTION_KEY_CONFLICT);
    }

    @Test
    void allowsRerunningAPartialExecutionBecauseItsResultIsNotUsable() {
        // PARTIAL 的 usable() 为 false，本来就是"还没算完"：补齐缺失来源后原地重跑是它的正常路径
        when(executionMapper.selectByExecutionKey("exec-1"))
                .thenReturn(new AiCrossSourceExecutionDO()
                        .setId(7L)
                        .setExecutionKey("exec-1")
                        .setPlanHash("plan-1")
                        .setStatus(AiCrossSourceExecutionDO.STATUS_PARTIAL)
                        .setVersion(2));
        when(fetcher.fetch(any(), any())).thenReturn(rows("C-1", "100.00", SAME_TIME));
        givenCountedSources(counted(1L, "order", "100.00", SAME_TIME));

        CrossSourceExecutionResult result =
                executor.execute(plan(), List.of(request("order", false)), budget(60), "exec-1");

        assertThat(result.totalAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void refusesToRerunAnExecutionThatAlreadyProducedAResult() {
        // 已出结果的合计是别人正在引用的数字：重算必须换执行键
        when(executionMapper.selectByExecutionKey("exec-1"))
                .thenReturn(new AiCrossSourceExecutionDO()
                        .setExecutionKey("exec-1")
                        .setPlanHash("plan-1")
                        .setStatus(AiCrossSourceExecutionDO.STATUS_SUCCEEDED));

        assertCode(
                () -> executor.execute(plan(), List.of(request("order", false)), budget(60), "exec-1"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_EXECUTION_KEY_CONFLICT);
    }

    @Test
    void allowsRerunningAnExecutionThatWasInterruptedByAControlledTermination() {
        // FAILED 正是重试要处理的状态：允许原地重跑，去重交给台账而不是靠拒绝重试
        when(executionMapper.selectByExecutionKey("exec-1"))
                .thenReturn(new AiCrossSourceExecutionDO()
                        .setId(7L)
                        .setExecutionKey("exec-1")
                        .setPlanHash("plan-1")
                        .setStatus(AiCrossSourceExecutionDO.STATUS_FAILED)
                        .setVersion(1));
        when(fetcher.fetch(any(), any())).thenReturn(rows("C-1", "100.00", SAME_TIME));
        givenCountedSources(counted(1L, "order", "100.00", SAME_TIME));

        CrossSourceExecutionResult result =
                executor.execute(plan(), List.of(request("order", false)), budget(60), "exec-1");

        assertThat(result.totalAmount()).isEqualByComparingTo("100.00");
        // 收尾 CAS 的 fromStatus 用实际读到的 FAILED，而不是写死 RUNNING
        verify(executionMapper)
                .updateStatusWithVersion(
                        eq(7L),
                        eq(AiCrossSourceExecutionDO.STATUS_FAILED),
                        eq(AiCrossSourceExecutionDO.STATUS_SUCCEEDED),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(1));
    }

    @Test
    void refusesAConcurrentFinalizeInsteadOfOverwritingTheWinner() {
        when(fetcher.fetch(any(), any())).thenReturn(rows("C-1", "100.00", SAME_TIME));
        givenCountedSources(counted(1L, "order", "100.00", SAME_TIME));
        when(executionMapper.updateStatusWithVersion(
                        anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                        anyInt()))
                .thenReturn(0);

        assertCode(
                () -> executor.execute(plan(), List.of(request("order", false)), budget(60), "exec-1"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_EXECUTION_KEY_CONFLICT);
    }

    @Test
    void refusesRequestsThatDoNotMatchTheDeclaredPlanSources() {
        assertCode(
                () -> executor.execute(plan(), List.of(request("unknown_role", false)), budget(60), "exec-1"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED);
        // 版本不一致：计划钉住 r1，取数规格却是 r2
        assertCode(
                () -> executor.execute(plan(), List.of(requestWithRevision("order", 2L)), budget(60), "exec-1"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED);
    }

    @Test
    void refusesMissingOrEmptyExecutionInput() {
        assertCode(
                () -> executor.execute(null, List.of(request("order", false)), budget(60), "exec-1"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_EXECUTION_NOT_EXISTS);
        assertCode(
                () -> executor.execute(plan(), List.of(), budget(60), "exec-1"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_EXECUTION_NOT_EXISTS);
        assertCode(
                () -> executor.execute(plan(), List.of(request("order", false)), budget(60), " "),
                AiErrorCodeConstants.AI_CROSS_SOURCE_EXECUTION_NOT_EXISTS);
    }

    // ---- 夹具 ----

    private static CrossSourceBudget budget(int skewSeconds) {
        return new CrossSourceBudget(500, ONE_MB, 2, 5_000, 4 * ONE_MB, skewSeconds);
    }

    private static CrossSourceQueryPlan plan() {
        return new CrossSourceQueryPlan(
                "net_revenue",
                1,
                "caliber-hash",
                List.of(
                        new CrossSourceQueryPlan.SourceSelection(
                                "dset_orders", 2, 1L, "order", List.of("order_id"), true),
                        new CrossSourceQueryPlan.SourceSelection(
                                "dset_invoices", 1, 1L, "invoice", List.of("invoice_id"), true),
                        new CrossSourceQueryPlan.SourceSelection(
                                "dset_payments", 3, 1L, "payment", List.of("payment_id"), true)),
                List.of("order", "invoice", "payment"),
                "CNY",
                "Asia/Shanghai",
                "CALENDAR_MONTH",
                "CURRENCY",
                "plan-1");
    }

    private static CrossSourceSourceRequest request(String role, boolean optional) {
        return new CrossSourceSourceRequest(
                role,
                "dset_" + role,
                1,
                9L,
                new CompiledQuery(
                        "SELECT customer AS entity_key, SUM(amount) AS net_amount, MAX(updated_at) AS source_as_of"
                                + " FROM catalog." + role + " WHERE customer IN (?) GROUP BY customer LIMIT ?",
                        List.of(SqlParameter.string("C-1"), SqlParameter.number(10L)),
                        List.of(new CompiledQuery.ResultColumn("entity_key", "客户", "STRING")),
                        10,
                        5_000,
                        1L,
                        "plan-1",
                        "catalog." + role),
                List.of(new CrossSourceEntityKey("C-1", 1L)),
                1L,
                "entity_key",
                "net_amount",
                new CrossSourceSourceRequest.LocalDateTimeColumn("source_as_of"),
                optional);
    }

    /** 构造一个钉在指定映射版本上的取数规格（record 无 setter，故另起构造）。 */
    private static CrossSourceSourceRequest requestWithRevision(String role, long mappingRevision) {
        return new CrossSourceSourceRequest(
                role,
                "dset_" + role,
                1,
                9L,
                new CompiledQuery(
                        "SELECT customer AS entity_key, SUM(amount) AS net_amount, MAX(updated_at) AS source_as_of"
                                + " FROM catalog." + role + " WHERE customer IN (?) GROUP BY customer LIMIT ?",
                        List.of(SqlParameter.string("C-1"), SqlParameter.number(10L)),
                        List.of(new CompiledQuery.ResultColumn("entity_key", "客户", "STRING")),
                        10,
                        5_000,
                        1L,
                        "plan-1",
                        "catalog." + role),
                List.of(new CrossSourceEntityKey("C-1", mappingRevision)),
                mappingRevision,
                "entity_key",
                "net_amount",
                new CrossSourceSourceRequest.LocalDateTimeColumn("source_as_of"),
                false);
    }

    private static CrossSourceSourceFetcher.FetchedRows rows(String key, String amount, LocalDateTime asOf) {
        return new CrossSourceSourceFetcher.FetchedRows(
                List.of(new CrossSourceSourceFetcher.PreAggregatedRow(
                        new CrossSourceEntityKey(key, 1L), new BigDecimal(amount))),
                false,
                100,
                asOf,
                12);
    }

    private static AiCrossSourceExecutionSourceDO counted(
            Long executionId, String role, String amount, LocalDateTime asOf) {
        return new AiCrossSourceExecutionSourceDO()
                .setId((long) role.hashCode())
                .setExecutionId(executionId)
                .setRole(role)
                .setDatasetCode("dset_" + role)
                .setDatasetVersion(1)
                .setMappingRevision(1L)
                .setStatus(AiCrossSourceExecutionSourceDO.STATUS_COUNTED)
                .setAmount(new BigDecimal(amount))
                .setRowCount(1)
                .setByteSize(100L)
                .setSourceAsOf(asOf)
                .setElapsedMillis(12L)
                .setAttemptCount(1)
                .setVersion(0);
    }

    /** 已计入行由台账决定：合计只从这些行求和（重试不新增行 → 合计与重试次数无关）。 */
    private void givenCountedSources(AiCrossSourceExecutionSourceDO... rows) {
        when(contributionMapper.selectCountedSources(anyLong())).thenReturn(List.of(rows));
    }

    private static void assertCode(
            Runnable operation, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(expected.getCode()));
    }
}
