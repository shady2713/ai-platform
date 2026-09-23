package com.basicframework.module.ai.service.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlQueryResultDTO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetVersionDO;
import com.basicframework.module.ai.domain.query.AiQueryPlanValidator;
import com.basicframework.module.ai.domain.query.QueryScope;
import com.basicframework.module.ai.domain.query.ValidatedQueryPlan;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.query.compiler.AiCompiledQueryExecutor;
import com.basicframework.module.ai.service.query.planner.AiQueryPlanner;
import com.basicframework.module.ai.service.query.planner.dto.AiQueryPlanResultDTO;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionRequestDTO;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionResultDTO;
import com.basicframework.module.ai.testfixture.AiQueryPlanFixture;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R05 受控查询执行：没有行范围不执行、版本不可执行即拒绝、结果按计划投影、澄清原样带回。
 *
 * <p>本套件用固定夹具（D05 的语义定义与计划）驱动：结论必须可复现，不依赖真实模型与网络。
 */
class AiRunQueryExecutionServiceImplTest {

    private static final Long CONNECTOR_ID = 7L;

    private final AiQueryPlanner queryPlanner = mock(AiQueryPlanner.class);

    private final AiDatasetService datasetService = mock(AiDatasetService.class);

    private final AiCompiledQueryExecutor compiledQueryExecutor = mock(AiCompiledQueryExecutor.class);

    private final AiRunQueryExecutionServiceImpl service = new AiRunQueryExecutionServiceImpl(
            queryPlanner, datasetService, compiledQueryExecutor, AiQueryPlanFixture.CLOCK);

    private static AiRunQueryExecutionRequestDTO request() {
        return new AiRunQueryExecutionRequestDTO()
                .setDatasetId(AiQueryPlanFixture.DATASET_ID)
                .setDatasetVersionId(AiQueryPlanFixture.DATASET_VERSION_ID)
                .setEndpointId(5L)
                .setQuestion("按客户看净销售额")
                .setRowScope(QueryScope.eq("region", "EAST"));
    }

    private static AiDatasetDO dataset() {
        return new AiDatasetDO()
                .setId(AiQueryPlanFixture.DATASET_ID)
                .setCode("it-query-orders")
                .setConnectorId(CONNECTOR_ID)
                .setSourceObject("it_query.orders")
                .setStatus(AiDatasetDO.STATUS_ENABLED);
    }

    private static AiDatasetVersionDO version() {
        return new AiDatasetVersionDO()
                .setId(AiQueryPlanFixture.DATASET_VERSION_ID)
                .setDatasetId(AiQueryPlanFixture.DATASET_ID)
                .setVersionNo(1)
                .setStatus(AiDatasetVersionDO.STATUS_PUBLISHED)
                .setVerificationStatus(AiDatasetVersionDO.VERIFICATION_VERIFIED)
                .setDefinitionJson(AiQueryPlanFixture.DEFINITION)
                .setSchemaHash("a".repeat(64));
    }

    private static AiQueryPlanResultDTO plan() {
        return new AiQueryPlanResultDTO()
                .setKind("PLAN")
                .setDatasetId(AiQueryPlanFixture.DATASET_ID)
                .setDatasetCode("it-query-orders")
                .setPlanDatasetId("dset_it-query-orders")
                .setDatasetVersionId(AiQueryPlanFixture.DATASET_VERSION_ID)
                .setDatasetVersionNo(1)
                .setSchemaHash("a".repeat(64))
                .setPlanHash(canonicalPlanHash())
                .setPlanJson(canonicalPlan());
    }

    /**
     * 规划器回传的规范化计划（与 D05 的 {@code planJson} 同形）：逻辑码 + 聚合 + 计划哈希。
     *
     * <p>测试侧按已验证计划的公开访问器重建这份文本，因此"执行前再校验"的哈希比对是真检查：
     * 还原错一个键就得不到同一个计划哈希。
     */
    private static String canonicalPlan() {
        ValidatedQueryPlan plan = validated();
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("datasetId", plan.planDatasetId());
        canonical.put("datasetVersion", plan.datasetVersionNo());
        canonical.put(
                "metrics",
                plan.metrics().stream()
                        .map(metric -> Map.of(
                                "code", metric.code(), "aggregation", metric.aggregation(), "unit", metric.unit()))
                        .toList());
        canonical.put(
                "dimensions",
                plan.dimensions().stream()
                        .map(dimension -> Map.of("code", dimension.code()))
                        .toList());
        canonical.put(
                "filters",
                plan.filters().stream()
                        .map(filter ->
                                Map.of("code", filter.code(), "operator", filter.operator(), "values", filter.values()))
                        .toList());
        canonical.put(
                "timeRange",
                plan.timeWindow() == null
                        ? null
                        : Map.of(
                                "code", plan.timeWindow().code(),
                                "startInclusive", plan.timeWindow().startInclusive(),
                                "endExclusive", plan.timeWindow().endExclusive(),
                                "timezone", plan.timeWindow().timezone()));
        canonical.put(
                "orderBy",
                plan.orderBy().stream()
                        .map(order -> Map.of("code", order.code(), "direction", order.direction()))
                        .toList());
        canonical.put("limit", plan.limit());
        canonical.put("planHash", plan.planHash());
        return JsonUtils.toJsonString(canonical);
    }

    private static String canonicalPlanHash() {
        return validated().planHash();
    }

    private static ValidatedQueryPlan validated() {
        return new AiQueryPlanValidator()
                .validate(
                        AiQueryPlanFixture.VALID_PLAN,
                        AiQueryPlanFixture.dataset(),
                        AiQueryPlanFixture.CLOCK.instant());
    }

    private static void assertCode(Throwable throwable, Integer expectedCode) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expectedCode);
    }

    @Test
    void refusesWithoutEffectiveRowScopeBeforeAnyPlanning() {
        assertCode(
                assertThatThrownBy(() -> service.execute(request().setRowScope(null)))
                        .actual(),
                AiErrorCodeConstants.AI_QUERY_SCOPE_REQUIRED.getCode());
        assertCode(
                assertThatThrownBy(() -> service.execute(request().setRowScope(new QueryScope(List.of()))))
                        .actual(),
                AiErrorCodeConstants.AI_QUERY_SCOPE_REQUIRED.getCode());
        // 没有行范围时连规划都不该发生（更不会执行查询）
        verify(queryPlanner, never()).plan(any());
        verify(compiledQueryExecutor, never()).execute(any(), any());
    }

    @Test
    void rejectsIncompleteRequest() {
        assertCode(
                assertThatThrownBy(() -> service.execute(null)).actual(),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                assertThatThrownBy(() -> service.execute(request().setQuestion(" ")))
                        .actual(),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                assertThatThrownBy(() -> service.execute(request().setEndpointId(null)))
                        .actual(),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
    }

    @Test
    void returnsClarificationAsNormalResultWithoutExecuting() {
        when(queryPlanner.plan(any()))
                .thenReturn(new AiQueryPlanResultDTO()
                        .setKind("CLARIFICATION")
                        .setDatasetId(AiQueryPlanFixture.DATASET_ID)
                        .setDatasetCode("it-query-orders")
                        .setQuestion("“销售额”指净额还是含退款？")
                        .setReason("AMBIGUOUS")
                        .setCandidates(List.of(new AiQueryPlanResultDTO.Candidate("net_amount", "净销售额"))));

        AiRunQueryExecutionResultDTO result = service.execute(request());

        assertThat(result.getKind()).isEqualTo(AiRunQueryExecutionResultDTO.KIND_CLARIFICATION);
        assertThat(result.getClarificationQuestion()).isEqualTo("“销售额”指净额还是含退款？");
        assertThat(result.getClarificationReason()).isEqualTo("AMBIGUOUS");
        assertThat(result.getClarificationCandidates()).hasSize(1);
        // 澄清不执行：编译与执行都不该发生
        verify(compiledQueryExecutor, never()).execute(any(), any());
    }

    @Test
    void rejectsDatasetThatIsNotExecutable() {
        when(queryPlanner.plan(any())).thenReturn(plan());

        when(datasetService.getDataset(AiQueryPlanFixture.DATASET_ID))
                .thenReturn(dataset().setStatus("DISABLED"));
        assertCode(
                assertThatThrownBy(() -> service.execute(request())).actual(),
                AiErrorCodeConstants.AI_DATASET_DISABLED.getCode());

        when(datasetService.getDataset(AiQueryPlanFixture.DATASET_ID)).thenReturn(dataset());
        when(datasetService.getVersion(AiQueryPlanFixture.DATASET_VERSION_ID))
                .thenReturn(version().setStatus(AiDatasetVersionDO.STATUS_DRAFT));
        assertCode(
                assertThatThrownBy(() -> service.execute(request())).actual(),
                AiErrorCodeConstants.AI_DATASET_VERSION_NOT_PUBLISHED.getCode());

        when(datasetService.getVersion(AiQueryPlanFixture.DATASET_VERSION_ID))
                .thenReturn(version().setVerificationStatus(AiDatasetVersionDO.VERIFICATION_DRIFTED));
        assertCode(
                assertThatThrownBy(() -> service.execute(request())).actual(),
                AiErrorCodeConstants.AI_DATASET_VERSION_DRIFTED.getCode());

        when(datasetService.getVersion(AiQueryPlanFixture.DATASET_VERSION_ID))
                .thenReturn(version().setVerificationStatus(AiDatasetVersionDO.VERIFICATION_UNVERIFIED));
        assertCode(
                assertThatThrownBy(() -> service.execute(request())).actual(),
                AiErrorCodeConstants.AI_DATASET_VERSION_NOT_VERIFIED.getCode());

        // 版本不属于该数据集：按不存在处理，不串数据集
        when(datasetService.getVersion(AiQueryPlanFixture.DATASET_VERSION_ID))
                .thenReturn(version().setDatasetId(999L));
        assertCode(
                assertThatThrownBy(() -> service.execute(request())).actual(),
                AiErrorCodeConstants.AI_DATASET_VERSION_NOT_FOUND.getCode());

        // 计划没有版本锚点（异常上游）：同样拒绝
        when(queryPlanner.plan(any())).thenReturn(plan().setDatasetVersionId(null));
        assertCode(
                assertThatThrownBy(() -> service.execute(request())).actual(),
                AiErrorCodeConstants.AI_DATASET_VERSION_NOT_FOUND.getCode());
    }

    @Test
    void executesPlanAndProjectsRowsToPlanColumnsOnly() {
        when(queryPlanner.plan(any())).thenReturn(plan());
        when(datasetService.getDataset(AiQueryPlanFixture.DATASET_ID)).thenReturn(dataset());
        when(datasetService.getVersion(AiQueryPlanFixture.DATASET_VERSION_ID)).thenReturn(version());
        when(compiledQueryExecutor.execute(eq(CONNECTOR_ID), any()))
                .thenReturn(new AiMysqlQueryResultDTO()
                        .setColumns(List.of("net_amount", "customer_name", "secret_owner"))
                        .setRows(List.of(Map.of(
                                "net_amount", "450.00", "customer_name", "客户二", "secret_owner", "not-authorized")))
                        .setRowCount(1)
                        .setTruncated(false));

        AiRunQueryExecutionResultDTO result = service.execute(request());

        assertThat(result.getKind()).isEqualTo(AiRunQueryExecutionResultDTO.KIND_PLAN);
        assertThat(result.getDatasetCode()).isEqualTo("it-query-orders");
        assertThat(result.datasetResourceKey()).isEqualTo("dset_it-query-orders");
        assertThat(result.getPlanHash()).hasSize(64);
        // 结果标识由计划哈希派生：同一份计划永远同一标识（不含数据与物理对象名）
        assertThat(result.getResultRef())
                .isEqualTo("plan_" + result.getPlanHash().substring(0, 12));
        assertThat(result.getCompleteness()).isEqualTo(AiRunQueryExecutionResultDTO.COMPLETE);
        assertThat(result.isTruncated()).isFalse();
        assertThat(result.getColumns())
                .extracting(AiRunQueryExecutionResultDTO.Column::code)
                .contains("net_amount", "customer_name");
        // 结果行只保留计划声明的列：目录外的列（含越权列）不进入结果
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0)).doesNotContainKey("secret_owner");
        // 指标单位来自语义定义（CURRENCY），报表直接用它标注
        assertThat(result.getColumns())
                .filteredOn(column -> column.code().equals("net_amount"))
                .extracting(AiRunQueryExecutionResultDTO.Column::unit)
                .containsExactly("CURRENCY");
    }

    @Test
    void refusesCanonicalPlanWhoseHashDoesNotMatchRevalidation() {
        when(queryPlanner.plan(any()))
                .thenReturn(plan().setPlanJson(canonicalPlan().replace(canonicalPlanHash(), "f".repeat(64))));
        when(datasetService.getDataset(AiQueryPlanFixture.DATASET_ID)).thenReturn(dataset());
        when(datasetService.getVersion(AiQueryPlanFixture.DATASET_VERSION_ID)).thenReturn(version());

        // 还原后的计划与规划器回传的不是同一份：拒绝执行，绝不执行"另一份计划"
        assertCode(
                assertThatThrownBy(() -> service.execute(request())).actual(),
                AiErrorCodeConstants.AI_QUERY_PLAN_INVALID.getCode());
        verify(compiledQueryExecutor, never()).execute(any(), any());
    }

    @Test
    void truncatedResultIsReportedAsPartial() {
        when(queryPlanner.plan(any())).thenReturn(plan());
        when(datasetService.getDataset(AiQueryPlanFixture.DATASET_ID)).thenReturn(dataset());
        when(datasetService.getVersion(AiQueryPlanFixture.DATASET_VERSION_ID)).thenReturn(version());
        when(compiledQueryExecutor.execute(eq(CONNECTOR_ID), any()))
                .thenReturn(new AiMysqlQueryResultDTO()
                        .setColumns(List.of("net_amount", "customer_name"))
                        .setRows(List.of(Map.of("net_amount", "1.00", "customer_name", "客户一")))
                        .setRowCount(1)
                        .setTruncated(true));

        AiRunQueryExecutionResultDTO result = service.execute(request());

        // 触达行数上限就是 PARTIAL：不得谎称完整统计
        assertThat(result.getCompleteness()).isEqualTo(AiRunQueryExecutionResultDTO.PARTIAL);
        assertThat(result.isTruncated()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(1);
    }

    @Test
    void clockIsTheInjectedFixedClock() {
        // 时钟必须是注入的（可复现），不是 Instant.now()
        Clock clock = AiQueryPlanFixture.CLOCK;
        assertThat(clock.instant().toString()).isEqualTo("2026-09-20T12:00:00Z");
    }
}
