package com.basicframework.module.ai.service.report.validation;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_BINDING_MISMATCH;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_REFERENCE_INVALID;

import com.basicframework.module.ai.domain.report.AiReportSpec;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报表数据绑定（R01）：把规格里的声明绑到**真实执行结果**上。
 *
 * <p>为什么绑定要单独一步：ReportSpec 是模型产物，它声明的列、行数与完整性都只是"说法"。
 * 绑定用运行结果（查询执行产物）逐项核对：
 * <ul>
 *   <li><b>列集合</b>：规格声明的每个列都必须在结果里存在（多出来的列被忽略，不进界面）；</li>
 *   <li><b>行数</b>：声明 rowCount 必须等于结果行数（模型不能"少报"或"多报"行）；</li>
 *   <li><b>完整性</b>：声明 completeness 必须等于结果的完整性（PARTIAL 不能被写成 COMPLETE）；</li>
 *   <li><b>数字来源</b>：指标块与图表数值取的是**结果里的真实值**，本绑定不做任何计算，
 *       只做"取值 + 类型转换"；金额一律十进制字符串语义（不经过二进制浮点）。</li>
 * </ul>
 *
 * <p>文本块与数字分开标注：文本块 {@code verified=false}（说明性文字不伪装成确定性核验），
 * 数字块 {@code verified=true} 且带来源（数据集引用 + 查询引用 + 列名），审计能一路回到执行结果。
 */
public class AiReportDataBinder {

    /** 执行结果：列名 + 行 + 完整性（由查询执行链路提供）。 */
    public record ExecutionResult(
            String resultRef, List<String> columns, List<Map<String, Object>> rows, String completeness) {

        public ExecutionResult {
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /** 绑定结果：块 → 数据（表格行 / 图表点 / 指标值）+ 每块是否经过确定性核验。 */
    public record BoundReport(String title, List<BoundBlock> blocks) {}

    /** 绑定后的块：type + 数据 + 来源 + 是否确定性核验。 */
    public record BoundBlock(
            String blockId,
            String type,
            String title,
            boolean verified,
            String sourceQueryRef,
            String sourceResultRef,
            Object value,
            List<Map<String, Object>> rows,
            List<Map<String, Object>> points,
            String note) {}

    /**
     * 绑定：规格 + 执行结果（按 datasetRef.id 提供）。
     *
     * <p>缺少某个数据集引用的执行结果时**拒绝**（而不是留空）：模型声明的来源必须真实存在。
     */
    public BoundReport bind(AiReportSpec spec, Map<String, ExecutionResult> results) {
        if (spec == null || results == null) {
            throw exception(AI_REPORT_REFERENCE_INVALID);
        }
        Map<String, ExecutionResult> verified = new LinkedHashMap<>();
        for (AiReportSpec.DatasetRef dataset : spec.datasetRefs()) {
            ExecutionResult result = results.get(dataset.id());
            if (result == null) {
                throw exception(AI_REPORT_REFERENCE_INVALID);
            }
            verifyDeclaration(dataset, result);
            verified.put(dataset.id(), result);
        }
        List<BoundBlock> bound = new ArrayList<>();
        for (AiReportSpec.Block block : spec.blocks()) {
            bound.add(bindBlock(block, verified));
        }
        return new BoundReport(spec.title(), List.copyOf(bound));
    }

    /** 声明与真实结果核对：列存在、行数一致、完整性一致。 */
    private void verifyDeclaration(AiReportSpec.DatasetRef dataset, ExecutionResult result) {
        Set<String> actualColumns = new java.util.LinkedHashSet<>(result.columns());
        for (AiReportSpec.ResultColumn column : dataset.columns()) {
            if (!actualColumns.contains(column.field())) {
                throw exception(AI_REPORT_BINDING_MISMATCH);
            }
        }
        if (dataset.rowCount() != result.rows().size()) {
            // 行数对不上：模型可能在"少报/多报"数据量
            throw exception(AI_REPORT_BINDING_MISMATCH);
        }
        if (!dataset.completeness().equals(result.completeness())) {
            // 完整性对不上：把 PARTIAL 写成 COMPLETE 是典型的数据失真
            throw exception(AI_REPORT_BINDING_MISMATCH);
        }
    }

    private BoundBlock bindBlock(AiReportSpec.Block block, Map<String, ExecutionResult> results) {
        return switch (block.type()) {
            case "metric" -> {
                ExecutionResult result = results.get(block.datasetRef());
                Map<String, Object> row = result.rows().get(block.rowIndex());
                Object raw = row.get(block.metricField());
                yield new BoundBlock(
                        block.id(),
                        block.type(),
                        block.title(),
                        true,
                        null,
                        result.resultRef(),
                        toDecimal(raw),
                        List.of(),
                        List.of(),
                        null);
            }
            case "table" -> {
                ExecutionResult result = results.get(block.datasetRef());
                List<Map<String, Object>> rows = result.rows().stream()
                        .map(row -> project(
                                row,
                                block.columns().stream()
                                        .map(AiReportSpec.TableColumn::field)
                                        .toList()))
                        .toList();
                yield new BoundBlock(
                        block.id(),
                        block.type(),
                        block.title(),
                        true,
                        null,
                        result.resultRef(),
                        null,
                        rows,
                        List.of(),
                        null);
            }
            case "chart" -> {
                ExecutionResult result = results.get(block.datasetRef());
                List<String> fields = new ArrayList<>();
                fields.add(block.chart().categoryField());
                fields.add(block.chart().valueField());
                if (block.chart().seriesField() != null) {
                    fields.add(block.chart().seriesField());
                }
                List<Map<String, Object>> points =
                        result.rows().stream().map(row -> project(row, fields)).toList();
                yield new BoundBlock(
                        block.id(),
                        block.type(),
                        block.title(),
                        true,
                        null,
                        result.resultRef(),
                        null,
                        List.of(),
                        points,
                        null);
            }
            default ->
                new BoundBlock(
                        block.id(),
                        block.type(),
                        block.title(),
                        // 文本说明不经过确定性核验：界面与接口都要如实标注
                        false,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        "文本说明未经过确定性核验，数字以绑定结果为准");
        };
    }

    private static Map<String, Object> project(Map<String, Object> row, List<String> fields) {
        Map<String, Object> projected = new LinkedHashMap<>();
        for (String field : fields) {
            projected.put(field, row.get(field));
        }
        return projected;
    }

    /** 金额/数值统一成十进制（不经过二进制浮点）。 */
    private static Object toDecimal(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(String.valueOf(raw));
    }
}
