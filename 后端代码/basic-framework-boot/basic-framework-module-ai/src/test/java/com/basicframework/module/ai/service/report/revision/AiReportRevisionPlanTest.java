package com.basicframework.module.ai.service.report.revision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.testfixture.AiReportRevisionFixture;
import org.junit.jupiter.api.Test;

/**
 * R05 修订计划：操作白名单、必填参数、分类（展示类/数据类）与数据集编号解析。
 *
 * <p>分类是服务端的结论：模型不能通过写别的操作码把"新增指标"降级成"只改展示"。
 */
class AiReportRevisionPlanTest {

    private static void assertInvalid(Throwable throwable) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_REPORT_REVISION_PLAN_INVALID.getCode());
    }

    @Test
    void presentationPlanDoesNotRequireQuery() {
        AiReportRevisionPlan plan = AiReportRevisionPlan.parse(AiReportRevisionFixture.PRESENTATION_PLAN);

        assertThat(plan.requiresQuery()).isFalse();
        assertThat(plan.opCodes()).containsExactly(AiReportRevisionOp.SET_CHART_TYPE);
        assertThat(plan.queryDatasetIds()).isEmpty();
        assertThat(plan.operations().get(0).presentationOnly()).isTrue();
        assertThat(plan.operations().get(0).blockId()).isEqualTo("sales_chart");
        assertThat(plan.operations().get(0).value()).isEqualTo("line");
    }

    @Test
    void dataPlanRequiresQueryAndCarriesDatasetId() {
        AiReportRevisionPlan plan = AiReportRevisionPlan.parse(AiReportRevisionFixture.DATA_PLAN);

        assertThat(plan.requiresQuery()).isTrue();
        assertThat(plan.queryDatasetIds()).containsExactly(81L);
        assertThat(plan.operations().get(0).requiresQuery()).isTrue();
        assertThat(plan.operations().get(0).metric()).isEqualTo("total_net_amount");
    }

    @Test
    void rejectsUnknownOperationCode() {
        assertInvalid(assertThatThrownBy(() -> AiReportRevisionPlan.parse(
                        "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"DROP_DATASET\",\"value\":\"x\"}]}"))
                .actual());
    }

    @Test
    void rejectsMissingRequiredParameters() {
        // ADD_METRIC 少了 metric：口径不完整，不能"猜一个指标"
        assertInvalid(assertThatThrownBy(() -> AiReportRevisionPlan.parse(
                        "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"ADD_METRIC\",\"datasetId\":\"81\"}]}"))
                .actual());
        // SET_CHART_FIELD 少了 field：不知道改的是哪个绑定
        assertInvalid(assertThatThrownBy(
                        () -> AiReportRevisionPlan.parse(
                                "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"SET_CHART_FIELD\",\"blockId\":\"c\",\"value\":\"x\"}]}"))
                .actual());
        // REMOVE_BLOCK 少了 blockId
        assertInvalid(assertThatThrownBy(() -> AiReportRevisionPlan.parse(
                        "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"REMOVE_BLOCK\"}]}"))
                .actual());
    }

    @Test
    void rejectsUnknownKeysAndWrongSchemaVersion() {
        assertInvalid(assertThatThrownBy(
                        () -> AiReportRevisionPlan.parse(
                                "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"SET_TITLE\",\"value\":\"x\",\"script\":\"alert(1)\"}]}"))
                .actual());
        assertInvalid(assertThatThrownBy(() -> AiReportRevisionPlan.parse(
                        "{\"schemaVersion\":\"2.0\",\"operations\":[{\"op\":\"SET_TITLE\",\"value\":\"x\"}]}"))
                .actual());
        assertInvalid(
                assertThatThrownBy(() -> AiReportRevisionPlan.parse("not-json")).actual());
        assertInvalid(assertThatThrownBy(() -> AiReportRevisionPlan.parse(null)).actual());
        assertInvalid(
                assertThatThrownBy(() -> AiReportRevisionPlan.parse("{\"schemaVersion\":\"1.0\",\"operations\":[]}"))
                        .actual());
    }

    @Test
    void rejectsNonNumericDatasetId() {
        // 数据集编号不是数字：结构能过，取数据集编号时被拒（模型在编造来源）
        AiReportRevisionPlan plan = AiReportRevisionPlan.parse(
                "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"SET_GRAIN\",\"datasetId\":\"dset_sales\",\"grain\":\"WEEK\"}]}");
        assertInvalid(assertThatThrownBy(plan::queryDatasetIds).actual());
    }

    @Test
    void rejectsOperationFlood() {
        StringBuilder operations = new StringBuilder();
        for (int index = 0; index < AiReportRevisionPlan.MAX_OPERATIONS + 1; index++) {
            operations
                    .append(index == 0 ? "" : ",")
                    .append("{\"op\":\"SET_TITLE\",\"value\":\"t")
                    .append(index)
                    .append("\"}");
        }
        assertInvalid(assertThatThrownBy(() ->
                        AiReportRevisionPlan.parse("{\"schemaVersion\":\"1.0\",\"operations\":[" + operations + "]}"))
                .actual());
    }

    @Test
    void parsesOperationParametersUsedByPatcher() {
        AiReportRevisionPlan plan = AiReportRevisionPlan.parse("{\"schemaVersion\":\"1.0\",\"operations\":["
                + "{\"op\":\"SET_TABLE_COLUMNS\",\"blockId\":\"t\",\"value\":\"customer_name, total_net_amount\"},"
                + "{\"op\":\"SET_PAGE_SIZE\",\"blockId\":\"t\",\"value\":\"20\"},"
                + "{\"op\":\"SET_THEME\",\"value\":\"thm_dark\",\"field\":\"3\"}]}");

        assertThat(plan.operations().get(0).tableFields()).containsExactly("customer_name", "total_net_amount");
        assertThat(plan.operations().get(1).pageSize()).isEqualTo(20);
        assertThat(plan.operations().get(2).themeRevision()).isEqualTo(3);
        assertThat(plan.requiresQuery()).isFalse();
    }

    @Test
    void rejectsMalformedNumericValues() {
        AiReportRevisionPlan plan = AiReportRevisionPlan.parse(
                "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"SET_PAGE_SIZE\",\"blockId\":\"t\",\"value\":\"many\"}]}");
        assertInvalid(
                assertThatThrownBy(() -> plan.operations().get(0).pageSize()).actual());

        AiReportRevisionPlan theme = AiReportRevisionPlan.parse(
                "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"SET_THEME\",\"value\":\"thm_dark\",\"field\":\"v3\"}]}");
        assertInvalid(assertThatThrownBy(() -> theme.operations().get(0).themeRevision())
                .actual());

        // 表格列值为空白：等于"没有列"，解析阶段即拒绝
        assertInvalid(assertThatThrownBy(
                        () -> AiReportRevisionPlan.parse(
                                "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"SET_TABLE_COLUMNS\",\"blockId\":\"t\",\"value\":\" \"}]}"))
                .actual());
    }
}
