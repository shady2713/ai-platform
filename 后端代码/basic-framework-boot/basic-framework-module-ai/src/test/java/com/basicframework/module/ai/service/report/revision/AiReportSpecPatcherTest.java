package com.basicframework.module.ai.service.report.revision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.domain.report.AiReportSpec;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionResultDTO;
import com.basicframework.module.ai.testfixture.AiReportRevisionFixture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R05 规格补丁：展示类操作只改已存在的块（确定性），数据类操作只用受控查询结果（不编造数字）。
 */
class AiReportSpecPatcherTest {

    private final AiReportSpecPatcher patcher = new AiReportSpecPatcher();

    private AiReportSpecPatch apply(String planJson) {
        return apply(planJson, Map.of());
    }

    private AiReportSpecPatch apply(String planJson, Map<Long, AiRunQueryExecutionResultDTO> queryResults) {
        return patcher.apply(AiReportRevisionFixture.BASE_SPEC, AiReportRevisionPlan.parse(planJson), queryResults);
    }

    private static void assertCode(Throwable throwable, Integer expectedCode) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expectedCode);
    }

    @Test
    void chartTypeChangeKeepsBlocksAndDataProjection() {
        AiReportSpecPatch patch = apply(AiReportRevisionFixture.PRESENTATION_PLAN);
        AiReportSpec candidate = AiReportSpec.parse(patch.specJson());

        assertThat(candidate.blocksById().get("sales_chart").chart().chartType())
                .isEqualTo("line");
        assertThat(candidate.blocks()).hasSize(3);
        assertThat(candidate.datasetRefs()).hasSize(1);
        assertThat(patch.diff().queryRequired()).isFalse();
        assertThat(patch.diff().modifiedBlocks()).containsExactly("sales_chart");
        assertThat(patch.diff().addedBlocks()).isEmpty();
        assertThat(patch.diff().changed()).isTrue();
        // 换图不改数据投影：基础版本的数据仍可直接复用（不查库）
        assertThat(AiReportRevisionData.projectionChanged(
                        AiReportSpec.parse(AiReportRevisionFixture.BASE_SPEC), candidate))
                .isFalse();
    }

    @Test
    void fieldAndColumnChangesAreProjectionChangesButStillPresentationOnly() {
        AiReportSpecPatch patch = apply(AiReportRevisionFixture.REPROJECTION_PLAN);
        AiReportSpec candidate = AiReportSpec.parse(patch.specJson());

        assertThat(patch.diff().queryRequired()).isFalse();
        assertThat(patch.diff().modifiedBlocks()).containsExactly("sales_table");
        // 投影变了 → 必须重新绑定（用基础版本保存的结果行，仍不查库）
        assertThat(AiReportRevisionData.projectionChanged(
                        AiReportSpec.parse(AiReportRevisionFixture.BASE_SPEC), candidate))
                .isTrue();
    }

    @Test
    void presentationOpsCoverTitleThemeTextLayoutAndBlocks() {
        AiReportSpecPatch patch = apply("{\"schemaVersion\":\"1.0\",\"operations\":["
                + "{\"op\":\"SET_TITLE\",\"value\":\"9 月销售\"},"
                + "{\"op\":\"SET_THEME\",\"value\":\"thm_dark\",\"field\":\"2\"},"
                + "{\"op\":\"SET_BLOCK_TITLE\",\"blockId\":\"intro\",\"value\":\"新口径\"},"
                + "{\"op\":\"SET_TEXT\",\"blockId\":\"intro\",\"value\":\"9 月、华东、已付款。\"},"
                + "{\"op\":\"SET_PAGE_SIZE\",\"blockId\":\"sales_table\",\"value\":\"20\"},"
                + "{\"op\":\"ADD_TEXT\",\"blockId\":\"extra_note\",\"field\":\"补充\",\"value\":\"数据截至 9 月末。\"},"
                + "{\"op\":\"REMOVE_BLOCK\",\"blockId\":\"sales_chart\"}]}");
        AiReportSpec candidate = AiReportSpec.parse(patch.specJson());

        assertThat(candidate.title()).isEqualTo("9 月销售");
        assertThat(candidate.themeId()).isEqualTo("thm_dark");
        assertThat(candidate.themeRevision()).isEqualTo(2);
        assertThat(candidate.blocksById().get("intro").title()).isEqualTo("新口径");
        assertThat(candidate.blocksById().get("intro").text()).contains("9 月");
        assertThat(candidate.blocksById().get("sales_table").pageSize()).isEqualTo(20);
        assertThat(candidate.blocksById()).containsKey("extra_note");
        assertThat(candidate.blocksById()).doesNotContainKey("sales_chart");
        assertThat(patch.diff().titleChanged()).isTrue();
        assertThat(patch.diff().themeChanged()).isTrue();
        assertThat(patch.diff().addedBlocks()).containsExactly("extra_note");
        assertThat(patch.diff().removedBlocks()).containsExactly("sales_chart");
        assertThat(patch.diff().layoutChanged()).isTrue();
        // 布局必须覆盖所有块：删掉的块不能留在布局里，新增的块必须有摆放位置
        assertThat(candidate.layout())
                .extracting(AiReportSpec.LayoutItem::blockId)
                .containsExactlyInAnyOrder("intro", "sales_table", "extra_note");
    }

    @Test
    void rejectsOperationsOnMissingOrWrongTypedBlocks() {
        assertCode(
                assertThatThrownBy(
                                () -> apply(
                                        "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"SET_TEXT\",\"blockId\":\"ghost\",\"value\":\"x\"}]}"))
                        .actual(),
                AiErrorCodeConstants.AI_REPORT_REVISION_PLAN_INVALID.getCode());
        // 对表格块改图表类型：类型不符即拒绝
        assertCode(
                assertThatThrownBy(
                                () -> apply(
                                        "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"SET_CHART_TYPE\",\"blockId\":\"sales_table\",\"value\":\"pie\"}]}"))
                        .actual(),
                AiErrorCodeConstants.AI_REPORT_REVISION_PLAN_INVALID.getCode());
        // 指标块不存在的绑定字段
        assertCode(
                assertThatThrownBy(
                                () -> apply(
                                        "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"SET_METRIC_FIELD\",\"blockId\":\"sales_chart\",\"value\":\"x\"}]}"))
                        .actual(),
                AiErrorCodeConstants.AI_REPORT_REVISION_PLAN_INVALID.getCode());
        // 图表绑定字段名不在图表域内
        assertCode(
                assertThatThrownBy(
                                () -> apply(
                                        "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"SET_CHART_FIELD\",\"blockId\":\"sales_chart\",\"field\":\"tooltip\",\"value\":\"x\"}]}"))
                        .actual(),
                AiErrorCodeConstants.AI_REPORT_REVISION_PLAN_INVALID.getCode());
        // 新增文本块用了已存在的标识
        assertCode(
                assertThatThrownBy(
                                () -> apply(
                                        "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"ADD_TEXT\",\"blockId\":\"intro\",\"value\":\"x\"}]}"))
                        .actual(),
                AiErrorCodeConstants.AI_REPORT_REVISION_PLAN_INVALID.getCode());
        // 布局值不是合法布局项数组
        assertCode(
                assertThatThrownBy(
                                () -> apply(
                                        "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"SET_LAYOUT\",\"value\":\"not-json\"}]}"))
                        .actual(),
                AiErrorCodeConstants.AI_REPORT_REVISION_PLAN_INVALID.getCode());
        // 表格列字段不在结果列里：语义校验会拒绝（这里验证确实抛错而不是静默通过）
        assertCode(
                assertThatThrownBy(() -> {
                            AiReportSpecPatch patch = apply(
                                    "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"SET_TABLE_COLUMNS\",\"blockId\":\"sales_table\",\"value\":\"not_a_column\"}]}");
                            new com.basicframework.module.ai.service.report.validation.AiReportSpecValidator()
                                    .validate(AiReportSpec.parse(patch.specJson()));
                        })
                        .actual(),
                AiErrorCodeConstants.AI_REPORT_CHART_FIELD_INVALID.getCode());
    }

    @Test
    void dataOperationRequiresControlledQueryResult() {
        // 没有受控查询结果：拒绝（不允许用旧数据凑新口径）
        assertCode(
                assertThatThrownBy(() -> apply(AiReportRevisionFixture.DATA_PLAN))
                        .actual(),
                AiErrorCodeConstants.AI_REPORT_REVISION_UNSUPPORTED.getCode());
    }

    @Test
    void addMetricUsesRealQueryResultAndAddsDatasetQueryAndSource() {
        AiReportSpecPatch patch =
                apply(AiReportRevisionFixture.DATA_PLAN, Map.of(81L, AiReportRevisionFixture.queryResult()));
        AiReportSpec candidate = AiReportSpec.parse(patch.specJson());

        assertThat(patch.diff().queryRequired()).isTrue();
        assertThat(patch.diff().addedBlocks()).hasSize(1);
        assertThat(patch.diff().addedDatasetRefs()).hasSize(1);
        assertThat(patch.diff().addedSources()).hasSize(1);
        // 新增指标块绑定到受控查询结果的数字列，取值来自真实结果（第 0 行）
        String blockId = patch.diff().addedBlocks().get(0);
        AiReportSpec.Block block = candidate.blocksById().get(blockId);
        assertThat(block.type()).isEqualTo("metric");
        assertThat(block.metricField()).isEqualTo("total_net_amount");
        assertThat(block.rowIndex()).isZero();
        assertThat(block.title()).isEqualTo("客户净销售额合计");
        assertThat(block.unit()).isEqualTo("CURRENCY");
        // 数据集引用/查询引用/来源三件套都来自真实执行结果
        AiReportSpec.DatasetRef ref = candidate.datasetRefs().stream()
                .filter(item -> item.id().equals(block.datasetRef()))
                .findFirst()
                .orElseThrow();
        assertThat(ref.resultRef()).isEqualTo("plan_bbbbbbbbbbbb");
        assertThat(ref.rowCount()).isEqualTo(2);
        assertThat(ref.completeness()).isEqualTo("COMPLETE");
        assertThat(ref.columns())
                .extracting(AiReportSpec.ResultColumn::field)
                .containsExactly("customer_name", "total_net_amount");
        assertThat(candidate.queryRefs()).hasSize(2);
        assertThat(candidate.sources()).hasSize(2);
        assertThat(candidate.sources().get(1).resourceId()).isEqualTo(AiReportRevisionFixture.DATASET_CODE);
    }

    @Test
    void addMetricRejectsMetricMissingFromResultColumns() {
        AiRunQueryExecutionResultDTO result = AiReportRevisionFixture.queryResult()
                .setColumns(List.of(new AiRunQueryExecutionResultDTO.Column("customer_name", "客户", "STRING", null)));
        assertCode(
                assertThatThrownBy(() -> apply(AiReportRevisionFixture.DATA_PLAN, Map.of(81L, result)))
                        .actual(),
                AiErrorCodeConstants.AI_REPORT_REVISION_PLAN_INVALID.getCode());
    }

    @Test
    void addMetricRejectsEmptyResultInsteadOfFabricatingNumbers() {
        AiRunQueryExecutionResultDTO empty =
                AiReportRevisionFixture.queryResult().setRows(List.of()).setRowCount(0);
        assertCode(
                assertThatThrownBy(() -> apply(AiReportRevisionFixture.DATA_PLAN, Map.of(81L, empty)))
                        .actual(),
                AiErrorCodeConstants.AI_REPORT_REVISION_UNSUPPORTED.getCode());
    }

    @Test
    void regrainReplacesDatasetQueryAndSourceInPlace() {
        AiReportSpecPatch patch =
                apply(AiReportRevisionFixture.GRAIN_PLAN, Map.of(81L, AiReportRevisionFixture.queryResult()));
        AiReportSpec candidate = AiReportSpec.parse(patch.specJson());

        // 同一数据集重查：沿用原数据集引用标识，已有块不用改绑定
        assertThat(candidate.datasetRefs()).hasSize(1);
        assertThat(candidate.datasetRefs().get(0).id()).isEqualTo("sales_result");
        assertThat(candidate.datasetRefs().get(0).resultRef()).isEqualTo("plan_bbbbbbbbbbbb");
        assertThat(candidate.queryRefs()).hasSize(1);
        assertThat(candidate.sources()).hasSize(1);
        assertThat(patch.diff().addedDatasetRefs()).isEmpty();
        assertThat(patch.diff().addedBlocks()).isEmpty();
        // 结果列没变：原有图表/表格仍能绑定成功
        new com.basicframework.module.ai.service.report.validation.AiReportSpecValidator().validate(candidate);
    }

    @Test
    void addDatasetAddsTableBlockForNewSource() {
        AiReportSpecPatch patch = apply(
                "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"ADD_DATASET\",\"datasetId\":\"81\",\"value\":\"回款明细\"}]}",
                Map.of(81L, AiReportRevisionFixture.queryResult()));
        AiReportSpec candidate = AiReportSpec.parse(patch.specJson());

        String blockId = patch.diff().addedBlocks().get(0);
        AiReportSpec.Block block = candidate.blocksById().get(blockId);
        assertThat(block.type()).isEqualTo("table");
        assertThat(block.title()).isEqualTo("回款明细");
        assertThat(block.columns())
                .extracting(AiReportSpec.TableColumn::field)
                .containsExactly("customer_name", "total_net_amount");
        new com.basicframework.module.ai.service.report.validation.AiReportSpecValidator().validate(candidate);
    }

    @Test
    void reusesBaseVersionDataWithoutQueryAndDropsRemovedBlocks() {
        AiReportSpec candidate = AiReportSpec.parse(apply(
                        "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"REMOVE_BLOCK\",\"blockId\":\"sales_chart\"}]}")
                .specJson());

        Map<String, com.basicframework.module.ai.service.report.validation.AiReportDataBinder.ExecutionResult>
                datasets = AiReportRevisionData.executionResults(AiReportRevisionFixture.BASE_DATA);
        assertThat(datasets).containsOnlyKeys("sales_result");
        assertThat(datasets.get("sales_result").rows()).hasSize(2);

        String reused = AiReportRevisionData.reuse(AiReportRevisionFixture.BASE_DATA, candidate);
        assertThat(reused).doesNotContain("sales_chart").contains("sales_table").contains("客户二");
        // 基础版本没有数据（可刷新模式）时，复用结果为空（候选版本同样不带数据）
        assertThat(AiReportRevisionData.reuse(null, candidate)).isNull();
    }
}
