package com.basicframework.module.ai.domain.result;

import java.util.List;

/**
 * 报表结果块（R03）：报表生成的**唯一产物**，进入会话/报表页的只是它。
 *
 * <p>为什么把规格与数据分开放在一个块里：渲染需要"结构（ReportSpec）+ 数据（绑定结果）"两份东西，
 * 但两者来源不同——规格来自模型，数据来自执行结果。块里分开存，且**来源列表逐项可追溯**
 * （来源 → 查询引用 → 运行结果标识），这样"数字是从哪来的"永远能回答。
 *
 * <p>{@code notes} 承载"没有数据""部分降级"之类的说明；说明文本不参与数字计算。
 */
public record AiReportResultBlock(
        String kind,
        String title,
        String specJson,
        List<BoundBlockData> data,
        List<SourceRef> sources,
        List<String> notes) {

    /** 结果块类型（会话/报表页按它分派渲染器）。 */
    public static final String KIND_REPORT = "REPORT";

    public AiReportResultBlock {
        data = data == null ? List.of() : List.copyOf(data);
        sources = sources == null ? List.of() : List.copyOf(sources);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** 绑定后的块数据（与 ReportSpec 里的块一一对应）。 */
    public record BoundBlockData(
            String blockId,
            String type,
            boolean verified,
            Object value,
            List<java.util.Map<String, Object>> rows,
            List<java.util.Map<String, Object>> points) {

        public BoundBlockData {
            rows = rows == null ? List.of() : List.copyOf(rows);
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    /** 来源：数据集引用 → 查询引用 → 运行结果标识（可一路追溯到执行）。 */
    public record SourceRef(String datasetRef, String queryRef, String resultRef, int rowCount, String completeness) {}
}
