package com.basicframework.module.ai.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 夹具不变量测试：合成业务（订单/回款/范围/分页）、知识文档（公共/私有/注入/删除）与
 * Mock 模型响应（合法/畸形/超时/工具调用）必须满足协议与安全语义，
 * 前端 {@code packages/ai-contracts} 的夹具测试读取同一份文件。
 */
class AiTestFixturesTest {

    private static final Set<String> ALLOWED_MOCK_EXPECT = Set.of("ACCEPT", "REJECT", "TIMEOUT", "UPSTREAM_ERROR");

    @Test
    void fixedClockIsExplicitAndStable() {
        assertThat(AiTestFixtures.load("fixed-clock.json").path("now").asText()).isEqualTo("2026-09-17T02:00:00Z");
        assertThat(AiTestFixtures.FIXED_NOW).isEqualTo(AiTestFixtures.parseInstant("2026-09-17T02:00:00Z"));
    }

    @Test
    void ordersCarryDecimalAmountsWithoutPrecisionLoss() {
        var rows = AiTestFixtures.rows("business-orders.json", "rows");
        assertThat(rows).hasSize(4);

        Set<String> orderIds = new HashSet<>();
        for (JsonNode row : rows) {
            assertThat(orderIds.add(row.path("order_id").asText())).isTrue();
            assertThat(AiTestFixtures.isDecimalString(row.path("amount").asText()))
                    .isTrue();
        }
        // 大额订单：解析为 BigDecimal 后精度与标度不变
        BigDecimal big = new BigDecimal(rows.get(0).path("amount").asText());
        assertThat(big).isEqualByComparingTo(new BigDecimal("12345678901234.56"));
        assertThat(big.scale()).isEqualTo(2);
    }

    @Test
    void paymentsCoverPartialRefundScenario() {
        var rows = AiTestFixtures.rows("business-payments.json", "rows");
        assertThat(rows).hasSize(3);
        // 同一个订单存在多笔回款（含 0.00），用于验证聚合口径而不是简单取首条
        long multiPaymentOrders = 0;
        for (JsonNode row : rows) {
            if ("ORD-2026-0002".equals(row.path("order_id").asText())) {
                multiPaymentOrders++;
            }
            assertThat(AiTestFixtures.isDecimalString(row.path("amount").asText()))
                    .isTrue();
        }
        assertThat(multiPaymentOrders).isEqualTo(2);
    }

    @Test
    void userScopesIncludeEmptyScopeThatMeansNoPermission() {
        var scopes = AiTestFixtures.rows("user-scopes.json", "scopes");
        assertThat(scopes).hasSize(4);

        boolean sawEmptyScope = false;
        for (JsonNode scope : scopes) {
            JsonNode values = scope.path("values");
            assertThat(values.isArray()).isTrue();
            if (values.isEmpty()) {
                sawEmptyScope = true;
                // 空集合必须解释为无权限（而不是无限制），由消费者按下标语义处理
                assertThat(scope.path("subject").path("externalUserId").asText())
                        .isEqualTo("carol");
            }
        }
        assertThat(sawEmptyScope).isTrue();
    }

    @Test
    void paginationFixtureCoversOverLimitRejection() {
        JsonNode fixture = AiTestFixtures.load("api-pagination.json");
        assertThat(fixture.path("pages")).hasSize(2);
        assertThat(fixture.path("pages").get(0).path("hasNext").asBoolean()).isTrue();
        assertThat(fixture.path("pages").get(1).path("hasNext").asBoolean()).isFalse();
        assertThat(fixture.path("overLimit").path("pageSize").asInt()).isGreaterThan(1000);
        assertThat(fixture.path("overLimit").path("expected").asText()).contains("REJECT");
    }

    @Test
    void knowledgeDocumentsCoverVisibilityInjectionAndDeletion() {
        var documents = AiTestFixtures.rows("knowledge-documents.json", "documents");
        assertThat(documents).hasSize(4);

        Set<String> visibilities = new HashSet<>();
        boolean sawInjection = false;
        boolean sawDeleted = false;
        for (JsonNode document : documents) {
            visibilities.add(document.path("visibility").asText());
            if (document.path("content").asText().contains("忽略之前的指令")) {
                sawInjection = true;
                assertThat(document.path("note").asText()).contains("不得被当作指令执行");
            }
            if (document.path("deleted").asBoolean()) {
                sawDeleted = true;
            }
        }
        assertThat(visibilities).containsExactlyInAnyOrder("PUBLIC", "PRIVATE");
        assertThat(sawInjection).isTrue();
        assertThat(sawDeleted).isTrue();
    }

    @Test
    void mockModelResponsesDeclareExpectedOutcomeAndMalformedIsReallyMalformed() {
        var responses = AiTestFixtures.rows("mock-model-responses.json", "responses");
        assertThat(responses).hasSize(6);

        Set<String> expectations = new HashSet<>();
        for (JsonNode response : responses) {
            expectations.add(response.path("expect").asText());
            if ("malformed-json".equals(response.path("id").asText())) {
                // 畸形响应必须真的无法解析，否则 REJECT 断言失去意义
                assertThat(rawBodyIsInvalid(response.path("rawBody").asText())).isTrue();
            }
            if ("timeout".equals(response.path("id").asText())) {
                assertThat(response.path("latencyMs").asLong()).isGreaterThanOrEqualTo(60_000L);
            }
        }
        assertThat(expectations).isSubsetOf(ALLOWED_MOCK_EXPECT);
        assertThat(expectations).contains("ACCEPT", "REJECT", "TIMEOUT", "UPSTREAM_ERROR");
    }

    private static boolean rawBodyIsInvalid(String rawBody) {
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(rawBody);
            return false;
        } catch (Exception expected) {
            return true;
        }
    }
}
