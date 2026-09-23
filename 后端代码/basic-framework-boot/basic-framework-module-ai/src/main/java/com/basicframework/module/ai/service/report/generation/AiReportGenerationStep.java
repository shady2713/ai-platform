package com.basicframework.module.ai.service.report.generation;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_MODEL_UNAVAILABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_SPEC_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.domain.report.AiReportSpec;
import com.basicframework.module.ai.domain.result.AiReportResultBlock;
import com.basicframework.module.ai.service.report.generation.dto.AiReportGenerationResultDTO;
import com.basicframework.module.ai.service.report.validation.AiReportDataBinder;
import com.basicframework.module.ai.service.report.validation.AiReportSpecValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 自然语言报表生成步骤（R03）：需求 + 查询结果 → 受控 ReportSpec → 结果块。
 *
 * <p>三步的边界（对应卡片逐步实施）：
 * <ol>
 *   <li><b>受控生成</b>：提示词里只放**服务端提供的列目录**（来自真实执行结果），
 *       模型只能引用目录里的数据集与列——编造的列在 R01 的校验里就过不去；</li>
 *   <li><b>有限修复</b>：解析/校验/绑定失败时最多修复 {@link #MAX_REPAIR_ATTEMPTS} 次，
 *       每次只回传**稳定错误码**（不回传数据正文）；次数用尽即失败，不无限重试；</li>
 *   <li><b>结果块与来源</b>：产物是 {@link AiReportResultBlock}——规格、绑定数据、
 *       可追溯来源（数据集引用 → 查询引用 → 运行结果标识）与说明分开存放；
 *       空数据时产出**空报表 + 说明**，不编造行。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReportGenerationStep {

    /** 修复次数上限（含首次生成之外的重试次数）。 */
    public static final int MAX_REPAIR_ATTEMPTS = 2;

    /** 空数据的说明文案。 */
    public static final String NOTE_NO_DATA = "查询结果为空，未生成图表与明细，仅保留统计口径说明";

    /** 报表模型（未装配时按稳定原因码失败，而不是让整个应用上下文启动不了）。 */
    private final org.springframework.beans.factory.ObjectProvider<AiReportSpecModel> modelProvider;

    private final AiReportSpecValidator validator;

    private final AiReportDataBinder binder;

    /**
     * 生成报表。
     *
     * @param requirement 用户需求（自然语言）
     * @param results     真实执行结果（按 datasetRef.id）
     * @param catalog     可用结果目录（服务端提供的列与类型，模型只能引用它）
     */
    public AiReportGenerationResultDTO generate(
            String requirement, Map<String, AiReportDataBinder.ExecutionResult> results, String catalog) {
        if (!StringUtils.hasText(requirement) || results == null || results.isEmpty()) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiReportSpecModel model = modelProvider.getIfAvailable();
        if (model == null) {
            throw exception(AI_REPORT_MODEL_UNAVAILABLE);
        }
        String catalogJson = StringUtils.hasText(catalog) ? catalog : defaultCatalog(results);
        String output = model.generate(requirement, catalogJson);
        int attempts = 1;
        boolean repaired = false;
        String lastErrorCode = null;
        while (attempts <= MAX_REPAIR_ATTEMPTS + 1) {
            try {
                AiReportSpec spec = validator.validate(AiReportSpec.parse(output));
                AiReportDataBinder.BoundReport bound = binder.bind(spec, results);
                return buildResult(spec, bound, attempts, repaired, results);
            } catch (ServiceException failure) {
                lastErrorCode = String.valueOf(failure.getCode());
                if (attempts > MAX_REPAIR_ATTEMPTS) {
                    // 修复次数用尽：如实失败，不返回半成品
                    log.warn("[generate][报表生成失败][attempts={}, lastErrorCode={}]", attempts, lastErrorCode);
                    throw exception(AI_REPORT_SPEC_INVALID);
                }
                repaired = true;
                attempts++;
                output = model.repair(output, lastErrorCode);
            }
        }
        throw exception(AI_REPORT_SPEC_INVALID);
    }

    /** 组装结果块：规格 + 绑定数据 + 可追溯来源 + 说明。 */
    private AiReportGenerationResultDTO buildResult(
            AiReportSpec spec,
            AiReportDataBinder.BoundReport bound,
            int attempts,
            boolean repaired,
            Map<String, AiReportDataBinder.ExecutionResult> results) {
        List<AiReportResultBlock.BoundBlockData> data = new ArrayList<>();
        for (AiReportDataBinder.BoundBlock block : bound.blocks()) {
            data.add(new AiReportResultBlock.BoundBlockData(
                    block.blockId(), block.type(), block.verified(), block.value(), block.rows(), block.points()));
        }
        List<AiReportResultBlock.SourceRef> sources = new ArrayList<>();
        for (AiReportSpec.DatasetRef dataset : spec.datasetRefs()) {
            AiReportDataBinder.ExecutionResult result = results.get(dataset.id());
            sources.add(new AiReportResultBlock.SourceRef(
                    dataset.id(),
                    dataset.queryRef(),
                    result == null ? dataset.resultRef() : result.resultRef(),
                    dataset.rowCount(),
                    dataset.completeness()));
        }
        List<String> notes = new ArrayList<>();
        if (spec.datasetRefs().stream().allMatch(dataset -> dataset.rowCount() == 0)) {
            // 空数据：给空报表 + 说明（不编造行、不画空图）
            notes.add(NOTE_NO_DATA);
        }
        if (repaired) {
            notes.add("模型输出经过修复后通过校验");
        }
        AiReportResultBlock block = new AiReportResultBlock(
                AiReportResultBlock.KIND_REPORT, spec.title(), specJson(spec), data, sources, notes);
        return new AiReportGenerationResultDTO()
                .setBlock(block)
                .setBound(bound)
                .setAttempts(attempts)
                .setRepaired(repaired)
                .setNotes(notes)
                .setGenerated(true);
    }

    /** 规格回写为 JSON（结果块里保存**已校验**的规格，渲染方不需要再解析模型输出）。 */
    private static String specJson(AiReportSpec spec) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("schemaVersion", AiReportSpec.SCHEMA_VERSION);
        canonical.put("title", spec.title());
        canonical.put("themeRef", Map.of("themeId", spec.themeId(), "revision", spec.themeRevision()));
        canonical.put(
                "blocks",
                spec.blocks().stream().map(AiReportGenerationStep::blockJson).toList());
        canonical.put(
                "datasetRefs",
                spec.datasetRefs().stream()
                        .map(AiReportGenerationStep::datasetJson)
                        .toList());
        canonical.put("layout", Map.of("gap", spec.gap(), "items", spec.layout()));
        canonical.put(
                "queryRefs",
                spec.queryRefs().stream()
                        .map(ref -> Map.of("id", ref.id(), "plan", JsonUtils.parseObject(ref.planJson(), Map.class)))
                        .toList());
        canonical.put("sources", spec.sources());
        return JsonUtils.toJsonString(canonical);
    }

    private static Map<String, Object> blockJson(AiReportSpec.Block block) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", block.id());
        json.put("type", block.type());
        json.put("title", block.title());
        if (block.datasetRef() != null) {
            json.put("datasetRef", block.datasetRef());
        }
        if (block.text() != null) {
            json.put("text", block.text());
        }
        if (block.metricField() != null) {
            json.put("binding", Map.of("datasetRef", block.datasetRef(), "field", block.metricField()));
            json.put("rowIndex", block.rowIndex());
            json.put("format", block.format());
            if (block.unit() != null) {
                json.put("unit", block.unit());
            }
        }
        if (block.columns() != null) {
            json.put("columns", block.columns());
            json.put("pageSize", block.pageSize());
        }
        if (block.chart() != null) {
            json.put("chart", block.chart());
        }
        return json;
    }

    private static Map<String, Object> datasetJson(AiReportSpec.DatasetRef dataset) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", dataset.id());
        json.put("resultRef", dataset.resultRef());
        json.put("queryRef", dataset.queryRef());
        json.put("columns", dataset.columns());
        json.put("rowCount", dataset.rowCount());
        json.put("completeness", dataset.completeness());
        return json;
    }

    /** 未提供目录时按真实结果生成（列名 + 类型推断），保证模型只能看到真实列。 */
    private static String defaultCatalog(Map<String, AiReportDataBinder.ExecutionResult> results) {
        List<Map<String, Object>> catalog = new ArrayList<>();
        results.forEach((id, result) -> catalog.add(Map.of(
                "datasetRef", id,
                "resultRef", result.resultRef(),
                "rowCount", result.rows().size(),
                "completeness", result.completeness(),
                "columns", result.columns())));
        return JsonUtils.toJsonString(catalog);
    }
}
