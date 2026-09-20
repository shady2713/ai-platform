package com.basicframework.module.ai.service.query.planner;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_DISABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_VERSION_DRIFTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_VERSION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_VERSION_NOT_PUBLISHED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_VERSION_NOT_VERIFIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_CLARIFICATION_REQUIRED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_DATASET_NOT_ALLOWED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_MODEL_OUTPUT_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_REPAIR_EXHAUSTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_SQL_REJECTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetVersionDO;
import com.basicframework.module.ai.domain.query.AiQueryPlanValidator;
import com.basicframework.module.ai.domain.query.QueryPlanOutcome;
import com.basicframework.module.ai.domain.query.ResolvedDatasetVersion;
import com.basicframework.module.ai.domain.query.ValidatedQueryPlan;
import com.basicframework.module.ai.domain.semantic.AiDatasetDefinition;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.query.planner.dto.AiQueryPlanRequestDTO;
import com.basicframework.module.ai.service.query.planner.dto.AiQueryPlanResultDTO;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 查询规划器实现（D05）。
 *
 * <p>一次调用的完整路径：授权范围校验 → 解析数据集版本（必须已发布且已验证）→
 * 只把授权摘要交给模型 → 运行层判别 PLAN/CLARIFICATION → PLAN 走校验器（结构→授权版本→字段类型→时间→参数）
 * → 失败时在**原授权范围内**有限修复 → 用尽修复次数即失败结束。
 *
 * <p>三条不可越过的线：
 * <ol>
 *   <li><b>不扩大数据集</b>：{@code allowedDatasetIds} 是调用方给出的授权范围，
 *       模型提到别的数据集直接拒绝（403），修复重试复用同一范围；</li>
 *   <li><b>不接受 SQL</b>：模型输出里出现 SQL 片段立即拒绝，不做"试着修复成 SQL"；</li>
 *   <li><b>歧义不猜</b>：措辞命中目录别名（例如"销售额"而目录里叫 net_amount）时返回澄清候选，而不是选一个执行。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AiQueryPlannerImpl implements AiQueryPlanner {

    /** 澄清候选上限（追问要给有限选项，不能把目录整份丢回给用户）。 */
    private static final int MAX_CANDIDATES = 8;

    private final AiDatasetService datasetService;

    private final AiQueryPlanModel model;

    private final AiDatasetSummaryBuilder summaryBuilder;

    /** 固定时钟：摘要里的"当前时间"与时间窗口边界判定都基于它，保证可复现。 */
    private final Clock clock;

    private final AiQueryPlanValidator validator = new AiQueryPlanValidator();

    @Override
    public AiQueryPlanResultDTO plan(AiQueryPlanRequestDTO request) {
        if (request == null
                || request.getDatasetId() == null
                || request.getEndpointId() == null
                || !StringUtils.hasText(request.getQuestion())) {
            throw exception(AI_REQUEST_INVALID);
        }
        requireAllowedDataset(request);
        ResolvedDatasetVersion dataset = resolve(request);
        String prompt = summaryBuilder.buildPrompt(dataset, request.getQuestion(), clock, null);
        String rawOutput = model.propose(request.getEndpointId(), prompt, summaryBuilder.outputSchema());
        Map<String, Object> envelope = parseEnvelope(rawOutput);
        String kind = String.valueOf(envelope.get("kind"));
        if ("CLARIFICATION".equals(kind)) {
            return toClarificationResult(request, dataset, envelope, 1);
        }
        if (!"PLAN".equals(kind)) {
            throw exception(AI_QUERY_MODEL_OUTPUT_INVALID);
        }

        if (envelope.get("plan") == null) {
            throw exception(AI_QUERY_MODEL_OUTPUT_INVALID);
        }
        String planJson = JsonUtils.toJsonString(envelope.get("plan"));
        int maxRepairs = request.effectiveMaxRepairs();
        int attempts = 1;
        ServiceException lastFailure = null;
        while (true) {
            try {
                ValidatedQueryPlan plan = validator.validate(planJson, dataset, clock.instant());
                return toPlanResult(dataset, plan, attempts);
            } catch (ServiceException failure) {
                if (isSqlRejection(failure)) {
                    // SQL 片段：直接拒绝，不给"修成 SQL"的机会
                    throw failure;
                }
                if (isClarification(failure)) {
                    return toAliasClarification(dataset, planJson, attempts);
                }
                lastFailure = failure;
            }
            if (attempts > maxRepairs) {
                throw exception(AI_QUERY_REPAIR_EXHAUSTED);
            }
            // 有限修复：同一数据集、同一授权范围、同一时钟，只把"哪里不合规"告诉模型
            String repairHint = repairHint(lastFailure);
            String repairPrompt = summaryBuilder.buildPrompt(dataset, request.getQuestion(), clock, repairHint);
            String repaired = model.propose(request.getEndpointId(), repairPrompt, summaryBuilder.outputSchema());
            Map<String, Object> repairedEnvelope = parseEnvelope(repaired);
            if (!"PLAN".equals(String.valueOf(repairedEnvelope.get("kind")))) {
                throw exception(AI_QUERY_MODEL_OUTPUT_INVALID);
            }
            if (repairedEnvelope.get("plan") == null) {
                throw exception(AI_QUERY_MODEL_OUTPUT_INVALID);
            }
            planJson = JsonUtils.toJsonString(repairedEnvelope.get("plan"));
            attempts++;
        }
    }

    @Override
    public String datasetSummary(Long datasetId, Long datasetVersionId, List<String> allowedFieldCodes) {
        AiQueryPlanRequestDTO request = new AiQueryPlanRequestDTO()
                .setDatasetId(datasetId)
                .setDatasetVersionId(datasetVersionId)
                .setAllowedFieldCodes(allowedFieldCodes);
        requireAllowedDataset(request);
        return summaryBuilder.buildSummary(resolve(request), clock);
    }

    /** 授权范围：默认只允许请求的数据集本身；显式给出集合时必须在集合内。 */
    private static void requireAllowedDataset(AiQueryPlanRequestDTO request) {
        List<Long> allowed = request.getAllowedDatasetIds();
        if (allowed == null || allowed.isEmpty()) {
            return;
        }
        if (!allowed.contains(request.getDatasetId())) {
            throw exception(AI_QUERY_DATASET_NOT_ALLOWED);
        }
    }

    /** 解析数据集版本：必须启用、已发布、已验证且定义可解析。 */
    private ResolvedDatasetVersion resolve(AiQueryPlanRequestDTO request) {
        AiDatasetDO dataset = datasetService.getDataset(request.getDatasetId());
        if (!AiDatasetDO.STATUS_ENABLED.equals(dataset.getStatus())) {
            throw exception(AI_DATASET_DISABLED);
        }
        AiDatasetVersionDO version = request.getDatasetVersionId() == null
                ? latestPublished(dataset)
                : datasetService.getVersion(request.getDatasetVersionId());
        if (!dataset.getId().equals(version.getDatasetId())) {
            throw exception(AI_DATASET_VERSION_NOT_FOUND);
        }
        if (!AiDatasetVersionDO.STATUS_PUBLISHED.equals(version.getStatus())) {
            throw exception(AI_DATASET_VERSION_NOT_PUBLISHED);
        }
        if (AiDatasetVersionDO.VERIFICATION_DRIFTED.equals(version.getVerificationStatus())) {
            throw exception(AI_DATASET_VERSION_DRIFTED);
        }
        if (!AiDatasetVersionDO.VERIFICATION_VERIFIED.equals(version.getVerificationStatus())) {
            throw exception(AI_DATASET_VERSION_NOT_VERIFIED);
        }
        AiDatasetDefinition definition = AiDatasetDefinition.parse(version.getDefinitionJson());
        return new ResolvedDatasetVersion(
                dataset.getId(),
                dataset.getCode(),
                version.getId(),
                version.getVersionNo(),
                version.getSchemaHash(),
                dataset.getSourceObject(),
                definition,
                request.getAllowedFieldCodes());
    }

    /** 最新已发布版本（没有已发布版本时按"未发布"拒绝，而不是退回草稿）。 */
    private AiDatasetVersionDO latestPublished(AiDatasetDO dataset) {
        // 版本分页按编号倒序：取一页足够覆盖"最新已发布版本"（版本数量有界且按需增长）
        AiDatasetVersionDO published = datasetService
                .getVersionPage(
                        dataset.getId(), new com.basicframework.framework.common.pojo.PageParam().setPageSize(100))
                .getList()
                .stream()
                .filter(version -> AiDatasetVersionDO.STATUS_PUBLISHED.equals(version.getStatus()))
                .findFirst()
                .orElse(null);
        if (published == null) {
            throw exception(AI_DATASET_VERSION_NOT_PUBLISHED);
        }
        return published;
    }

    private static Map<String, Object> parseEnvelope(String rawOutput) {
        Map<?, ?> raw;
        try {
            raw = JsonUtils.parseObject(rawOutput, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_QUERY_MODEL_OUTPUT_INVALID);
        }
        if (raw == null || raw.get("kind") == null) {
            throw exception(AI_QUERY_MODEL_OUTPUT_INVALID);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        raw.forEach((key, value) -> envelope.put(String.valueOf(key), value));
        return envelope;
    }

    private static boolean isSqlRejection(ServiceException failure) {
        return failure.getCode().equals(AI_QUERY_SQL_REJECTED.getCode());
    }

    private static boolean isClarification(ServiceException failure) {
        return failure.getCode().equals(AI_QUERY_CLARIFICATION_REQUIRED.getCode());
    }

    /**
     * 修复提示：按**稳定错误码**给出固定文案，不复用异常正文
     * （异常正文可能带上游或用户内容，提示词会外发给模型）。
     */
    private static String repairHint(ServiceException failure) {
        if (failure == null) {
            return null;
        }
        Integer code = failure.getCode();
        if (AI_QUERY_DATASET_NOT_ALLOWED.getCode().equals(code)) {
            return "计划引用了本次不允许的数据集，只能使用摘要中的数据集标识与版本号";
        }
        if (AI_QUERY_SQL_REJECTED.getCode().equals(code)) {
            return "输出里出现了 SQL 片段，只能输出结构化计划字段";
        }
        if (AI_QUERY_CLARIFICATION_REQUIRED.getCode().equals(code)) {
            return "字段/指标名称有歧义，请改用摘要中的逻辑码，或返回 CLARIFICATION 追问";
        }
        return "查询计划不合规：请严格按输出契约与摘要中的逻辑码、时间口径、取值类型重新输出";
    }

    /** 领域结果 → 服务 DTO：PLAN/CLARIFICATION 是两种正常结果，转换保持一一对应。 */
    private static AiQueryPlanResultDTO toResult(
            QueryPlanOutcome outcome, ResolvedDatasetVersion dataset, int attempts) {
        AiQueryPlanResultDTO result = new AiQueryPlanResultDTO()
                .setKind(outcome.kind())
                .setDatasetId(dataset.datasetId())
                .setDatasetCode(dataset.datasetCode())
                .setPlanDatasetId(dataset.planDatasetId())
                .setDatasetVersionId(dataset.datasetVersionId())
                .setDatasetVersionNo(dataset.datasetVersionNo())
                .setSchemaHash(dataset.schemaHash())
                .setCandidates(List.of())
                .setAttempts(attempts);
        if (outcome instanceof QueryPlanOutcome.Plan planOutcome) {
            return result.setPlanHash(planOutcome.plan().planHash()).setPlanJson(planJson(planOutcome.plan()));
        }
        QueryPlanOutcome.Clarification clarification = (QueryPlanOutcome.Clarification) outcome;
        return result.setQuestion(clarification.question())
                .setReason(clarification.reason())
                .setCandidates(clarification.candidates().stream()
                        .map(candidate -> new AiQueryPlanResultDTO.Candidate(candidate.code(), candidate.label()))
                        .toList());
    }

    private AiQueryPlanResultDTO toPlanResult(ResolvedDatasetVersion dataset, ValidatedQueryPlan plan, int attempts) {
        return toResult(new QueryPlanOutcome.Plan(plan), dataset, attempts);
    }

    /** 计划规范化 JSON（协议层直接回传，执行器与审计共用同一份文本）。 */
    static String planJson(ValidatedQueryPlan plan) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("datasetId", plan.planDatasetId());
        canonical.put("datasetVersion", plan.datasetVersionNo());
        canonical.put(
                "metrics",
                plan.metrics().stream()
                        .map(metric -> Map.of(
                                "code", metric.code(), "aggregation", metric.aggregation(), "unit", metric.unit()))
                        .toList());
        canonical.put(
                "dimensions",
                plan.dimensions().stream()
                        .map(dimension -> Map.of("code", dimension.code()))
                        .toList());
        canonical.put(
                "filters",
                plan.filters().stream()
                        .map(filter -> Map.of(
                                "code", filter.code(),
                                "operator", filter.operator(),
                                "values", filter.values()))
                        .toList());
        canonical.put(
                "timeRange",
                plan.timeWindow() == null
                        ? null
                        : Map.of(
                                "code", plan.timeWindow().code(),
                                "startInclusive", plan.timeWindow().startInclusive(),
                                "endExclusive", plan.timeWindow().endExclusive(),
                                "timezone", plan.timeWindow().timezone()));
        canonical.put(
                "orderBy",
                plan.orderBy().stream()
                        .map(order -> Map.of("code", order.code(), "direction", order.direction()))
                        .toList());
        canonical.put("limit", plan.limit());
        canonical.put("planHash", plan.planHash());
        return JsonUtils.toJsonString(canonical);
    }

    private AiQueryPlanResultDTO toClarificationResult(
            AiQueryPlanRequestDTO request, ResolvedDatasetVersion dataset, Map<String, Object> envelope, int attempts) {
        Object clarification = envelope.get("clarification");
        if (!(clarification instanceof Map<?, ?> raw)) {
            throw exception(AI_QUERY_MODEL_OUTPUT_INVALID);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        raw.forEach((key, value) -> payload.put(String.valueOf(key), value));
        String question = payload.get("question") == null ? null : String.valueOf(payload.get("question"));
        if (!StringUtils.hasText(question)) {
            throw exception(AI_QUERY_MODEL_OUTPUT_INVALID);
        }
        return toResult(
                new QueryPlanOutcome.Clarification(
                        question,
                        candidates(dataset, payload.get("candidates")),
                        QueryPlanOutcome.Clarification.REASON_AMBIGUOUS),
                dataset,
                attempts);
    }

    /** 别名歧义：把"目录里可能是哪一个"作为候选返回（只取授权范围内、去重、限量）。 */
    private AiQueryPlanResultDTO toAliasClarification(ResolvedDatasetVersion dataset, String planJson, int attempts) {
        return toResult(
                new QueryPlanOutcome.Clarification(
                        "问题里的字段/指标名称有歧义，请从候选中确认要使用的口径。",
                        aliasCandidates(dataset, planJson),
                        QueryPlanOutcome.Clarification.REASON_AMBIGUOUS),
                dataset,
                attempts);
    }

    /** 模型给出的候选：只保留授权目录里真实存在的码（模型不能"发明"候选）。 */
    private static List<QueryPlanOutcome.Candidate> candidates(ResolvedDatasetVersion dataset, Object value) {
        Set<String> catalog = catalog(dataset);
        List<QueryPlanOutcome.Candidate> candidates = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (candidates.size() >= MAX_CANDIDATES) {
                    break;
                }
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Object code = raw.get("code");
                if (!(code instanceof String text) || !catalog.contains(text)) {
                    continue;
                }
                Object label = raw.get("label");
                candidates.add(
                        new QueryPlanOutcome.Candidate(text, label instanceof String labelText ? labelText : text));
            }
        }
        return candidates;
    }

    /** 从计划里找出命中别名的码，并映射回对应的目录码作为候选。 */
    private static List<QueryPlanOutcome.Candidate> aliasCandidates(ResolvedDatasetVersion dataset, String planJson) {
        Set<String> referenced = referencedCodes(planJson);
        List<QueryPlanOutcome.Candidate> candidates = new ArrayList<>();
        AiDatasetDefinition definition = dataset.definition();
        for (String code : referenced) {
            if (candidates.size() >= MAX_CANDIDATES) {
                break;
            }
            for (AiDatasetDefinition.Field field : definition.fields()) {
                if (!dataset.allowsField(field.name())) {
                    continue;
                }
                boolean aliasHit = field.aliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(code));
                if (aliasHit) {
                    candidates.add(new QueryPlanOutcome.Candidate(field.name(), field.name()));
                }
            }
        }
        return candidates.stream().distinct().toList();
    }

    private static Set<String> catalog(ResolvedDatasetVersion dataset) {
        Set<String> codes = new LinkedHashSet<>();
        AiDatasetDefinition definition = dataset.definition();
        definition.fields().stream()
                .filter(field -> dataset.allowsField(field.name()))
                .forEach(field -> codes.add(field.name()));
        definition.metrics().stream()
                .filter(metric -> dataset.allowsField(metric.name()))
                .forEach(metric -> codes.add(metric.name()));
        definition.dimensions().stream()
                .filter(dimension -> dataset.allowsField(dimension.name()))
                .forEach(dimension -> codes.add(dimension.name()));
        return codes;
    }

    /** 计划文本里出现过的码（用于别名候选匹配；只做词表比对，不解析结构）。 */
    private static Set<String> referencedCodes(String planJson) {
        Set<String> codes = new LinkedHashSet<>();
        for (String token : planJson.split("[^A-Za-z0-9_\\u4e00-\\u9fa5-]+")) {
            if (StringUtils.hasText(token)) {
                codes.add(token);
            }
        }
        return codes;
    }
}
