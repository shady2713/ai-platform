package com.basicframework.server.fixtures.ai;

import java.util.List;
import java.util.Map;

/**
 * 单系统查询黄金集夹具（D11）：**同一套合成销售系统**的数据、口径与期望值。
 *
 * <p>为什么把数据、DDL 与期望值放在一个夹具里：SQL 入口（D06 编译执行）与 API 入口（D07 归一化）
 * 必须对**同一个系统**给出**同一组数字**（740/450/290/190）；把口径写在夹具里，
 * 两个入口各自断言同一常量，避免"两边各写一套期望值"。
 *
 * <p>口径（与蓝图 §5.3 的示例一致）：
 * <ul>
 *   <li>净额 = 订单金额 − 退款金额（退款为 NULL 表示无退款）；</li>
 *   <li>回款是**独立指标**：订单与回款是多对多，直接相加会放大金额，因此分别聚合（AT-034）；</li>
 *   <li>时间口径：按 Asia/Shanghai 的 [月初, 次月初) 半开区间（AT-033）；</li>
 *   <li>权限：Alice 只见自己的客户集合（C001），华东范围含 C001/C002（AT-031/032）。</li>
 * </ul>
 */
public final class AiGoldenSetFixture {

    /** 来源对象（审核视图）：已按订单预聚合回款，避免与明细直接相加造成放大。 */
    public static final String SOURCE_OBJECT = "golden_catalog.v_sales_golden";

    /** 回款聚合视图（同一套数据的另一入口：回款明细）。 */
    public static final String PAYMENT_OBJECT = "golden_catalog.v_payments_golden";

    /** 期望值：华东 8 月净额合计。 */
    public static final String EXPECTED_EAST_TOTAL = "740.00";

    /** 期望值：C002（上海）8 月净额。 */
    public static final String EXPECTED_C002_NET = "450.00";

    /** 期望值：C001（杭州）8 月净额。 */
    public static final String EXPECTED_C001_NET = "290.00";

    /** 期望值：C001 8 月回款（独立指标，不与净额相加）。 */
    public static final String EXPECTED_C001_PAYMENT = "190.00";

    /** 数据集标识（计划里的 dset_ 前缀由它派生）。 */
    public static final String DATASET_CODE = "golden-sales";

    /** 语义版本号（API 入口与计划里引用）。 */
    public static final Integer DATASET_VERSION_NO = 1;

    /** 订单与退款明细（金额都是十进制字符串：夹具与消费方都不得转浮点）。 */
    public static final List<String> ORDERS_DDL = List.of(
            "CREATE TABLE golden_catalog.orders ("
                    + "id BIGINT PRIMARY KEY, customer_id VARCHAR(16) NOT NULL, customer_name VARCHAR(64) NOT NULL,"
                    + " region VARCHAR(16) NOT NULL, order_time DATETIME NOT NULL,"
                    + " amount DECIMAL(18,2) NOT NULL, refund_amount DECIMAL(18,2) NULL)",
            "CREATE TABLE golden_catalog.payments ("
                    + "id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, paid_at DATETIME NOT NULL,"
                    + " amount DECIMAL(18,2) NULL)",
            // 审核视图：订单与回款分别聚合后再关联（回款按订单聚合，不放大订单行）
            "CREATE VIEW golden_catalog.v_sales_golden AS"
                    + " SELECT o.id AS order_id, o.customer_id AS customer_id, o.customer_name AS customer_name,"
                    + " o.region AS region, o.order_time AS order_time,"
                    + " o.amount - COALESCE(o.refund_amount, 0) AS net_amount"
                    + " FROM golden_catalog.orders o",
            "CREATE VIEW golden_catalog.v_payments_golden AS"
                    + " SELECT p.order_id AS order_id, o.customer_id AS customer_id,"
                    + " MAX(p.paid_at) AS paid_at, SUM(p.amount) AS payment_amount"
                    + " FROM golden_catalog.payments p JOIN golden_catalog.orders o ON o.id = p.order_id"
                    + " GROUP BY p.order_id, o.customer_id");

    /** 数据（8 月两笔 + 9 月边界一笔 + NULL 退款 + 0.00 回款）。 */
    public static final List<String> DATA_DML = List.of(
            // C001（杭州，华东）：200.00 + 150.00 − 60.00 退款 = 290.00
            "INSERT INTO golden_catalog.orders (id, customer_id, customer_name, region, order_time, amount,"
                    + " refund_amount) VALUES"
                    + " (1, 'C001', 'alice', '杭州', '2026-08-01 00:00:00', 200.00, 60.00),"
                    + " (2, 'C001', 'alice', '杭州', '2026-08-31 23:59:59', 150.00, NULL),"
                    // C002（上海，华东）：500.00 − 50.00 = 450.00
                    + " (3, 'C002', 'bob', '上海', '2026-08-15 12:00:00', 500.00, 50.00),"
                    // 时间边界：9 月 1 日 00:00:00（+08:00）必须被 8 月窗口排除
                    + " (4, 'C001', 'alice', '杭州', '2026-09-01 00:00:00', 999.00, NULL)",
            // 回款（独立指标）：C001 190.00（含 0.00 与 NULL 各一条，验证空值口径）
            "INSERT INTO golden_catalog.payments (id, order_id, paid_at, amount) VALUES"
                    + " (1, 1, '2026-08-02 10:00:00', 190.00),"
                    + " (2, 1, '2026-08-03 10:00:00', 0.00),"
                    + " (3, 2, '2026-08-04 10:00:00', NULL)");

    /** 计划里的数据集定义（字段/指标/维度；来源是审核视图）。 */
    public static final String DEFINITION =
            """
            {"grain": "一行一单",
             "time": {"field": "order_time", "granularity": "DAY", "timezone": "Asia/Shanghai"},
             "fields": [
               {"name": "order_id", "sourceColumn": "order_id", "type": "NUMBER", "unit": "COUNT",
                "visibility": "PUBLIC"},
               {"name": "customer", "sourceColumn": "customer_id", "type": "STRING", "visibility": "INTERNAL"},
               {"name": "customer_display_name", "sourceColumn": "customer_name", "type": "STRING",
                "visibility": "INTERNAL"},
               {"name": "region", "sourceColumn": "region", "type": "STRING", "visibility": "INTERNAL"},
               {"name": "order_time", "sourceColumn": "order_time", "type": "DATETIME", "visibility": "INTERNAL"},
               {"name": "net_amount", "sourceColumn": "net_amount", "type": "DECIMAL", "unit": "CURRENCY",
                "aliases": ["销售额"], "visibility": "INTERNAL"}],
             "metrics": [{"name": "total_net_amount", "field": "net_amount", "aggregation": "SUM",
                          "unit": "CURRENCY", "visibility": "INTERNAL"}],
             "dimensions": [{"name": "customer_name", "field": "customer_display_name"}]}
            """;

    /** 回款数据集定义（同一系统的另一入口，用于 AT-034 的"不重复"验证）。 */
    public static final String PAYMENT_DEFINITION =
            """
            {"grain": "一行一回款",
             "time": {"field": "paid_at", "granularity": "DAY", "timezone": "Asia/Shanghai"},
             "fields": [
               {"name": "order_id", "sourceColumn": "order_id", "type": "NUMBER", "unit": "COUNT",
                "visibility": "PUBLIC"},
               {"name": "customer", "sourceColumn": "customer_id", "type": "STRING", "visibility": "INTERNAL"},
               {"name": "paid_at", "sourceColumn": "paid_at", "type": "DATETIME", "visibility": "INTERNAL"},
               {"name": "payment_amount", "sourceColumn": "payment_amount", "type": "DECIMAL", "unit": "CURRENCY",
                "visibility": "INTERNAL"}],
             "metrics": [{"name": "total_payment", "field": "payment_amount", "aggregation": "SUM",
                          "unit": "CURRENCY", "visibility": "INTERNAL"}],
             "dimensions": []}
            """;

    /** 华东 8 月计划（按客户聚合净额，降序 → 450.00 在前、290.00 在后）。 */
    public static final String EAST_AUGUST_PLAN =
            """
            {"schemaVersion": "1.0",
             "datasetId": "dset_golden-sales",
             "datasetVersion": 1,
             "metrics": ["total_net_amount"],
             "dimensions": ["customer_name"],
             "filters": [],
             "timeRange": {"field": "order_time", "startInclusive": "2026-08-01T00:00:00+08:00",
                           "endExclusive": "2026-09-01T00:00:00+08:00", "timezone": "Asia/Shanghai"},
             "orderBy": [{"field": "total_net_amount", "direction": "DESC"}],
             "limit": 10}
            """;

    /** 回款计划（8 月，C001 范围）。 */
    public static final String C001_PAYMENT_PLAN =
            """
            {"schemaVersion": "1.0",
             "datasetId": "dset_golden-payments",
             "datasetVersion": 1,
             "metrics": ["total_payment"],
             "dimensions": [],
             "filters": [],
             "timeRange": {"field": "paid_at", "startInclusive": "2026-08-01T00:00:00+08:00",
                           "endExclusive": "2026-09-01T00:00:00+08:00", "timezone": "Asia/Shanghai"},
             "orderBy": [],
             "limit": 10}
            """;

    /** API 入口的条目（与 SQL 入口同一系统：两页 + 一条 9 月边界行）。 */
    public static final Map<String, List<String>> API_PAGES = Map.of(
            "page1",
            List.of(
                    "{\"customer_name\":\"bob\",\"net_amount\":\"300.00\"}",
                    "{\"customer_name\":\"alice\",\"net_amount\":\"200.00\"}"),
            "page2",
            List.of(
                    "{\"customer_name\":\"bob\",\"net_amount\":\"150.00\"}",
                    "{\"customer_name\":\"alice\",\"net_amount\":\"90.00\"}"),
            // 截断样例：页数上限命中 → PARTIAL（不得宣称完整统计，AT-039）
            "partial",
            List.of("{\"customer_name\":\"bob\",\"net_amount\":\"300.00\"}"));

    /** 计划（API 入口）：按客户聚合净额，降序。 */
    public static final String API_PLAN = EAST_AUGUST_PLAN;

    private AiGoldenSetFixture() {}
}
