package com.basicframework.module.ai.adapter.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.util.List;
import org.junit.jupiter.api.Test;

/** K01 服务端过滤：字段白名单、取值净化（拒绝特殊字符）、交集语义与点 ID 映射。 */
class KnowledgeFilterTest {

    private static void assertInvalid(Throwable throwable) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
    }

    @Test
    void acceptsSafeFieldsAndValues() {
        KnowledgeFilter filter = KnowledgeFilter.of("tenant", List.of("tenant-a", "kb_1", "订单-2026"));

        assertThat(filter.conditions()).containsEntry("tenant", List.of("tenant-a", "kb_1", "订单-2026"));
        assertThat(filter.isEmpty()).isFalse();
        assertThat(KnowledgeFilter.of("tenant_id_2", List.of("a")).isEmpty()).isFalse();
    }

    @Test
    void rejectsInjectionCharactersInsteadOfEscaping() {
        // 表达式注入与通配符：一律拒绝（不依赖上游转义规则）
        for (String value : List.of(
                "tenant\" or true",
                "tenant') or ('1'='1",
                "tenant*",
                "tenant\\",
                "tenant\nother",
                "tenant;",
                "tenant{",
                "a".repeat(129))) {
            assertThatThrownBy(() -> KnowledgeFilter.of("tenant", List.of(value)))
                    .as("危险取值必须被拒绝：%s", value)
                    .satisfies(KnowledgeFilterTest::assertInvalid);
        }

        // 字段名同样白名单化：不能出现点号、括号或空格
        for (String field : List.of("tenant.key", "tenant key", "Tenant", "tenant()", "", "1tenant")) {
            assertThatThrownBy(() -> KnowledgeFilter.of(field, List.of("a")))
                    .as("非法字段必须被拒绝：%s", field)
                    .satisfies(KnowledgeFilterTest::assertInvalid);
        }

        assertThatThrownBy(() -> KnowledgeFilter.of("tenant", List.of())).satisfies(KnowledgeFilterTest::assertInvalid);
        assertThatThrownBy(() -> KnowledgeFilter.of("tenant", null)).satisfies(KnowledgeFilterTest::assertInvalid);
    }

    @Test
    void isSafeValueMatchesTheRejectionRule() {
        assertThat(KnowledgeFilter.isSafeValue("tenant-a")).isTrue();
        assertThat(KnowledgeFilter.isSafeValue("订单")).isTrue();
        assertThat(KnowledgeFilter.isSafeValue("a b")).isTrue();
        assertThat(KnowledgeFilter.isSafeValue("a\"b")).isFalse();
        assertThat(KnowledgeFilter.isSafeValue("a*b")).isFalse();
        assertThat(KnowledgeFilter.isSafeValue(null)).isFalse();
    }

    @Test
    void andIntersectsTheSameFieldAndMergesDifferentFields() {
        KnowledgeFilter tenant = KnowledgeFilter.of("tenant", List.of("a", "b"));
        KnowledgeFilter sameField = KnowledgeFilter.of("tenant", List.of("b", "c"));
        KnowledgeFilter otherField = KnowledgeFilter.of("kb", List.of("kb-1"));

        assertThat(tenant.and(sameField).conditions()).as("同字段取交集：权限收窄而不是放大").containsEntry("tenant", List.of("b"));
        assertThat(tenant.and(otherField).conditions())
                .containsEntry("tenant", List.of("a", "b"))
                .containsEntry("kb", List.of("kb-1"));
        assertThat(tenant.and(null)).isEqualTo(tenant);
    }

    @Test
    void failureVocabularyCarriesStableReasonsOnly() {
        // 失败原因码是运维矩阵与 Go/No-Go 结论的输入：每个原因都必须可构造且不携带上游正文
        for (KnowledgeIndexException.Reason reason : KnowledgeIndexException.Reason.values()) {
            KnowledgeIndexException failure = new KnowledgeIndexException(reason, "稳定说明");
            assertThat(failure.getReason()).isEqualTo(reason);
            assertThat(failure.getMessage()).isEqualTo("稳定说明");
            assertThat(failure.getCause()).isNull();
        }

        KnowledgeIndexException withCause = new KnowledgeIndexException(
                KnowledgeIndexException.Reason.TRANSPORT_FAILED, "向量服务不可达", new java.io.IOException("boom"));
        assertThat(withCause.getReason()).isEqualTo(KnowledgeIndexException.Reason.TRANSPORT_FAILED);
        assertThat(withCause.getCause()).isInstanceOf(java.io.IOException.class);
        assertThat(KnowledgeIndexException.Reason.values())
                .as("原因码覆盖端口契约的失败面")
                .contains(
                        KnowledgeIndexException.Reason.COLLECTION_NOT_FOUND,
                        KnowledgeIndexException.Reason.DIMENSION_MISMATCH,
                        KnowledgeIndexException.Reason.UNAUTHORIZED,
                        KnowledgeIndexException.Reason.TRANSPORT_FAILED,
                        KnowledgeIndexException.Reason.UPSTREAM_REJECTED,
                        KnowledgeIndexException.Reason.INVALID_PAYLOAD);
    }

    @Test
    void pointIdMappingIsDeterministicAndAcceptsUuidOrInteger() {
        String uuid = "1c1c1c1c-1c1c-1c1c-1c1c-1c1c1c1c1c1c";

        assertThat(QdrantRestKnowledgeIndexAdapter.pointId(uuid)).isEqualTo(uuid);
        assertThat(QdrantRestKnowledgeIndexAdapter.pointId("12345")).isEqualTo("12345");
        // 逻辑标识 → 确定性 UUID：同一标识永远映射到同一个点（upsert 幂等）
        String mapped = QdrantRestKnowledgeIndexAdapter.pointId("doc-a-1");
        assertThat(mapped).isEqualTo(QdrantRestKnowledgeIndexAdapter.pointId("doc-a-1"));
        assertThat(mapped).isNotEqualTo(QdrantRestKnowledgeIndexAdapter.pointId("doc-a-2"));
        assertThatThrownBy(() -> QdrantRestKnowledgeIndexAdapter.pointId(" "))
                .satisfies(KnowledgeFilterTest::assertInvalid);
    }
}
