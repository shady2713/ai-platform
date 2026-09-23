package com.basicframework.module.ai.testfixture;

import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionResultDTO;
import java.util.List;
import java.util.Map;

/**
 * 报表对话修改固定夹具（R05）：基础版本（规格 + 版本数据 + 依赖）、修订计划样例与受控查询结果。
 *
 * <p>为什么做成夹具而不是每个测试各写一份 JSON：修订的结论（分类、差异、是否查库）依赖"同一份基础版本"，
 * 散落的字面量会让"同一条指令同一种改法"无法回归比对。生产不引用本类；
 * 集成测试用同一份夹具驱动脚本化模型。
 */
public final class AiReportRevisionFixture {

    /** 基础版本依赖的资源（A03 词表：DATASET + dset_ 前缀的资源键）。 */
    public static final String DATASET_CODE = "sales_demo";

    /** 基础版本规格：文本 + 图表 + 表格三块，一个数据集引用（可解析计划）。 */
    public static final String BASE_SPEC =
            """
            {"schemaVersion":"1.0",
             "title":"8 月销售",
             "themeRef":{"themeId":"thm_default","revision":1},
             "layout":{"columns":12,"gap":16,"items":[
               {"blockId":"intro","row":0,"column":0,"span":12},
               {"blockId":"sales_chart","row":1,"column":0,"span":7},
               {"blockId":"sales_table","row":1,"column":7,"span":5}]},
             "blocks":[
               {"id":"intro","title":"统计口径","type":"text","text":"2026 年 8 月、华东、已付款订单，按客户汇总净额。"},
               {"id":"sales_chart","title":"客户净销售额","type":"chart","datasetRef":"sales_result",
                "chart":{"chartType":"column","categoryField":"customer_name","valueField":"total_net_amount","legend":false}},
               {"id":"sales_table","title":"客户明细","type":"table","datasetRef":"sales_result",
                "columns":[{"field":"customer_name","label":"客户","format":"TEXT"},
                           {"field":"total_net_amount","label":"净销售额","format":"CURRENCY"}],
                "pageSize":10}],
             "datasetRefs":[
               {"id":"sales_result","resultRef":"run_r05_demo/result/0","queryRef":"sales_query",
                "columns":[{"field":"customer_name","label":"客户","dataType":"STRING"},
                           {"field":"total_net_amount","label":"净销售额","dataType":"DECIMAL","unit":"CURRENCY"}],
                "rowCount":2,"completeness":"COMPLETE"}],
             "queryRefs":[
               {"id":"sales_query","plan":{"schemaVersion":"1.0","datasetId":"dset_sales_demo","datasetVersion":1,
                 "metrics":["total_net_amount"],"dimensions":["customer_name"],"filters":[],"orderBy":[],"limit":10}}],
             "sources":[
               {"id":"sales_source","kind":"DATASET","resourceId":"sales_demo","resourceVersion":1,
                "queryRef":"sales_query","description":"合成销售数据集（固定夹具）"}]}
            """;

    /** 基础版本数据：结果行 + 块数据（形状由 R05 固化，渲染与再绑定都读它）。 */
    public static final String BASE_DATA =
            """
            {"kind":"REPORT","title":"8 月销售","specJson":%s,
             "datasets":[{"datasetRef":"sales_result",
               "columns":[{"field":"customer_name","label":"客户","dataType":"STRING"},
                          {"field":"total_net_amount","label":"净销售额","dataType":"DECIMAL","unit":"CURRENCY"}],
               "rows":[{"customer_name":"客户二","total_net_amount":"450.00"},
                       {"customer_name":"客户一","total_net_amount":"290.00"}],
               "completeness":"COMPLETE"}],
             "data":[
               {"blockId":"intro","type":"text","verified":false,"value":null,"rows":[],"points":[]},
               {"blockId":"sales_chart","type":"chart","verified":true,"value":null,"rows":[],
                "points":[{"customer_name":"客户二","total_net_amount":"450.00"},
                          {"customer_name":"客户一","total_net_amount":"290.00"}]},
               {"blockId":"sales_table","type":"table","verified":true,"value":null,
                "rows":[{"customer_name":"客户二","total_net_amount":"450.00"},
                        {"customer_name":"客户一","total_net_amount":"290.00"}],
                "points":[]}],
             "sources":[{"datasetRef":"sales_result","queryRef":"sales_query","resultRef":"run_r05_demo/result/0",
                         "rowCount":2,"completeness":"COMPLETE"}],
             "notes":[]}
            """
                    .formatted(quoted(BASE_SPEC));

    /** 基础版本依赖清单（R04 的 sources_json 口径）。 */
    public static final String BASE_SOURCES =
            "[{\"resourceType\":\"DATASET\",\"resourceKey\":\"dset_" + DATASET_CODE + "\"}]";

    /** 展示类修订计划：只换图表类型（不查库）。 */
    public static final String PRESENTATION_PLAN =
            """
            {"schemaVersion":"1.0","operations":[{"op":"SET_CHART_TYPE","blockId":"sales_chart","value":"line"}]}
            """;

    /** 展示类修订计划：换图表绑定字段 + 改表格列（重新投影，仍不查库）。 */
    public static final String REPROJECTION_PLAN =
            """
            {"schemaVersion":"1.0","operations":[
              {"op":"SET_CHART_FIELD","blockId":"sales_chart","field":"valueField","value":"total_net_amount"},
              {"op":"SET_TABLE_COLUMNS","blockId":"sales_table","value":"customer_name"}]}
            """;

    /** 数据类修订计划：新增按客户聚合的指标（必须受控查询）。 */
    public static final String DATA_PLAN =
            """
            {"schemaVersion":"1.0","operations":[
              {"op":"ADD_METRIC","datasetId":"81","metric":"total_net_amount","value":"客户净销售额合计"}]}
            """;

    /** 数据类修订计划：改时间粒度（同一数据集重查，沿用原数据集引用）。 */
    public static final String GRAIN_PLAN =
            """
            {"schemaVersion":"1.0","operations":[{"op":"SET_GRAIN","datasetId":"81","grain":"WEEK"}]}
            """;

    /** 受控查询结果：按当前权限重新执行后的真实结果（客户二在前）。 */
    public static AiRunQueryExecutionResultDTO queryResult() {
        return new AiRunQueryExecutionResultDTO()
                .setKind(AiRunQueryExecutionResultDTO.KIND_PLAN)
                .setDatasetId(81L)
                .setDatasetCode(DATASET_CODE)
                .setDatasetVersionId(91L)
                .setDatasetVersionNo(1)
                .setSchemaHash("a".repeat(64))
                .setPlanHash("b".repeat(64))
                .setPlanJson(
                        "{\"schemaVersion\":\"1.0\",\"datasetId\":\"dset_" + DATASET_CODE + "\",\"datasetVersion\":1,"
                                + "\"metrics\":[\"total_net_amount\"],\"dimensions\":[\"customer_name\"],"
                                + "\"filters\":[],\"orderBy\":[],\"limit\":10}")
                .setResultRef("plan_bbbbbbbbbbbb")
                .setColumns(List.of(
                        new AiRunQueryExecutionResultDTO.Column("customer_name", "客户", "STRING", null),
                        new AiRunQueryExecutionResultDTO.Column("total_net_amount", "净销售额", "DECIMAL", "CURRENCY")))
                .setRows(List.of(
                        Map.of("customer_name", "客户二", "total_net_amount", "450.00"),
                        Map.of("customer_name", "客户一", "total_net_amount", "290.00")))
                .setRowCount(2)
                .setCompleteness(AiRunQueryExecutionResultDTO.COMPLETE)
                .setTruncated(false);
    }

    /** 受控查询返回澄清（问题有歧义：不猜口径、不建版本）。 */
    public static AiRunQueryExecutionResultDTO clarification() {
        return new AiRunQueryExecutionResultDTO()
                .setKind(AiRunQueryExecutionResultDTO.KIND_CLARIFICATION)
                .setDatasetId(81L)
                .setDatasetCode(DATASET_CODE)
                .setClarificationQuestion("“销售额”指净销售额还是含退款金额？")
                .setClarificationReason("AMBIGUOUS")
                .setClarificationCandidates(
                        List.of(new AiRunQueryExecutionResultDTO.Candidate("total_net_amount", "净销售额")));
    }

    private static String quoted(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private AiReportRevisionFixture() {}
}
