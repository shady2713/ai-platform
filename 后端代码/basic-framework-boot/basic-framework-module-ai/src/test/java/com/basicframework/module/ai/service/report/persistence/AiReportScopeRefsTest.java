package com.basicframework.module.ai.service.report.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** R04 依赖解析与指纹：只认 A03 词表、未知类型拒绝、指纹稳定且随依赖集合变化。 */
class AiReportScopeRefsTest {

    @Test
    void parseAcceptsOnlyCatalogResourceTypesAndDeduplicates() {
        List<AiReportScopeRef> refs =
                AiReportScopeRefs.parse("[{\"resourceType\":\"knowledge_base\",\"resourceKey\":\"kb-1\"},"
                        + "{\"resourceType\":\"KNOWLEDGE_BASE\",\"resourceKey\":\"kb-1\",\"resultRef\":\"res_1\"},"
                        + "{\"resourceType\":\"DATASET\",\"resourceKey\":\"dset_orders\"}]");

        assertThat(refs).hasSize(2);
        assertThat(AiReportScopeRefs.key(refs.get(0))).isEqualTo("KNOWLEDGE_BASE/kb-1");
        assertThat(AiReportScopeRefs.key(refs.get(1))).isEqualTo("DATASET/dset_orders");
    }

    @Test
    void parseRejectsUnknownTypeMissingKeyAndNonArray() {
        assertThatThrownBy(() -> AiReportScopeRefs.parse("[{\"resourceType\":\"TABLE\",\"resourceKey\":\"t\"}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A03 词表");
        assertThatThrownBy(() -> AiReportScopeRefs.parse("[{\"resourceType\":\"DATASET\",\"resourceKey\":\" \"}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resourceKey");
        assertThatThrownBy(() -> AiReportScopeRefs.parse("{\"resourceType\":\"DATASET\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须是数组");
        assertThatThrownBy(() -> AiReportScopeRefs.parse("[1,2]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("依赖项必须是对象");
    }

    @Test
    void emptyDeclarationKeepsStableNonBlankFingerprint() {
        assertThat(AiReportScopeRefs.parse(null)).isEmpty();
        assertThat(AiReportScopeRefs.parse("")).isEmpty();
        assertThat(AiReportScopeRefs.fingerprint(List.of()))
                .isEqualTo(AiReportScopeRefs.EMPTY_FINGERPRINT)
                .hasSize(64);
    }

    @Test
    void fingerprintDependsOnRefSetAndPerRefFingerprintOrderInsensitively() {
        AiReportScopeRef kb = new AiReportScopeRef("KNOWLEDGE_BASE", "kb-1", "f1");
        AiReportScopeRef dataset = new AiReportScopeRef("DATASET", "dset_orders", "f2");

        String forward = AiReportScopeRefs.fingerprint(List.of(kb, dataset));
        String backward = AiReportScopeRefs.fingerprint(List.of(dataset, kb));
        assertThat(forward).isEqualTo(backward).isNotEqualTo(AiReportScopeRefs.EMPTY_FINGERPRINT);

        // 任一项指纹变化（授权版本变化）整体指纹必须变化
        assertThat(AiReportScopeRefs.fingerprint(
                        List.of(new AiReportScopeRef("KNOWLEDGE_BASE", "kb-1", "f1-changed"), dataset)))
                .isNotEqualTo(forward);
        // 依赖集合变化同样变化
        assertThat(AiReportScopeRefs.fingerprint(List.of(kb))).isNotEqualTo(forward);
    }

    @Test
    void jsonRoundTripKeepsRefs() {
        List<AiReportScopeRef> refs = List.of(new AiReportScopeRef("KNOWLEDGE_BASE", "kb-1", "f1"));
        String json = AiReportScopeRefs.toJson(refs);

        List<AiReportScopeRef> parsed = AiReportScopeRefs.fromJson(json);
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).getResourceType()).isEqualTo("KNOWLEDGE_BASE");
        assertThat(parsed.get(0).getResourceKey()).isEqualTo("kb-1");
        assertThat(parsed.get(0).getFingerprint()).isEqualTo("f1");
        assertThat(AiReportScopeRefs.fingerprint(parsed)).isEqualTo(AiReportScopeRefs.fingerprint(refs));
        assertThat(AiReportScopeRefs.fromJson(null)).isEmpty();
    }
}
