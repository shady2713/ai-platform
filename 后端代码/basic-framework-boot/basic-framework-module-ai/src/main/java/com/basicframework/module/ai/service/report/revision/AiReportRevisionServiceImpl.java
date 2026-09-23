package com.basicframework.module.ai.service.report.revision;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_ACCESS_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_REVISION_MODEL_UNAVAILABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_REVISION_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportVersionDO;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.domain.report.AiReportSpec;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.report.persistence.AiReportScopeRef;
import com.basicframework.module.ai.service.report.persistence.AiReportScopeRefs;
import com.basicframework.module.ai.service.report.persistence.AiReportService;
import com.basicframework.module.ai.service.report.persistence.dto.AiReportSaveDTO;
import com.basicframework.module.ai.service.report.revision.dto.AiReportRevisionRequestDTO;
import com.basicframework.module.ai.service.report.revision.dto.AiReportRevisionResultDTO;
import com.basicframework.module.ai.service.report.validation.AiReportDataBinder;
import com.basicframework.module.ai.service.report.validation.AiReportSpecValidator;
import com.basicframework.module.ai.service.run.AiRunQueryExecutionService;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionRequestDTO;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionResultDTO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 报表对话修改实现（R05）。
 *
 * <p>为什么分类不能交给模型：模型说"这次只是换个图"并不等于真的没动数据。
 * 服务端只认操作码——只要清单里有一条数据类操作就走受控查询，模型无法把"新增指标"
 * 伪装成展示类改动；反之展示类操作也不能偷偷换数据集（换数据集本身就是数据类操作码）。
 *
 * <p>为什么展示类不查库也能保证数据对：候选规格用 R01 的绑定器把**基础版本保存的结果行**
 * 重新绑一遍（列存在、行数一致、完整性一致），换图类型/字段只是重新投影，
 * 数据来源与数字没有任何变化（AT-045 的"换图不重复查库"）。
 *
 * <p>为什么原版本不会被改写：本类不调用任何"更新版本内容"的方法，保存只走 R04 的新增版本
 * （乐观锁 + 版本号唯一索引）；并发修改在 R04 的 CAS 上返回 409。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReportRevisionServiceImpl implements AiReportRevisionService {

    private final AiReportService reportService;

    private final AiDatasetService datasetService;

    private final AiAuthorizationService authorizationService;

    private final AiConversationSubjectResolver subjectResolver;

    private final AiRunQueryExecutionService queryExecutionService;

    private final AiReportSpecValidator validator;

    private final AiReportDataBinder binder;

    private final AiReportSpecPatcher patcher;

    /** 修订模型（未装配时按稳定原因码失败，而不是让整个应用上下文启动不了）。 */
    private final ObjectProvider<AiReportRevisionModel> modelProvider;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiReportRevisionResultDTO revise(AiReportRevisionRequestDTO request) {
        requireRequest(request);
        AiConversationSubject subject = subjectResolver.resolveCurrent().orElseThrow(() -> exception(AI_ACCESS_DENIED));
        // 第 2 步：基础版本必须属于当前主体，且保存时的授权范围仍被当前范围覆盖（R04 口径）
        AiReportVersionDO base = reportService.getVersion(request.getReportId(), request.getBaseVersionNo());
        AiReportDO report = reportService.getReport(request.getReportId());
        AiReportSpec baseSpec = AiReportSpec.parse(base.getSpecJson());

        AiReportRevisionPlan plan = plan(base.getSpecJson(), baseSpec, request);
        Map<Long, AiRunQueryExecutionResultDTO> queryResults = new LinkedHashMap<>();
        if (plan.requiresQuery()) {
            for (Long datasetId : plan.queryDatasetIds()) {
                AiRunQueryExecutionResultDTO result = controlledQuery(subject, request, datasetId, plan);
                if (AiRunQueryExecutionResultDTO.KIND_CLARIFICATION.equals(result.getKind())) {
                    // 歧义不猜：把追问带回对话，不创建新版本、不改动报表
                    return clarification(request, result);
                }
                queryResults.put(datasetId, result);
            }
        }
        boolean queryPerformed = !queryResults.isEmpty();

        AiReportSpecPatch patch = patcher.apply(base.getSpecJson(), plan, queryResults);
        AiReportSpec candidate = validator.validate(AiReportSpec.parse(patch.specJson()));
        // 绑定输入 = 基础版本保存的结果行 + 本次受控查询的新结果（新结果覆盖它写进的引用）
        Map<String, AiReportDataBinder.ExecutionResult> datasets =
                new LinkedHashMap<>(AiReportRevisionData.executionResults(base.getDataJson()));
        datasets.putAll(boundFromQueries(patch, queryResults));
        if (queryPerformed) {
            // 受控查询只覆盖它写进的引用；候选规格里其余引用必须仍能从基础版本结果行绑定，
            // 否则宁可失败也不留一个"没有数据"的块
            for (AiReportSpec.DatasetRef ref : candidate.datasetRefs()) {
                if (!datasets.containsKey(ref.id())) {
                    throw exception(AI_REPORT_REVISION_UNSUPPORTED, "基础版本未保存结果行，无法在不重新查询的前提下绑定该数据集");
                }
            }
        } else if (datasets.isEmpty() && AiReportRevisionData.projectionChanged(baseSpec, candidate)) {
            // 展示类操作改了数据投影（换字段/列），而基础版本没存结果行：明确拒绝，
            // 既不查库（用户没要求改数据）也不猜（猜出来的数字就是编造）
            throw exception(AI_REPORT_REVISION_UNSUPPORTED, "基础版本未保存结果行，改字段或列的修订需要重新查询");
        }
        String dataJson = datasets.isEmpty()
                ? AiReportRevisionData.reuse(base.getDataJson(), candidate)
                : AiReportRevisionData.build(candidate, binder.bind(candidate, datasets), datasets);

        Long reportId = reportService.saveVersion(new AiReportSaveDTO()
                .setId(report.getId())
                .setName(report.getName())
                .setDescription(report.getDescription())
                .setMode(report.getMode())
                .setServiceId(report.getServiceId())
                .setReleaseId(report.getReleaseId())
                .setThemeId(candidate.themeId())
                .setThemeRevision(candidate.themeRevision())
                .setSchemaVersion(AiReportSpec.SCHEMA_VERSION)
                .setSpecJson(patch.specJson())
                .setDataJson(dataJson)
                .setSourcesJson(sourcesJson(base, queryResults))
                .setCompleteness(completeness(base, queryResults))
                .setCreatedByRun(request.getCreatedByRun())
                .setVersion(request.getVersion()));

        List<String> notes = new ArrayList<>();
        if (queryPerformed) {
            notes.add("数据类修改已按当前权限重新执行受控查询（计划哈希 " + planHash(queryResults) + "）");
        } else {
            notes.add("展示类修改复用保存时的数据，未重新查询数据源");
        }
        return new AiReportRevisionResultDTO()
                .setOutcome(AiReportRevisionResultDTO.OUTCOME_APPLIED)
                .setReportId(reportId)
                .setBaseVersionNo(request.getBaseVersionNo())
                .setNewVersionNo(request.getBaseVersionNo() + 1)
                .setQueryPerformed(queryPerformed)
                .setDiff(patch.diff())
                .setNotes(notes);
    }

    /** 目录：只放基础版本里已有的块与结果列，加上本次授权允许的数据集（模型看不到别的）。 */
    private String catalog(AiReportSpec baseSpec, AiReportRevisionRequestDTO request) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (AiReportSpec.Block block : baseSpec.blocks()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("blockId", block.id());
            node.put("type", block.type());
            node.put("title", block.title());
            blocks.add(node);
        }
        List<Map<String, Object>> datasets = new ArrayList<>();
        for (AiReportSpec.DatasetRef ref : baseSpec.datasetRefs()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("datasetRef", ref.id());
            node.put("rowCount", ref.rowCount());
            node.put("completeness", ref.completeness());
            node.put(
                    "columns",
                    ref.columns().stream()
                            .map(column -> {
                                Map<String, Object> columnNode = new LinkedHashMap<>();
                                columnNode.put("field", column.field());
                                columnNode.put("label", column.label());
                                columnNode.put("dataType", column.dataType());
                                return columnNode;
                            })
                            .toList());
            datasets.add(node);
        }
        List<Map<String, Object>> allowed = new ArrayList<>();
        if (request.getDatasetId() != null) {
            AiDatasetDO dataset = datasetService.getDataset(request.getDatasetId());
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("datasetId", dataset.getId());
            node.put("datasetCode", dataset.getCode());
            allowed.add(node);
        }
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("blocks", blocks);
        catalog.put("datasetRefs", datasets);
        catalog.put("queryableDatasets", allowed);
        return JsonUtils.toJsonString(catalog);
    }

    private AiReportRevisionPlan plan(String baseSpecJson, AiReportSpec baseSpec, AiReportRevisionRequestDTO request) {
        AiReportRevisionModel model = modelProvider.getIfAvailable();
        if (model == null) {
            // 没有模型就做不了对话修改：明确失败，不返回"看起来成功"的假修订
            throw exception(AI_REPORT_REVISION_MODEL_UNAVAILABLE);
        }
        String output = model.propose(baseSpecJson, catalog(baseSpec, request), request.getInstruction());
        return AiReportRevisionPlan.parse(output);
    }

    /**
     * 数据类操作的受控查询：先做 A03 的 DATASET READ 判定，再按当前权限执行查询。
     *
     * <p>顺序不能反：授权判定在前，避免"先查了数据再发现没权限"。
     * 行范围由授权层给出（请求体给不了），没有行范围时查询链路直接拒绝（不退回全库）。
     */
    private AiRunQueryExecutionResultDTO controlledQuery(
            AiConversationSubject subject,
            AiReportRevisionRequestDTO request,
            Long datasetId,
            AiReportRevisionPlan plan) {
        AiDatasetDO dataset = datasetService.getDataset(datasetId);
        AiAuthorizationDecisionDTO decision = authorizationService.authorize(
                subject.applicationId(),
                subject.subjectType().name(),
                subject.externalUserId(),
                AiResourceType.DATASET,
                "dset_" + dataset.getCode(),
                AiAction.READ,
                List.of());
        if (!decision.isAllowed()) {
            throw exception(AI_ACCESS_DENIED);
        }
        List<Long> allowed = request.getDatasetId() == null
                ? new ArrayList<>(plan.queryDatasetIds())
                : List.of(request.getDatasetId());
        return queryExecutionService.execute(new AiRunQueryExecutionRequestDTO()
                .setDatasetId(datasetId)
                .setDatasetVersionId(request.getDatasetVersionId())
                .setEndpointId(request.getEndpointId())
                .setQuestion(request.getInstruction())
                .setAllowedDatasetIds(allowed)
                .setAllowedFieldCodes(request.getAllowedFieldCodes())
                .setRowScope(request.getRowScope())
                .setRunKey(request.getCreatedByRun()));
    }

    /**
     * 受控查询结果 → 绑定输入：只绑定**本次查询写进的那个引用**（由补丁器精确告知）。
     *
     * <p>不能按数据集标识匹配引用：修订后同一数据集可能同时存在新旧两个引用（新增指标就是新建引用），
     * 按标识匹配会把新查询的结果错误地套到旧引用上（旧块的行数/完整性随之对不上）。
     */
    private static Map<String, AiReportDataBinder.ExecutionResult> boundFromQueries(
            AiReportSpecPatch patch, Map<Long, AiRunQueryExecutionResultDTO> queryResults) {
        Map<String, AiReportDataBinder.ExecutionResult> datasets = new LinkedHashMap<>();
        patch.datasetRefByDatasetId().forEach((datasetId, datasetRefId) -> {
            AiRunQueryExecutionResultDTO result = queryResults.get(datasetId);
            if (result == null) {
                return;
            }
            List<String> columns = new ArrayList<>();
            for (AiRunQueryExecutionResultDTO.Column column : result.getColumns()) {
                columns.add(column.code());
            }
            datasets.put(
                    datasetRefId,
                    new AiReportDataBinder.ExecutionResult(
                            result.getResultRef(), columns, result.getRows(), result.getCompleteness()));
        });
        return datasets;
    }

    /**
     * 依赖清单：基础版本的依赖 + 本次新增的数据集。
     *
     * <p>新数据集必须进依赖清单：R04 保存时逐项做 A03 READ 判定并记指纹，
     * 这样"修订引入的数据集"与"首次生成引入的数据集"受同一套范围复核约束（失权后拒绝展示）。
     */
    private static String sourcesJson(AiReportVersionDO base, Map<Long, AiRunQueryExecutionResultDTO> queryResults) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (AiReportScopeRef ref : AiReportScopeRefs.parse(base.getSourcesJson())) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("resourceType", ref.getResourceType());
            node.put("resourceKey", ref.getResourceKey());
            sources.add(node);
        }
        Set<String> keys = new LinkedHashSet<>();
        for (AiRunQueryExecutionResultDTO result : queryResults.values()) {
            if (result.datasetResourceKey() != null) {
                keys.add(result.datasetResourceKey());
            }
        }
        for (String key : keys) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("resourceType", AiResourceType.DATASET.name());
            node.put("resourceKey", key);
            sources.add(node);
        }
        return JsonUtils.toJsonString(sources);
    }

    /** 完整性：有受控查询结果时取最弱的一档（任一 PARTIAL 即 PARTIAL），否则沿用基础版本。 */
    private static String completeness(AiReportVersionDO base, Map<Long, AiRunQueryExecutionResultDTO> queryResults) {
        if (queryResults.isEmpty()) {
            return base.getCompleteness();
        }
        boolean partial = queryResults.values().stream()
                .anyMatch(result -> !AiRunQueryExecutionResultDTO.COMPLETE.equals(result.getCompleteness()));
        return partial ? AiRunQueryExecutionResultDTO.PARTIAL : AiRunQueryExecutionResultDTO.COMPLETE;
    }

    private static String planHash(Map<Long, AiRunQueryExecutionResultDTO> queryResults) {
        return queryResults.values().stream()
                .map(AiRunQueryExecutionResultDTO::getPlanHash)
                .filter(hash -> hash != null && !hash.isBlank())
                .findFirst()
                .orElse("");
    }

    private static AiReportRevisionResultDTO clarification(
            AiReportRevisionRequestDTO request, AiRunQueryExecutionResultDTO result) {
        List<AiReportRevisionResultDTO.Candidate> candidates = new ArrayList<>();
        for (AiRunQueryExecutionResultDTO.Candidate candidate : result.getClarificationCandidates() == null
                ? List.<AiRunQueryExecutionResultDTO.Candidate>of()
                : result.getClarificationCandidates()) {
            candidates.add(new AiReportRevisionResultDTO.Candidate(candidate.code(), candidate.label()));
        }
        return new AiReportRevisionResultDTO()
                .setOutcome(AiReportRevisionResultDTO.OUTCOME_CLARIFICATION)
                .setReportId(request.getReportId())
                .setBaseVersionNo(request.getBaseVersionNo())
                .setQueryPerformed(false)
                .setClarificationQuestion(result.getClarificationQuestion())
                .setClarificationReason(result.getClarificationReason())
                .setClarificationCandidates(candidates)
                .setNotes(List.of("修订指令存在歧义，未创建新版本"));
    }

    private static void requireRequest(AiReportRevisionRequestDTO request) {
        if (request == null
                || request.getReportId() == null
                || request.getBaseVersionNo() == null
                || request.getVersion() == null
                || request.getEndpointId() == null
                || !StringUtils.hasText(request.getInstruction())) {
            throw exception(AI_REQUEST_INVALID);
        }
    }
}
