package com.basicframework.module.ai.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.domain.report.AiReportSpec;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * AT-049 旧 ReportSpec 加载（基线模式）。
 *
 * <p>首发没有 N-1 的历史 ReportSpec 产物（v1 是第一个冻结版本），按 Q08 卡 §4 以
 * **基线兼容夹具验证**报告：冻结样例必须能被当前解析器加载；未知版本必须**明确拒绝**而不是
 * 静默按 v1 解析（AT-049 的失败分支："明确不支持"优于"错读成当前版本"）。
 * 首次真实升级（v2）时，这里要补上 v1 夹具 → v2 的显式迁移器与回退影响。
 */
class ReportSpecBaselineCompatibilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String frozenSample() {
        return CompatibilityRepositorySupport.readString(
                CompatibilityRepositorySupport.contractSamplesDirectory().resolve("report-spec.valid.json"));
    }

    @Test
    void frozenV1SampleIsLoadedByTheCurrentParser() throws Exception {
        var schema = MAPPER.readTree(CompatibilityRepositorySupport.readString(
                CompatibilityRepositorySupport.contractSchema("report-spec")));
        assertThat(schema.path("properties").path("schemaVersion").path("const").asText())
                .as("权威 Schema 必须把版本钉在 1.0")
                .isEqualTo(AiReportSpec.SCHEMA_VERSION);

        AiReportSpec spec = AiReportSpec.parse(frozenSample());

        assertThat(spec.title()).isEqualTo("2026年8月华东客户净销售额");
        assertThat(spec.blocks()).isNotEmpty();
        assertThat(spec.datasetRefs()).isNotEmpty();
        assertThat(spec.queryRefs()).isNotEmpty();
        assertThatCode(() -> AiReportSpec.parse(frozenSample())).doesNotThrowAnyException();
    }

    @Test
    void unknownSchemaVersionIsRejectedInsteadOfSilentlyParsedAsV1() {
        String sample = frozenSample();
        assertThat(sample).contains("\"schemaVersion\": \"1.0\"");

        for (String unknownVersion : new String[] {"2.0", "0.9"}) {
            String tampered =
                    sample.replace("\"schemaVersion\": \"1.0\"", "\"schemaVersion\": \"" + unknownVersion + "\"");
            assertThatThrownBy(() -> AiReportSpec.parse(tampered))
                    .as("未知 ReportSpec 版本 %s 必须被明确拒绝（不允许静默按 v1 解析）", unknownVersion)
                    .isInstanceOf(ServiceException.class)
                    .extracting(exception -> ((ServiceException) exception).getCode())
                    .isEqualTo(AiErrorCodeConstants.AI_REPORT_SPEC_INVALID.getCode());
        }
    }

    @Test
    void missingSchemaVersionIsRejected() throws Exception {
        var node = MAPPER.readTree(frozenSample());
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("schemaVersion");

        assertThatThrownBy(() -> AiReportSpec.parse(node.toString()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_REPORT_SPEC_INVALID.getCode());
    }
}
