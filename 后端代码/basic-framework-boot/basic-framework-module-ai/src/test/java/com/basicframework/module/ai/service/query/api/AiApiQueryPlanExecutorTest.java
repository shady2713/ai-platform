package com.basicframework.module.ai.service.query.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.connector.http.AiHttpConnectorExecutor;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionRequestDTO;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorOperationDO;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorOperationMapper;
import com.basicframework.module.ai.domain.query.AiQueryPlanValidator;
import com.basicframework.module.ai.domain.query.QueryScope;
import com.basicframework.module.ai.domain.query.ValidatedQueryPlan;
import com.basicframework.module.ai.domain.result.AiNormalizedResult;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.testfixture.AiQueryPlanFixture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** D07 API 计划执行器：绑定已发布 operation、参数面来自声明、行范围必需、预算截断与完整性。 */
class AiApiQueryPlanExecutorTest {

    private static final Long CONNECTOR_ID = 71L;

    private static final String OPERATION_KEY = "getOrders";

    private final AiConnectorOperationMapper operationMapper = mock(AiConnectorOperationMapper.class);

    private final AiHttpConnectorExecutor httpConnectorExecutor = mock(AiHttpConnectorExecutor.class);

    private final AiApiQueryPlanExecutor executor =
            new AiApiQueryPlanExecutor(operationMapper, httpConnectorExecutor, new AiApiResultNormalizer());

    private final AiQueryPlanValidator validator = new AiQueryPlanValidator();

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static AiConnectorOperationDO operation(String status, String parameterJson) {
        return new AiConnectorOperationDO()
                .setId(81L)
                .setConnectorId(CONNECTOR_ID)
                .setOperationKey(OPERATION_KEY)
                .setHttpMethod("GET")
                .setPathTemplate("/orders")
                .setParameterJson(parameterJson)
                .setResponseJson("{\"rootPath\":\"\",\"listPath\":\"data.items\"}")
                .setPaginationJson("{\"type\":\"NONE\",\"maxPages\":1}")
                .setStatus(status)
                .setVersion(0);
    }

    private static String item(String customer, String amount) {
        return "{\"customer_id\":\"" + customer + "\",\"amount\":" + amount + "}";
    }

    private static AiConnectorExecutionResultDTO execution(String status, String reason, String... items) {
        return new AiConnectorExecutionResultDTO()
                .setStatus(status)
                .setPages(1)
                .setItemCount(items.length)
                .setItems(List.of(items))
                .setStoppedReason(reason);
    }

    private ValidatedQueryPlan plan() {
        return validator.validate(
                AiQueryPlanFixture.VALID_PLAN, AiQueryPlanFixture.dataset(), AiQueryPlanFixture.CLOCK.instant());
    }

    private AiApiQueryRequestDTO request() {
        return new AiApiQueryRequestDTO(
                CONNECTOR_ID, OPERATION_KEY, plan(), QueryScope.in("customer_id", List.of("C001")), null, null, null);
    }

    @BeforeEach
    void setUp() {
        when(operationMapper.selectByKey(CONNECTOR_ID, OPERATION_KEY))
                .thenReturn(operation(
                        AiConnectorOperationDO.STATUS_PUBLISHED,
                        "{\"region\":{\"in\":\"query\",\"required\":false,\"type\":\"string\"},"
                                + "\"customer_id\":{\"in\":\"query\",\"required\":true,\"type\":\"string\"}}"));
    }

    @Test
    void bindsPlanFiltersAndScopeToDeclaredParametersOnly() {
        when(httpConnectorExecutor.execute(any()))
                .thenReturn(execution("COMPLETE", "no-more-pages", item("C001", "12.50"), item("C001", "7.00")));

        AiNormalizedResult result = executor.execute(request());

        ArgumentCaptor<AiConnectorExecutionRequestDTO> captor =
                ArgumentCaptor.forClass(AiConnectorExecutionRequestDTO.class);
        verify(httpConnectorExecutor).execute(captor.capture());
        assertThat(captor.getValue().getArguments())
                .as("过滤条件与行范围都映射到声明参数；请求面里没有 header")
                .containsOnlyKeys("region", "customer_id")
                .containsEntry("region", "EAST")
                .as("单值行范围直接传标量，多值传列表")
                .containsEntry("customer_id", "C001");
        assertThat(result.completeStatistics()).isTrue();
        assertThat(result.schema().codes()).containsExactly("customer_name", "net_amount");
        assertThat(result.rows()).hasSize(1);
    }

    @Test
    void refusesDraftOperationAndUndeclaredParameters() {
        when(operationMapper.selectByKey(CONNECTOR_ID, OPERATION_KEY))
                .thenReturn(operation(AiConnectorOperationDO.STATUS_DRAFT, "{\"region\":{}}"));
        assertThatThrownBy(() -> executor.execute(request()))
                .satisfies(
                        throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_PUBLISHED));
        verify(httpConnectorExecutor, never()).execute(any());

        when(operationMapper.selectByKey(CONNECTOR_ID, OPERATION_KEY)).thenReturn(null);
        assertThatThrownBy(() -> executor.execute(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_FOUND));

        // 声明里没有 region：过滤条件无处可发 → 拒绝（不接受"猜参数名"）
        when(operationMapper.selectByKey(CONNECTOR_ID, OPERATION_KEY))
                .thenReturn(operation(AiConnectorOperationDO.STATUS_PUBLISHED, "{\"customer_id\":{}}"));
        assertThatThrownBy(() -> executor.execute(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_ARGUMENT_INVALID));
    }

    @Test
    void refusesWhenRowScopeCannotBeAppliedUpstream() {
        when(operationMapper.selectByKey(CONNECTOR_ID, OPERATION_KEY))
                .thenReturn(operation(AiConnectorOperationDO.STATUS_PUBLISHED, "{\"region\":{},\"customer_id\":{}}"));

        // 行范围列为空：拒绝
        assertThatThrownBy(() -> executor.execute(new AiApiQueryRequestDTO(
                        CONNECTOR_ID, OPERATION_KEY, plan(), new QueryScope(List.of()), null, null, null)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_SCOPE_REQUIRED));

        // 行范围列未在 operation 声明：同样拒绝（不能"无行约束看全量"）
        assertThatThrownBy(() -> executor.execute(new AiApiQueryRequestDTO(
                        CONNECTOR_ID, OPERATION_KEY, plan(), QueryScope.eq("secret_owner", "x"), null, null, null)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_SCOPE_REQUIRED));
        verify(httpConnectorExecutor, never()).execute(any());
    }

    @Test
    void budgetTruncationDowngradesCompleteness() {
        when(httpConnectorExecutor.execute(any()))
                .thenReturn(execution(
                        "COMPLETE", "no-more-pages", item("C001", "1.00"), item("C002", "2.00"), item("C003", "3.00")));

        AiNormalizedResult limited = executor.execute(new AiApiQueryRequestDTO(
                CONNECTOR_ID, OPERATION_KEY, plan(), QueryScope.in("customer_id", List.of("C001")), 2, null, null));

        assertThat(limited.completeness()).isEqualTo(AiNormalizedResult.PARTIAL);
        assertThat(limited.reason()).isEqualTo("item-limit");
        assertThat(limited.completeStatistics()).as("触顶即不可宣称完整统计").isFalse();
        assertThat(limited.rows()).hasSize(2);

        // 字节上限：只保留能放下的前缀
        AiNormalizedResult bytes = executor.execute(new AiApiQueryRequestDTO(
                CONNECTOR_ID, OPERATION_KEY, plan(), QueryScope.in("customer_id", List.of("C001")), null, null, 30));
        assertThat(bytes.reason()).isEqualTo("size-limit");
        assertThat(bytes.completeStatistics()).isFalse();
    }

    @Test
    void failedUpstreamKeepsStableReasonAndNeverClaimsComplete() {
        when(httpConnectorExecutor.execute(any()))
                .thenReturn(new AiConnectorExecutionResultDTO()
                        .setStatus("FAILED")
                        .setPages(1)
                        .setItemCount(0)
                        .setItems(List.of())
                        .setStoppedReason("upstream-failed")
                        .setDetailCode("HTTP_503"));

        AiNormalizedResult result = executor.execute(request());

        assertThat(result.completeness()).isEqualTo(AiNormalizedResult.FAILED);
        assertThat(result.reason()).isEqualTo("HTTP_503");
        assertThat(result.completeStatistics()).isFalse();
    }

    @Test
    void partialUpstreamKeepsPartialReason() {
        when(httpConnectorExecutor.execute(any()))
                .thenReturn(execution("PARTIAL", "repeated-cursor", item("C001", "1.00")));

        AiNormalizedResult result = executor.execute(request());

        assertThat(result.completeness()).isEqualTo(AiNormalizedResult.PARTIAL);
        assertThat(result.reason()).isEqualTo("repeated-cursor");
        assertThat(result.sourceStatus()).isEqualTo("PARTIAL");
    }

    @Test
    void rejectsMalformedRequests() {
        for (AiApiQueryRequestDTO invalid : List.of(
                new AiApiQueryRequestDTO(
                        null, OPERATION_KEY, plan(), QueryScope.eq("customer_id", "C001"), null, null, null),
                new AiApiQueryRequestDTO(
                        CONNECTOR_ID, "  ", plan(), QueryScope.eq("customer_id", "C001"), null, null, null),
                new AiApiQueryRequestDTO(
                        CONNECTOR_ID, OPERATION_KEY, null, QueryScope.eq("customer_id", "C001"), null, null, null))) {
            assertThatThrownBy(() -> executor.execute(invalid))
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));
        }
        assertThat(executor.declaredParameters(operation(AiConnectorOperationDO.STATUS_PUBLISHED, null)))
                .as("未声明参数时参数面为空")
                .isEmpty();
        assertThat(executor.declaredParameters(operation(AiConnectorOperationDO.STATUS_PUBLISHED, "not-json")))
                .isEmpty();
        assertThat(AiApiQueryRequestDTO.MAX_ITEMS_LIMIT).isEqualTo(AiApiResultNormalizer.MAX_ITEMS);
        assertThat(Map.of()).isEmpty();
    }
}
