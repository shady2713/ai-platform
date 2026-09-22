package com.basicframework.module.ai.service.report.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.domain.report.AiReportSpec;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R01 ReportSpec 校验与绑定：恶意/边界夹具 + 数据来源绑定。
 *
 * <p>夹具覆盖 AT-043 的三类拒绝（非法结构、HTML/脚本、未知 dataset）与"缺字段图表不可生成"，
 * 以及绑定阶段的三类不一致（列缺失、行数不符、完整性失真）。
 */
class AiReportSpecValidatorTest {

    private static final String VALID_SPEC =
            """
            {"schemaVersion": "1.0",
             "title": "2026年8月华东客户净销售额",
             "themeRef": {"themeId": "thm_default", "revision": 1},
             "layout": {"columns": 12, "gap": 16, "items": [
               {"blockId": "intro", "row": 0, "column": 0, "span": 12},
               {"blockId": "sales_chart", "row": 1, "column": 0, "span": 7},
               {"blockId": "sales_table", "row": 1, "column": 7, "span": 5},
               {"blockId": "total_metric", "row": 2, "column": 0, "span": 4}]},
             "blocks": [
               {"id": "intro", "title": "统计口径", "type": "text", "text": "8 月、华东，按客户汇总净额。"},
               {"id": "sales_chart", "title": "客户净销售额", "type": "chart", "datasetRef": "sales_result",
                "chart": {"chartType": "column", "categoryField": "customer_name", "valueField": "net_amount",
                          "legend": false}},
               {"id": "sales_table", "title": "客户明细", "type": "table", "datasetRef": "sales_result",
                "columns": [{"field": "customer_name", "label": "客户", "format": "TEXT"},
                            {"field": "net_amount", "label": "净销售额（元）", "format": "CURRENCY"}],
                "pageSize": 10},
               {"id": "total_metric", "title": "合计", "type": "metric", "format": "CURRENCY",
                "binding": {"datasetRef": "sales_result", "field": "net_amount"}, "rowIndex": 0, "unit": "CNY"}],
             "datasetRefs": [
               {"id": "sales_result", "resultRef": "run_sales_demo/result/0", "queryRef": "sales_query",
                "columns": [{"field": "customer_name", "label": "客户", "dataType": "STRING"},
                            {"field": "net_amount", "label": "净销售额", "dataType": "DECIMAL", "unit": "CNY"}],
                "rowCount": 2, "completeness": "COMPLETE"}],
             "queryRefs": [{"id": "sales_query", "plan": {"schemaVersion": "1.0", "datasetId": "dset_sales"}}],
             "sources": [{"id": "src_orders", "kind": "DATASET", "resourceId": "dset_sales", "resourceVersion": 1,
                          "queryRef": "sales_query", "description": "语义数据集（华东 8 月）"}]}
            """;

    private final AiReportSpecValidator validator = new AiReportSpecValidator();

    private final AiReportDataBinder binder = new AiReportDataBinder();

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static Map<String, Object> row(String customer, String netAmount) {
        return Map.of("customer_name", customer, "net_amount", netAmount);
    }

    private static Map<String, AiReportDataBinder.ExecutionResult> results() {
        return Map.of(
                "sales_result",
                new AiReportDataBinder.ExecutionResult(
                        "run_sales_demo/result/0",
                        List.of("customer_name", "net_amount"),
                        List.of(row("bob", "450.00"), row("alice", "290.00")),
                        "COMPLETE"));
    }

    @Test
    void validSpecPassesBothValidationAndBinding() {
        AiReportSpec spec = validator.validate(AiReportSpec.parse(VALID_SPEC));

        assertThat(spec.title()).isEqualTo("2026年8月华东客户净销售额");
        assertThat(spec.blocks()).hasSize(4);

        AiReportDataBinder.BoundReport report = binder.bind(spec, results());

        assertThat(report.blocks()).hasSize(4);
        AiReportDataBinder.BoundBlock metric = report.blocks().stream()
                .filter(block -> block.type().equals("metric"))
                .findFirst()
                .orElseThrow();
        assertThat(metric.verified()).as("数字来自执行结果").isTrue();
        assertThat(metric.value()).isEqualTo(new BigDecimal("450.00"));
        AiReportDataBinder.BoundBlock text = report.blocks().stream()
                .filter(block -> block.type().equals("text"))
                .findFirst()
                .orElseThrow();
        assertThat(text.verified()).as("文本说明不伪装成确定性核验").isFalse();
        assertThat(text.note()).contains("未经过确定性核验");
    }

    @Test
    void scriptStyleAndHtmlFragmentsAreRejected() {
        for (String malicious : List.of(
                VALID_SPEC.replace("8 月、华东，按客户汇总净额。", "<script>alert(1)</script>"),
                VALID_SPEC.replace("8 月、华东，按客户汇总净额。", "<style>body{display:none}</style>"),
                VALID_SPEC.replace("8 月、华东，按客户汇总净额。", "javascript:alert(document.cookie)"),
                VALID_SPEC.replace("8 月、华东，按客户汇总净额。", "<iframe src=\"https://evil.example\"></iframe>"),
                VALID_SPEC.replace("8 月、华东，按客户汇总净额。", "<div onerror=\"alert(1)\">x</div>"))) {
            assertThatThrownBy(() -> AiReportSpec.parse(malicious))
                    .as("HTML/脚本/样式必须拒绝：%s", malicious.substring(0, 60))
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_SCRIPT_REJECTED));
        }
    }

    @Test
    void unknownKeysAndStructureViolationsAreRejected() {
        for (String invalid : List.of(
                VALID_SPEC.replace("\"title\": \"2026年8月华东客户净销售额\"", "\"title\": \"x\", \"style\": \"body{}\""),
                VALID_SPEC.replace("\"title\": \"2026年8月华东客户净销售额\"", "\"title\": \"x\", \"onClick\": \"run()\""),
                VALID_SPEC.replace("\"gap\": 16", "\"gap\": 13"),
                VALID_SPEC.replace("\"columns\": 12", "\"columns\": 24"),
                VALID_SPEC.replace("\"schemaVersion\": \"1.0\"", "\"schemaVersion\": \"2.0\""),
                VALID_SPEC.replace("\"chartType\": \"column\"", "\"chartType\": \"script\""),
                VALID_SPEC.replace("\"kind\": \"DATASET\"", "\"kind\": \"FILE\""),
                "not-json",
                "{}",
                "")) {
            assertThatThrownBy(() -> AiReportSpec.parse(invalid))
                    .as("非法结构必须拒绝：%s", invalid.substring(0, Math.min(60, invalid.length())))
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_SPEC_INVALID));
        }
    }

    @Test
    void unknownOrDuplicateReferencesAreRejected() {
        // 未知 dataset：模型自己造了一个数据来源
        assertThatThrownBy(() -> validator.validate(AiReportSpec.parse(
                        VALID_SPEC.replace("\"datasetRef\": \"sales_result\"", "\"datasetRef\": \"made_up\""))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_REFERENCE_INVALID));

        // 未知 queryRef（数据集指向不存在的查询）
        assertThatThrownBy(() -> validator.validate(AiReportSpec.parse(
                        VALID_SPEC.replace("\"queryRef\": \"sales_query\"", "\"queryRef\": \"missing_query\""))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_REFERENCE_INVALID));

        // 未知 block（布局引用了不存在的块）
        assertThatThrownBy(() -> validator.validate(
                        AiReportSpec.parse(VALID_SPEC.replace("\"blockId\": \"intro\"", "\"blockId\": \"ghost\""))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_REFERENCE_INVALID));

        // 来源声明了不存在的查询引用（悬空出处）
        assertThatThrownBy(() -> validator.validate(AiReportSpec.parse(VALID_SPEC
                        .replace("\"resourceId\": \"dset_sales\"", "\"resourceId\": \"dset_sales\"")
                        .replace(
                                "\"queryRef\": \"sales_query\", \"description\"",
                                "\"queryRef\": \"ghost_query\", \"description\""))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_REFERENCE_INVALID));
    }

    @Test
    void layoutOverlapOverflowAndUnplacedBlocksAreRejected() {
        // 越界：列 7 + 跨度 7 > 12
        assertThatThrownBy(() -> AiReportSpec.parse(VALID_SPEC.replace(
                        "{\"blockId\": \"sales_table\", \"row\": 1, \"column\": 7, \"span\": 5}",
                        "{\"blockId\": \"sales_table\", \"row\": 1, \"column\": 7, \"span\": 7}")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_SPEC_INVALID));

        // 重叠：同一行两个块的列区间相交
        assertThatThrownBy(() -> validator.validate(AiReportSpec.parse(VALID_SPEC.replace(
                        "{\"blockId\": \"sales_table\", \"row\": 1, \"column\": 7, \"span\": 5}",
                        "{\"blockId\": \"sales_table\", \"row\": 1, \"column\": 5, \"span\": 5}"))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_LAYOUT_INVALID));

        // 块未出现在布局里（界面不可见 = 可能藏内容）：显式构造一个"块比布局项多"的规格
        String unplacedBlockSpec =
                """
                {"schemaVersion": "1.0", "title": "t",
                 "themeRef": {"themeId": "thm_default", "revision": 1},
                 "layout": {"columns": 12, "gap": 16, "items": [
                   {"blockId": "a", "row": 0, "column": 0, "span": 6}]},
                 "blocks": [
                   {"id": "a", "title": "A", "type": "text", "text": "x"},
                   {"id": "b", "title": "B", "type": "text", "text": "y"}],
                 "datasetRefs": [
                   {"id": "sales_result", "resultRef": "run/result/0", "queryRef": "sales_query",
                    "columns": [{"field": "net_amount", "label": "净额", "dataType": "DECIMAL"}],
                    "rowCount": 1, "completeness": "COMPLETE"}],
                 "queryRefs": [{"id": "sales_query", "plan": {"schemaVersion": "1.0"}}],
                 "sources": [{"id": "src", "kind": "DATASET", "resourceId": "dset_sales", "resourceVersion": 1,
                              "description": "语义数据集"}]}
                """;
        assertThatThrownBy(() -> validator.validate(AiReportSpec.parse(unplacedBlockSpec)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_LAYOUT_INVALID));

        // 同一个块摆两次
        assertThatThrownBy(() -> validator.validate(AiReportSpec.parse(VALID_SPEC.replace(
                        "{\"blockId\": \"total_metric\", \"row\": 2, \"column\": 0, \"span\": 4}",
                        "{\"blockId\": \"intro\", \"row\": 2, \"column\": 0, \"span\": 4}"))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_LAYOUT_INVALID));
    }

    @Test
    void chartAndMetricFieldsMustExistAndMatchResultSchema() {
        // 图表字段在结果里不存在（缺字段图表不可生成）
        assertThatThrownBy(() -> validator.validate(AiReportSpec.parse(
                        VALID_SPEC.replace("\"valueField\": \"net_amount\"", "\"valueField\": \"missing_field\""))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_CHART_FIELD_INVALID));

        // 类目字段类型不符（把数字列当类目）
        assertThatThrownBy(() -> validator.validate(AiReportSpec.parse(VALID_SPEC.replace(
                        "\"categoryField\": \"customer_name\"", "\"categoryField\": \"net_amount\""))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_CHART_FIELD_INVALID));

        // 数值字段类型不符（把文本列当数值）
        assertThatThrownBy(() -> validator.validate(AiReportSpec.parse(
                        VALID_SPEC.replace("\"valueField\": \"net_amount\"", "\"valueField\": \"customer_name\""))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_CHART_FIELD_INVALID));

        // 指标绑定到文本列：模型编造数字
        assertThatThrownBy(() -> validator.validate(AiReportSpec.parse(VALID_SPEC.replace(
                        "{\"datasetRef\": \"sales_result\", \"field\": \"net_amount\"}",
                        "{\"datasetRef\": \"sales_result\", \"field\": \"customer_name\"}"))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_CHART_FIELD_INVALID));

        // 指标行号越界（声明行数之外的行）
        assertThatThrownBy(() -> validator.validate(
                        AiReportSpec.parse(VALID_SPEC.replace("\"rowIndex\": 0", "\"rowIndex\": 5"))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_REFERENCE_INVALID));
    }

    @Test
    void bindingRejectsDeclarationsThatDoNotMatchTheRealResult() {
        AiReportSpec spec = validator.validate(AiReportSpec.parse(VALID_SPEC));

        // 列缺失：结果里没有声明的列
        assertThatThrownBy(() -> binder.bind(
                        spec,
                        Map.of(
                                "sales_result",
                                new AiReportDataBinder.ExecutionResult(
                                        "run/result/0",
                                        List.of("customer_name"),
                                        List.of(Map.of("customer_name", "bob")),
                                        "COMPLETE"))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_BINDING_MISMATCH));

        // 行数不符
        assertThatThrownBy(() -> binder.bind(
                        spec,
                        Map.of(
                                "sales_result",
                                new AiReportDataBinder.ExecutionResult(
                                        "run/result/0",
                                        List.of("customer_name", "net_amount"),
                                        List.of(row("bob", "450.00")),
                                        "COMPLETE"))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_BINDING_MISMATCH));

        // 完整性失真：PARTIAL 被写成 COMPLETE
        assertThatThrownBy(() -> binder.bind(
                        spec,
                        Map.of(
                                "sales_result",
                                new AiReportDataBinder.ExecutionResult(
                                        "run/result/0",
                                        List.of("customer_name", "net_amount"),
                                        List.of(row("bob", "450.00"), row("alice", "290.00")),
                                        "PARTIAL"))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_BINDING_MISMATCH));

        // 缺少某个数据集引用的执行结果
        assertThatThrownBy(() -> binder.bind(spec, Map.of()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REPORT_REFERENCE_INVALID));
    }

    @Test
    void boundTablesAndChartsOnlyCarryDeclaredFields() {
        AiReportSpec spec = validator.validate(AiReportSpec.parse(VALID_SPEC));
        Map<String, AiReportDataBinder.ExecutionResult> withExtraColumn = Map.of(
                "sales_result",
                new AiReportDataBinder.ExecutionResult(
                        "run/result/0",
                        List.of("customer_name", "net_amount", "internal_margin"),
                        List.of(
                                Map.of("customer_name", "bob", "net_amount", "450.00", "internal_margin", "0.42"),
                                Map.of("customer_name", "alice", "net_amount", "290.00", "internal_margin", "0.38")),
                        "COMPLETE"));

        AiReportDataBinder.BoundReport report = binder.bind(spec, withExtraColumn);

        AiReportDataBinder.BoundBlock table = report.blocks().stream()
                .filter(block -> block.type().equals("table"))
                .findFirst()
                .orElseThrow();
        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0)).as("只带声明的列（上游多余列不进入界面）").containsOnlyKeys("customer_name", "net_amount");
        AiReportDataBinder.BoundBlock chart = report.blocks().stream()
                .filter(block -> block.type().equals("chart"))
                .findFirst()
                .orElseThrow();
        assertThat(chart.points().get(0)).containsOnlyKeys("customer_name", "net_amount");
    }
}
