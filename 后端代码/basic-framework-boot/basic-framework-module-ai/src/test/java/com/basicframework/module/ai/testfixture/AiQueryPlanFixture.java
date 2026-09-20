package com.basicframework.module.ai.testfixture;

import com.basicframework.module.ai.domain.query.ResolvedDatasetVersion;
import com.basicframework.module.ai.domain.semantic.AiDatasetDefinition;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 查询计划固定评测夹具（D05）：语义定义、数据集版本与模型输出的**唯一**测试来源。
 *
 * <p>为什么做成夹具而不是每个测试各写一份 JSON：规划链路的结论（计划哈希、澄清候选、
 * 修复次数）依赖"同一份输入"，散落的字面量会让"同问题同结论"无法回归比对。
 * 生产不引用本类；集成测试用同一份夹具驱动脚本化模型。
 */
public final class AiQueryPlanFixture {

    /** 固定时钟：所有时间边界判定基于它（2026-09-20T12:00:00Z）。 */
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);

    /** 语义定义：与 docs/contracts/ai/samples/query-plan.valid.json 同形（dset_/字段码/时间口径）。 */
    public static final String DEFINITION =
            """
            {"grain": "一行一单",
             "time": {"field": "created_at", "granularity": "DAY", "timezone": "Asia/Shanghai"},
             "fields": [
               {"name": "order_id", "sourceColumn": "id", "type": "NUMBER", "unit": "COUNT",
                "aliases": ["订单号"], "visibility": "PUBLIC"},
               {"name": "amount", "sourceColumn": "amount", "type": "DECIMAL", "unit": "CURRENCY",
                "aliases": ["销售额"], "visibility": "INTERNAL"},
               {"name": "created_at", "sourceColumn": "created_at", "type": "DATETIME",
                "visibility": "INTERNAL"},
               {"name": "region", "sourceColumn": "region", "type": "STRING", "aliases": ["区域"],
                "visibility": "INTERNAL"},
               {"name": "customer", "sourceColumn": "customer_id", "type": "STRING",
                "visibility": "INTERNAL"},
               {"name": "status", "sourceColumn": "status", "type": "ENUM",
                "enumValues": ["PAID", "REFUNDED"], "visibility": "RESTRICTED",
                "permission": "ai:dataset:field:status"}],
             "metrics": [{"name": "net_amount", "field": "amount", "aggregation": "SUM",
                          "unit": "CURRENCY", "visibility": "INTERNAL"}],
             "dimensions": [{"name": "customer_name", "field": "customer"}]}
            """;

    /** 合规计划（模型输出 PLAN 的载荷部分）。 */
    public static final String VALID_PLAN =
            """
            {"schemaVersion": "1.0",
             "datasetId": "dset_it-query-orders",
             "datasetVersion": 1,
             "metrics": ["net_amount"],
             "dimensions": ["customer_name"],
             "filters": [{"field": "region", "operator": "EQ", "value": "EAST"}],
             "timeRange": {"field": "created_at", "startInclusive": "2026-08-01T00:00:00+08:00",
                           "endExclusive": "2026-09-01T00:00:00+08:00", "timezone": "Asia/Shanghai"},
             "orderBy": [{"field": "net_amount", "direction": "DESC"}],
             "limit": 10}
            """;

    private AiQueryPlanFixture() {}

    /** 默认数据集版本（全部字段授权）。 */
    public static ResolvedDatasetVersion dataset() {
        return dataset(List.of());
    }

    /** 指定授权字段集合的数据集版本。 */
    public static ResolvedDatasetVersion dataset(List<String> allowedFieldCodes) {
        return new ResolvedDatasetVersion(
                81L,
                "it-query-orders",
                91L,
                1,
                "a".repeat(64),
                "it_query.orders",
                AiDatasetDefinition.parse(DEFINITION),
                allowedFieldCodes);
    }

    /** 数据集版本编号（夹具固定值）。 */
    public static final Long DATASET_ID = 81L;

    /** 语义版本编号（夹具固定值）。 */
    public static final Long DATASET_VERSION_ID = 91L;
}
