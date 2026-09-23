package com.basicframework.module.ai.service.run;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_DISABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_VERSION_DRIFTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_VERSION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_VERSION_NOT_PUBLISHED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_VERSION_NOT_VERIFIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_PLAN_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_SCOPE_REQUIRED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlQueryResultDTO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetVersionDO;
import com.basicframework.module.ai.domain.query.AiQueryPlanValidator;
import com.basicframework.module.ai.domain.query.CompiledQuery;
import com.basicframework.module.ai.domain.query.QueryScope;
import com.basicframework.module.ai.domain.query.ResolvedDatasetVersion;
import com.basicframework.module.ai.domain.query.ValidatedQueryPlan;
import com.basicframework.module.ai.domain.semantic.AiDatasetDefinition;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.query.compiler.AiCompiledQueryExecutor;
import com.basicframework.module.ai.service.query.compiler.QueryPlanSqlCompiler;
import com.basicframework.module.ai.service.query.planner.AiQueryPlanner;
import com.basicframework.module.ai.service.query.planner.dto.AiQueryPlanRequestDTO;
import com.basicframework.module.ai.service.query.planner.dto.AiQueryPlanResultDTO;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionRequestDTO;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionResultDTO;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 运行侧受控查询实现（R05）：D05 计划 → 版本可执行性复核 → D06 编译 → D03 只读执行。
 *
 * <p>顺序即安全语义：先按授权目录拿到**已校验计划**，再用同一版本锚点解析数据集，
 * 然后才编译与执行；执行结果按计划的逻辑码投影（多余列一律丢弃），
 * 完整性按是否触达行数上限如实标注（截断就是 PARTIAL，不谎称完整统计）。
 *
 * <p>版本可执行性复核与 D05 规划器同口径（启用 + 已发布 + 已验证 + 未漂移）：
 * 规划器解析的是"计划能引用什么"，这里复核的是"这份版本现在还能不能执行"——
 * 两次检查之间版本可能被停用或标记漂移，所以必须再判一次，宁可失败也不执行失效定义。
 */
@Service
@RequiredArgsConstructor
public class AiRunQueryExecutionServiceImpl implements AiRunQueryExecutionService {

    private final AiQueryPlanner queryPlanner;

    private final AiDatasetService datasetService;

    private final AiCompiledQueryExecutor compiledQueryExecutor;

    /** 固定时钟：计划的时间边界判定必须可复现（与 D05 共用同一 Bean）。 */
    private final Clock aiQueryPlannerClock;

    private final AiQueryPlanValidator validator = new AiQueryPlanValidator();

    private final QueryPlanSqlCompiler compiler = new QueryPlanSqlCompiler();

    @Override
    public AiRunQueryExecutionResultDTO execute(AiRunQueryExecutionRequestDTO request) {
        requireRequest(request);
        AiQueryPlanResultDTO plan = queryPlanner.plan(new AiQueryPlanRequestDTO()
                .setDatasetId(request.getDatasetId())
                .setDatasetVersionId(request.getDatasetVersionId())
                .setEndpointId(request.getEndpointId())
                .setQuestion(request.getQuestion())
                .setAllowedDatasetIds(request.getAllowedDatasetIds())
                .setAllowedFieldCodes(request.getAllowedFieldCodes()));
        if (!AiRunQueryExecutionResultDTO.KIND_PLAN.equals(plan.getKind())) {
            // 澄清是正常结果：原样带回追问与有限候选，绝不替用户选一个口径去执行
            return clarification(plan);
        }
        if (!StringUtils.hasText(plan.getPlanJson())) {
            throw exception(AI_REQUEST_INVALID);
        }
        Resolved resolved = resolve(request.getDatasetId(), plan.getDatasetVersionId());
        ValidatedQueryPlan validated = revalidate(plan.getPlanJson(), resolved.dataset());
        CompiledQuery compiled = compiler.compile(validated, resolved.dataset(), request.getRowScope());
        AiMysqlQueryResultDTO executed = compiledQueryExecutor.execute(resolved.connectorId(), compiled);
        return result(resolved.dataset(), validated, plan.getPlanJson(), compiled, executed);
    }

    /** 请求完整性与行范围：没有行范围就不生成可执行 SQL（空集合不是"不过滤"）。 */
    private static void requireRequest(AiRunQueryExecutionRequestDTO request) {
        if (request == null
                || request.getDatasetId() == null
                || request.getEndpointId() == null
                || !StringUtils.hasText(request.getQuestion())) {
            throw exception(AI_REQUEST_INVALID);
        }
        QueryScope scope = request.getRowScope();
        if (scope == null || !scope.isEffective()) {
            // 授权层没有给出行范围：拒绝执行，而不是退回全库
            throw exception(AI_QUERY_SCOPE_REQUIRED);
        }
    }

    /** 解析数据集与连接器：计划锚定的版本必须可执行（启用 + 已发布 + 已验证 + 未漂移）。 */
    private Resolved resolve(Long datasetId, Long datasetVersionId) {
        AiDatasetDO dataset = datasetService.getDataset(datasetId);
        if (!AiDatasetDO.STATUS_ENABLED.equals(dataset.getStatus())) {
            throw exception(AI_DATASET_DISABLED);
        }
        if (datasetVersionId == null) {
            throw exception(AI_DATASET_VERSION_NOT_FOUND);
        }
        AiDatasetVersionDO version = datasetService.getVersion(datasetVersionId);
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
        ResolvedDatasetVersion resolved = new ResolvedDatasetVersion(
                dataset.getId(),
                dataset.getCode(),
                version.getId(),
                version.getVersionNo(),
                version.getSchemaHash(),
                dataset.getSourceObject(),
                definition,
                null);
        return new Resolved(dataset.getConnectorId(), resolved);
    }

    /** 结果投影：列按计划的逻辑码（含语义单位），行只保留计划声明的列；完整性如实标注。 */
    private static AiRunQueryExecutionResultDTO result(
            ResolvedDatasetVersion dataset,
            ValidatedQueryPlan plan,
            String planJson,
            CompiledQuery compiled,
            AiMysqlQueryResultDTO executed) {
        List<AiRunQueryExecutionResultDTO.Column> columns = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (CompiledQuery.ResultColumn column : compiled.resultColumns()) {
            columns.add(new AiRunQueryExecutionResultDTO.Column(
                    column.code(), column.label(), column.type(), unitOf(plan, column.code())));
            codes.add(column.code());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row :
                executed.getRows() == null ? List.<Map<String, Object>>of() : executed.getRows()) {
            Map<String, Object> projected = new LinkedHashMap<>();
            for (String code : codes) {
                projected.put(code, row.get(code));
            }
            rows.add(projected);
        }
        return new AiRunQueryExecutionResultDTO()
                .setKind(AiRunQueryExecutionResultDTO.KIND_PLAN)
                .setDatasetId(dataset.datasetId())
                .setDatasetCode(dataset.datasetCode())
                .setDatasetVersionId(dataset.datasetVersionId())
                .setDatasetVersionNo(dataset.datasetVersionNo())
                .setSchemaHash(dataset.schemaHash())
                .setPlanHash(plan.planHash())
                .setPlanJson(planJson)
                .setResultRef(resultRef(plan.planHash()))
                .setColumns(columns)
                .setRows(rows)
                .setRowCount(rows.size())
                .setCompleteness(
                        executed.isTruncated()
                                ? AiRunQueryExecutionResultDTO.PARTIAL
                                : AiRunQueryExecutionResultDTO.COMPLETE)
                .setTruncated(executed.isTruncated());
    }

    /**
     * 执行前再校验一次规划器回传的**规范化计划**。
     *
     * <p>为什么还要校验：规划器回传的是规范化形态（逻辑码 + 聚合 + 计划哈希），而执行链路的唯一入口
     * 是"校验器建立的计划"——校验器只接受模型形态的计划文本。这里把规范化形态**按键名还原**成模型形态，
     * 再交给同一个校验器：授权范围、字段类型、时间边界、参数域全部重判一次（少一道就可能执行到别的计划）。
     *
     * <p>还原是否等价不靠人工比对：重新校验得到的计划哈希必须等于规范化计划里的 {@code planHash}，
     * 不一致即拒绝执行——还原错一个键就会在这里失败，而不是悄悄执行"另一份计划"。
     */
    private ValidatedQueryPlan revalidate(String canonicalPlanJson, ResolvedDatasetVersion dataset) {
        Map<String, Object> canonical;
        try {
            canonical = JsonUtils.parseObject(canonicalPlanJson, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        if (canonical == null) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        Object expectedHash = canonical.get("planHash");
        Map<String, Object> modelForm = new LinkedHashMap<>();
        modelForm.put("schemaVersion", AiQueryPlanValidator.SCHEMA_VERSION);
        modelForm.put("datasetId", canonical.get("datasetId"));
        modelForm.put("datasetVersion", canonical.get("datasetVersion"));
        modelForm.put("metrics", codes(canonical.get("metrics")));
        modelForm.put("dimensions", codes(canonical.get("dimensions")));
        modelForm.put("filters", filters(canonical.get("filters")));
        modelForm.put("timeRange", timeRange(canonical.get("timeRange")));
        modelForm.put("orderBy", orderBy(canonical.get("orderBy")));
        modelForm.put("limit", canonical.get("limit"));
        ValidatedQueryPlan plan =
                validator.validate(JsonUtils.toJsonString(modelForm), dataset, aiQueryPlannerClock.instant());
        if (expectedHash == null || !String.valueOf(expectedHash).equals(plan.planHash())) {
            // 还原后的计划与规划器回传的计划不是同一份：拒绝执行
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        return plan;
    }

    /** 指标/维度：规范化形态是 {@code [{"code":...}]}，校验器形态是码字符串数组。 */
    private static List<Object> codes(Object value) {
        if (!(value instanceof List<?> items)) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        List<Object> codes = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> node) {
                codes.add(node.get("code"));
            } else {
                codes.add(item);
            }
        }
        return codes;
    }

    /** 过滤条件：规范化形态用 {@code code} + {@code values}，校验器形态用 {@code field} + 取值键。 */
    private static List<Object> filters(Object value) {
        if (!(value instanceof List<?> items)) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        List<Object> filters = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> node)) {
                throw exception(AI_QUERY_PLAN_INVALID);
            }
            String operator = String.valueOf(node.get("operator"));
            List<?> values = node.get("values") instanceof List<?> list ? list : List.of();
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("field", node.get("code"));
            filter.put("operator", operator);
            switch (operator) {
                case "IS_NULL" -> {
                    // 无取值
                }
                case "IN" -> filter.put("values", values);
                case "BETWEEN" -> {
                    if (values.size() != 2) {
                        throw exception(AI_QUERY_PLAN_INVALID);
                    }
                    filter.put("lower", values.get(0));
                    filter.put("upper", values.get(1));
                }
                default -> {
                    if (values.size() != 1) {
                        throw exception(AI_QUERY_PLAN_INVALID);
                    }
                    filter.put("value", values.get(0));
                }
            }
            filters.add(filter);
        }
        return filters;
    }

    /** 时间窗口：规范化形态用 {@code code}，校验器形态用 {@code field}。 */
    private static Object timeRange(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> node)) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("field", node.get("code"));
        window.put("startInclusive", node.get("startInclusive"));
        window.put("endExclusive", node.get("endExclusive"));
        window.put("timezone", node.get("timezone"));
        return window;
    }

    /** 排序：规范化形态用 {@code code}，校验器形态用 {@code field}。 */
    private static List<Object> orderBy(Object value) {
        if (!(value instanceof List<?> items)) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        List<Object> orders = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> node)) {
                throw exception(AI_QUERY_PLAN_INVALID);
            }
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("field", node.get("code"));
            order.put("direction", node.get("direction"));
            orders.add(order);
        }
        return orders;
    }

    /** 结果标识：由计划哈希派生（稳定、可复现、不含数据与物理对象名）。 */
    private static String resultRef(String planHash) {
        String hash = planHash == null ? "" : planHash;
        return "plan_" + hash.substring(0, Math.min(12, hash.length()));
    }

    /** 结果列的单位：来自语义定义里的指标声明（"NONE" 表示无单位，不展示）。 */
    private static String unitOf(ValidatedQueryPlan plan, String code) {
        for (ValidatedQueryPlan.Metric metric : plan.metrics()) {
            if (metric.code().equals(code)) {
                return metric.unit() == null || "NONE".equals(metric.unit()) ? null : metric.unit();
            }
        }
        return null;
    }

    private static AiRunQueryExecutionResultDTO clarification(AiQueryPlanResultDTO plan) {
        List<AiRunQueryExecutionResultDTO.Candidate> candidates = new ArrayList<>();
        for (AiQueryPlanResultDTO.Candidate candidate :
                plan.getCandidates() == null ? List.<AiQueryPlanResultDTO.Candidate>of() : plan.getCandidates()) {
            candidates.add(new AiRunQueryExecutionResultDTO.Candidate(candidate.code(), candidate.label()));
        }
        return new AiRunQueryExecutionResultDTO()
                .setKind(AiRunQueryExecutionResultDTO.KIND_CLARIFICATION)
                .setDatasetId(plan.getDatasetId())
                .setDatasetCode(plan.getDatasetCode())
                .setDatasetVersionId(plan.getDatasetVersionId())
                .setDatasetVersionNo(plan.getDatasetVersionNo())
                .setClarificationQuestion(plan.getQuestion())
                .setClarificationReason(plan.getReason())
                .setClarificationCandidates(candidates);
    }

    /** 连接器编号 + 已解析版本（数据集与连接器的绑定不暴露给上层）。 */
    private record Resolved(Long connectorId, ResolvedDatasetVersion dataset) {}
}
