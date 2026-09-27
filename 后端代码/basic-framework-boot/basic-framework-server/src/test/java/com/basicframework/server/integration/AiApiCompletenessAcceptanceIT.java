package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.ExternalHttpRequest;
import com.basicframework.framework.ai.core.http.ExternalHttpResponse;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.domain.query.AiQueryPlanValidator;
import com.basicframework.module.ai.domain.query.QueryScope;
import com.basicframework.module.ai.domain.query.ResolvedDatasetVersion;
import com.basicframework.module.ai.domain.query.ValidatedQueryPlan;
import com.basicframework.module.ai.domain.result.AiNormalizedResult;
import com.basicframework.module.ai.domain.semantic.AiDatasetDefinition;
import com.basicframework.module.ai.service.connector.AiConnectorService;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import com.basicframework.module.ai.service.connector.importer.AiConnectorOperationService;
import com.basicframework.module.ai.service.query.api.AiApiQueryPlanExecutor;
import com.basicframework.module.ai.service.query.api.AiApiQueryRequestDTO;
import com.basicframework.server.fixtures.ai.AiGoldenSetFixture;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Q07 AT-039 验收（真实 MySQL + 受控出站夹具）：API 分页**截断或失败**时，
 * 结果完整性必须是 {@code PARTIAL}/{@code FAILED}，{@code completeStatistics()} 必须为 false，
 * 任何调用方（模型/报表）都不能把它当作完整统计。
 *
 * <p>证据层级说明：本类是后端 API 层证据。AT-039 的 UI 证据（页面明确显示"不完整/失败"）
 * 属前端切片，不在本卡的后端变更内。
 *
 * <p>上游由夹具精确编排：完整两页（对照组，证明夹具本身能得出 COMPLETE）、第二页 503、
 * 重复游标、条目预算截断。所有断言只看**平台给出的结论**，不使用假数据或伪造的分页数字。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(AiApiCompletenessAcceptanceIT.FixtureHttpConfiguration.class)
class AiApiCompletenessAcceptanceIT extends AbstractPersistenceIntegrationTest {

    /** 上游脚本（由各用例设置；夹具按脚本回应，页计数器暴露实际请求次数）。 */
    @TestConfiguration
    static class FixtureHttpConfiguration {

        static final String SCRIPT_COMPLETE = "complete";

        static final String SCRIPT_FAIL_SECOND_PAGE = "fail-second-page";

        static final String SCRIPT_REPEATED_CURSOR = "repeated-cursor";

        static final AtomicReference<String> SCRIPT = new AtomicReference<>(SCRIPT_COMPLETE);

        static final AtomicInteger CALLS = new AtomicInteger();

        static void script(String script) {
            SCRIPT.set(script);
            CALLS.set(0);
        }

        @Bean
        ExternalHttpClient completenessFixtureHttpClient() {
            return new ExternalHttpClient() {
                @Override
                public ExternalHttpResponse execute(ExternalHttpRequest request) {
                    int page = CALLS.incrementAndGet();
                    String script = SCRIPT.get();
                    if (SCRIPT_FAIL_SECOND_PAGE.equals(script) && page >= 2) {
                        String body = "{\"error\":\"upstream unavailable\"}";
                        return new ExternalHttpResponse(
                                503, Map.of(), body.getBytes(StandardCharsets.UTF_8), body.length());
                    }
                    List<String> items = itemsOf(page, customerOf(request.url()));
                    String next;
                    if (SCRIPT_REPEATED_CURSOR.equals(script)) {
                        // 上游一直回同一个游标：D02 必须判重复并降级，绝不能当成"取完了"
                        next = "\"cursor-same\"";
                    } else {
                        next = page == 1 ? "\"cursor-2\"" : "null";
                    }
                    String body = "{\"data\":{\"items\":[" + String.join(",", items) + "]},\"next\":" + next + "}";
                    return new ExternalHttpResponse(
                            200, Map.of(), body.getBytes(StandardCharsets.UTF_8), body.length());
                }

                @Override
                public CompletableFuture<ExternalHttpResponse> executeAsync(ExternalHttpRequest request) {
                    return CompletableFuture.completedFuture(execute(request));
                }

                @Override
                public void close() {
                    // 夹具无资源
                }

                /** 行范围真的传到上游：按 URL 里的 customer_name 参数过滤条目（不是本地过滤）。 */
                private static List<String> itemsOf(int page, String customer) {
                    List<String> source = AiGoldenSetFixture.API_PAGES.get(page == 1 ? "page1" : "page2");
                    if (customer == null) {
                        return source;
                    }
                    return source.stream()
                            .filter(item -> item.contains("\"customer_name\":\"" + customer + "\""))
                            .toList();
                }

                private static String customerOf(String url) {
                    int question = url.indexOf('?');
                    if (question < 0) {
                        return null;
                    }
                    for (String pair : url.substring(question + 1).split("&")) {
                        int equals = pair.indexOf('=');
                        if (equals > 0
                                && "customer_name"
                                        .equals(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8))) {
                            return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                        }
                    }
                    return null;
                }
            };
        }
    }

    private static final String HTTP_CONNECTOR_CODE = "it-q07-http";

    /** 固定时钟：与黄金集同一口径。 */
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");

    private static final String OPEN_API_DOCUMENT =
            """
            {"openapi": "3.0.3",
             "info": {"title": "Q07 分页夹具", "version": "1.0.0"},
             "paths": {"/sales": {"get": {"operationId": "getSales", "summary": "查询净额",
               "parameters": [{"name": "customer_name", "in": "query", "required": false,
                               "schema": {"type": "string"}}],
               "responses": {"200": {"description": "ok"}}}}}}
            """;

    /** 游标分页，最多 3 页：上游一直说有下一页时结论必须是 PARTIAL。 */
    private static final String PAGINATION_JSON =
            "{\"type\":\"CURSOR\",\"maxPages\":3,\"cursorParam\":\"cursor\",\"cursorPath\":\"next\"}";

    /** 数据集版本事实：与黄金集同一份已评审定义（不落库，只用于把计划校验成可执行形状）。 */
    private static final ResolvedDatasetVersion SALES = new ResolvedDatasetVersion(
            1L,
            AiGoldenSetFixture.DATASET_CODE,
            1L,
            AiGoldenSetFixture.DATASET_VERSION_NO,
            "it-q07-schema-hash",
            AiGoldenSetFixture.SOURCE_OBJECT,
            AiDatasetDefinition.parse(AiGoldenSetFixture.DEFINITION),
            List.of());

    @Autowired
    private AiConnectorService connectorService;

    @Autowired
    private AiConnectorOperationService operationService;

    @Autowired
    private AiApiQueryPlanExecutor apiQueryPlanExecutor;

    private final AiQueryPlanValidator validator = new AiQueryPlanValidator();

    private Long httpConnectorId;

    @BeforeEach
    void prepareHttpEntry() {
        FixtureHttpConfiguration.script(FixtureHttpConfiguration.SCRIPT_COMPLETE);
        httpConnectorId = connectorService.create(new AiConnectorSaveDTO()
                .setCode(HTTP_CONNECTOR_CODE)
                .setName("Q07 分页夹具")
                .setConnectorType(AiConnectorDO.TYPE_HTTP)
                .setConfigJson("{\"baseUrl\":\"https://q07.example.com\",\"method\":\"GET\"}"));
        operationService.importOperations(httpConnectorId, OPEN_API_DOCUMENT);
        Long operationId = operationService.listOperations(httpConnectorId).stream()
                .filter(operation -> "getSales".equals(operation.getOperationKey()))
                .findFirst()
                .orElseThrow()
                .getId();
        jdbcTemplate.update(
                "UPDATE ai_connector_operation SET pagination_json = ?, response_json = ? WHERE id = ?",
                PAGINATION_JSON,
                "{\"listPath\":\"data.items\"}",
                operationId);
        operationService.publish(
                operationId, operationService.getOperation(operationId).getVersion());
    }

    @AfterEach
    void cleanUp() {
        FixtureHttpConfiguration.script(FixtureHttpConfiguration.SCRIPT_COMPLETE);
        jdbcTemplate.update("DELETE FROM ai_connector_operation WHERE connector_id = ?", httpConnectorId);
        jdbcTemplate.update("DELETE FROM ai_connector WHERE code = ?", HTTP_CONNECTOR_CODE);
        httpConnectorId = null;
    }

    /** API 入口执行：已校验计划 + 已发布 operation + 行范围（上游按 customer_name 参数过滤）。 */
    private AiNormalizedResult execute(String customer, Integer maxItems) {
        ValidatedQueryPlan plan = validator.validate(AiGoldenSetFixture.API_PLAN, SALES, NOW);
        return apiQueryPlanExecutor.execute(new AiApiQueryRequestDTO(
                httpConnectorId, "getSales", plan, QueryScope.eq("customer_name", customer), maxItems, null, null));
    }

    /** 对照组：上游完整返回两页时结论是 COMPLETE——证明夹具与链路本身能得出"完整"。 */
    @Test
    void completePaginationIsTheControlCase() {
        AiNormalizedResult result = execute("bob", null);
        assertThat(result.completeness()).isEqualTo(AiNormalizedResult.COMPLETE);
        assertThat(result.completeStatistics()).isTrue();
        assertThat(result.rows()).hasSize(1);
        assertThat(decimal(result.rows().get(0).get("total_net_amount")))
                .as("两页都参与聚合（500.00 − 50.00）")
                .isEqualByComparingTo(new BigDecimal(AiGoldenSetFixture.EXPECTED_C002_NET));
        assertThat(result.pages()).isEqualTo(2);
        assertThat(FixtureHttpConfiguration.CALLS.get()).isEqualTo(2);
    }

    /** AT-039：第二页失败 → FAILED + 稳定原因；已取到的行不能冒充完整总额。 */
    @Test
    void upstreamFailureOnSecondPageIsFailedAndNeverClaimsComplete() {
        FixtureHttpConfiguration.script(FixtureHttpConfiguration.SCRIPT_FAIL_SECOND_PAGE);
        AiNormalizedResult result = execute("bob", null);

        assertThat(result.completeness()).isEqualTo(AiNormalizedResult.FAILED);
        assertThat(result.completeStatistics()).as("分页中途失败的结果不能作为总额").isFalse();
        assertThat(result.reason()).as("保留上游稳定原因码，便于排查与展示").isEqualTo("HTTP_503");
        assertThat(result.sourceStatus()).isEqualTo(AiNormalizedResult.FAILED);
        assertThat(result.pages()).as("失败发生在第 2 页，页数如实记录").isEqualTo(2);
        assertThat(decimal(result.rows().get(0).get("total_net_amount")))
                .as("只有第 1 页的 300.00，绝不是完整的两页 450.00")
                .isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(FixtureHttpConfiguration.CALLS.get()).isEqualTo(2);
    }

    /** AT-039：重复游标 → PARTIAL（不是"取完了"）；原因稳定且不可被当成完整。 */
    @Test
    void repeatedCursorIsPartialWithStableReason() {
        FixtureHttpConfiguration.script(FixtureHttpConfiguration.SCRIPT_REPEATED_CURSOR);
        AiNormalizedResult result = execute("bob", null);

        assertThat(result.completeness()).isEqualTo(AiNormalizedResult.PARTIAL);
        assertThat(result.completeStatistics()).isFalse();
        assertThat(result.reason()).isEqualTo("repeated-cursor");
        assertThat(FixtureHttpConfiguration.CALLS.get())
                .as("重复游标在第 2 页被发现后立即停止")
                .isEqualTo(2);
    }

    /** AT-039：条目预算触顶 → PARTIAL + item-limit，已取行保留但不能宣称完整。 */
    @Test
    void itemBudgetTruncationIsPartialWithItemLimitReason() {
        AiNormalizedResult result = execute("bob", 1);

        assertThat(result.completeness()).isEqualTo(AiNormalizedResult.PARTIAL);
        assertThat(result.completeStatistics()).isFalse();
        assertThat(result.reason()).isEqualTo("item-limit");
        assertThat(result.sourceItems()).as("截断后实际参与统计的条目数如实记录").isEqualTo(1);
        assertThat(decimal(result.rows().get(0).get("total_net_amount")))
                .as("被截断的结果只是 1 条上游条目，不能当成完整总额 450.00")
                .isEqualByComparingTo(new BigDecimal("300.00"));
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }
}
