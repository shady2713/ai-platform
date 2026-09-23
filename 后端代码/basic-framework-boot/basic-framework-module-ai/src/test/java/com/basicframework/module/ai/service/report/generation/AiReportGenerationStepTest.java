package com.basicframework.module.ai.service.report.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.domain.result.AiReportResultBlock;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.report.generation.dto.AiReportGenerationResultDTO;
import com.basicframework.module.ai.service.report.validation.AiReportDataBinder;
import com.basicframework.module.ai.service.report.validation.AiReportSpecValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * R03 报表生成步骤：受控生成、有限修复、空数据说明、来源与单位可追溯。
 *
 * <p>模型用**固定样例**驱动（`AiReportSpecModel` 的确定性替身）：真实模型效果由 Q04/Q10 评测负责；
 * 这里验证的是"编造列被拒、修复次数有限、产物可追溯"这些结构性约束。
 */
class AiReportGenerationStepTest {

    /** 固定模型样例：按脚本依次返回输出；脚本用尽后重复最后一个。 */
    private static final class ScriptedModel implements AiReportSpecModel {

        private final List<String> outputs;

        private final AtomicInteger index = new AtomicInteger();

        private final List<String> repairRequests = new ArrayList<>();

        private ScriptedModel(List<String> outputs) {
            this.outputs = outputs;
        }

        @Override
        public String generate(String requirement, String catalogJson) {
            return outputs.get(0);
        }

        @Override
        public String repair(String previousOutput, String errorCode) {
            repairRequests.add(errorCode);
            int next = Math.min(index.incrementAndGet(), outputs.size() - 1);
            return outputs.get(next);
        }

        int repairs() {
            return repairRequests.size();
        }
    }

    private static final String VALID_SPEC =
            """
            {"schemaVersion": "1.0", "title": "区域净额",
             "themeRef": {"themeId": "thm_default", "revision": 1},
             "layout": {"columns": 12, "gap": 16, "items": [
               {"blockId": "intro", "row": 0, "column": 0, "span": 12},
               {"blockId": "chart", "row": 1, "column": 0, "span": 7},
               {"blockId": "table", "row": 1, "column": 7, "span": 5}]},
             "blocks": [
               {"id": "intro", "title": "统计口径", "type": "text", "text": "8 月、华东，按客户汇总净额。"},
               {"id": "chart", "title": "客户净额", "type": "chart", "datasetRef": "sales_result",
                "chart": {"chartType": "column", "categoryField": "customer_name", "valueField": "net_amount",
                          "legend": false}},
               {"id": "table", "title": "明细", "type": "table", "datasetRef": "sales_result",
                "columns": [{"field": "customer_name", "label": "客户", "format": "TEXT"},
                            {"field": "net_amount", "label": "净额（元）", "format": "CURRENCY"}],
                "pageSize": 10}],
             "datasetRefs": [
               {"id": "sales_result", "resultRef": "run_1/result/0", "queryRef": "sales_query",
                "columns": [{"field": "customer_name", "label": "客户", "dataType": "STRING"},
                            {"field": "net_amount", "label": "净额", "dataType": "DECIMAL", "unit": "CNY"}],
                "rowCount": 2, "completeness": "COMPLETE"}],
             "queryRefs": [{"id": "sales_query", "plan": {"schemaVersion": "1.0"}}],
             "sources": [{"id": "src", "kind": "DATASET", "resourceId": "dset_sales", "resourceVersion": 1,
                          "description": "语义数据集"}]}
            """;

    private static final String EMPTY_SPEC = VALID_SPEC.replace("\"rowCount\": 2", "\"rowCount\": 0");

    private static Map<String, AiReportDataBinder.ExecutionResult> results() {
        return Map.of(
                "sales_result",
                new AiReportDataBinder.ExecutionResult(
                        "run_1/result/0",
                        List.of("customer_name", "net_amount"),
                        List.of(
                                Map.of("customer_name", "bob", "net_amount", "450.00"),
                                Map.of("customer_name", "alice", "net_amount", "290.00")),
                        "COMPLETE"));
    }

    /** 用 ObjectProvider 包住脚本模型（生产里模型未装配时步骤按稳定原因码失败）。 */
    private static AiReportGenerationStep step(ScriptedModel model) {
        return new AiReportGenerationStep(providerOf(model), new AiReportSpecValidator(), new AiReportDataBinder());
    }

    private static org.springframework.beans.factory.ObjectProvider<AiReportSpecModel> providerOf(
            AiReportSpecModel model) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public AiReportSpecModel getObject() {
                return model;
            }

            @Override
            public AiReportSpecModel getObject(Object... args) {
                return model;
            }

            @Override
            public AiReportSpecModel getIfAvailable() {
                return model;
            }

            @Override
            public AiReportSpecModel getIfUnique() {
                return model;
            }
        };
    }

    @Test
    void missingModelFailsClosedWithStableReason() {
        AiReportGenerationStep withoutModel =
                new AiReportGenerationStep(providerOf(null), new AiReportSpecValidator(), new AiReportDataBinder());

        assertThatThrownBy(() -> withoutModel.generate("上个月华东净额", results(), null))
                .satisfies(throwable -> assertThat(((ServiceException) throwable).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_REPORT_MODEL_UNAVAILABLE.getCode()));
    }

    @Test
    void generatesResultBlockWithTraceableSourcesAndUnits() {
        AiReportGenerationResultDTO result =
                step(new ScriptedModel(List.of(VALID_SPEC))).generate("上个月华东净额", results(), null);

        assertThat(result.isGenerated()).isTrue();
        assertThat(result.getAttempts()).isEqualTo(1);
        assertThat(result.isRepaired()).isFalse();
        AiReportResultBlock block = result.getBlock();
        assertThat(block.kind()).isEqualTo(AiReportResultBlock.KIND_REPORT);
        assertThat(block.title()).isEqualTo("区域净额");
        assertThat(block.data()).hasSize(3);
        assertThat(block.sources()).hasSize(1);
        AiReportResultBlock.SourceRef source = block.sources().get(0);
        assertThat(source.datasetRef()).isEqualTo("sales_result");
        assertThat(source.queryRef()).as("来源可追溯到查询").isEqualTo("sales_query");
        assertThat(source.resultRef()).as("来源可追溯到运行结果").isEqualTo("run_1/result/0");
        assertThat(source.completeness()).isEqualTo("COMPLETE");
        // 单位随列进入规格（金额列 unit=CNY），结果块里的规格已校验
        assertThat(block.specJson()).contains("CNY").contains("net_amount");
        // 数字块标 verified，文本块不标
        assertThat(block.data()).anySatisfy(data -> assertThat(data.verified()).isTrue());
        assertThat(block.data()).anySatisfy(data -> assertThat(data.verified()).isFalse());
    }

    @Test
    void fabricatedColumnsAreRepairedThenRejectedWhenRepairKeepsFailing() {
        String fabricated = VALID_SPEC.replace("\"valueField\": \"net_amount\"", "\"valueField\": \"made_up_column\"");
        ScriptedModel model = new ScriptedModel(List.of(fabricated));

        assertThatThrownBy(() -> step(model).generate("上个月华东净额", results(), null))
                .as("模型持续编造列时最终拒绝")
                .satisfies(throwable -> assertThat(((ServiceException) throwable).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_REPORT_SPEC_INVALID.getCode()));
        assertThat(model.repairs()).as("修复次数有限（不无限重试）").isEqualTo(AiReportGenerationStep.MAX_REPAIR_ATTEMPTS);
    }

    @Test
    void invalidJsonIsRepairedOnceAndSucceeds() {
        ScriptedModel model = new ScriptedModel(List.of("not-a-json", VALID_SPEC));

        AiReportGenerationResultDTO result = step(model).generate("上个月华东净额", results(), null);

        assertThat(result.isGenerated()).isTrue();
        assertThat(result.isRepaired()).isTrue();
        assertThat(result.getAttempts()).isEqualTo(2);
        assertThat(result.getNotes()).anySatisfy(note -> assertThat(note).contains("修复"));
    }

    @Test
    void dataMismatchIsRepairedWithStableErrorCodeOnly() {
        // 声明 3 行但结果只有 2 行：绑定阶段不一致
        String mismatched = VALID_SPEC.replace("\"rowCount\": 2", "\"rowCount\": 3");
        ScriptedModel model = new ScriptedModel(List.of(mismatched, VALID_SPEC));

        AiReportGenerationResultDTO result = step(model).generate("上个月华东净额", results(), null);

        assertThat(result.isGenerated()).isTrue();
        assertThat(model.repairs()).isEqualTo(1);
        assertThat(result.getBound().blocks()).hasSize(3);
    }

    @Test
    void emptyDataProducesEmptyReportWithExplanation() {
        AiReportGenerationResultDTO result = step(new ScriptedModel(List.of(EMPTY_SPEC)))
                .generate(
                        "上个月华东净额",
                        Map.of(
                                "sales_result",
                                new AiReportDataBinder.ExecutionResult(
                                        "run_1/result/0",
                                        List.of("customer_name", "net_amount"),
                                        List.of(),
                                        "COMPLETE")),
                        null);

        assertThat(result.isGenerated()).isTrue();
        assertThat(result.getNotes()).contains(AiReportGenerationStep.NOTE_NO_DATA);
        assertThat(result.getBlock().data()).allSatisfy(data -> {
            assertThat(data.rows()).isEmpty();
            assertThat(data.points()).isEmpty();
        });
    }

    @Test
    void missingRequirementOrResultsIsRejected() {
        assertThatThrownBy(() -> step(new ScriptedModel(List.of(VALID_SPEC))).generate("  ", results(), null))
                .satisfies(throwable -> assertThat(((ServiceException) throwable).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode()));
        assertThatThrownBy(() -> step(new ScriptedModel(List.of(VALID_SPEC))).generate("需求", Map.of(), null))
                .satisfies(throwable -> assertThat(((ServiceException) throwable).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode()));
    }
}
