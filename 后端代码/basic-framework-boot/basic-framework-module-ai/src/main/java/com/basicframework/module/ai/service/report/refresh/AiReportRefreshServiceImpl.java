package com.basicframework.module.ai.service.report.refresh;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_DISABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_VERSION_DRIFTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_VERSION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_VERSION_NOT_PUBLISHED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_VERSION_NOT_VERIFIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_REFRESH_NOT_SUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_REFRESH_SCOPE_REQUIRED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_SCOPE_CHANGED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_VERSION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetVersionDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportRefreshDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportVersionDO;
import com.basicframework.module.ai.dal.mysql.dataset.AiDatasetMapper;
import com.basicframework.module.ai.dal.mysql.report.AiReportMapper;
import com.basicframework.module.ai.dal.mysql.report.AiReportRefreshMapper;
import com.basicframework.module.ai.dal.mysql.report.AiReportVersionMapper;
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
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshRequestDTO;
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshResultDTO;
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshStateDTO;
import com.basicframework.module.ai.service.report.revision.AiReportRevisionData;
import com.basicframework.module.ai.service.report.validation.AiReportDataBinder;
import com.basicframework.module.ai.service.report.validation.AiReportSpecValidator;
import com.basicframework.module.ai.service.run.AiRunQueryExecutionService;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionFixedRequestDTO;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionResultDTO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 报表刷新实现（R06）。
 *
 * <p>为什么刷新自己写版本行而不是调用 R04 的保存入口：R04 的保存以**会话身份**为归属来源，
 * 而刷新既可能来自会话（接口），也可能来自作业（没有会话）。这里按 R04 的同一不变量写入——
 * 版本号由 {@code latest_version_no + 1} 推进、乐观锁 CAS 冲突即失败、{@code (report_id, version_no)}
 * 唯一索引兜底、可刷新版本不存数据（数据存在刷新尝试记录上）；归属只认**报表自身的归属列**，
 * 调用方无法指定身份。
 *
 * <p>为什么失败要留痕而不是抛异常：界面需要显示"上次刷新时间 + 失败原因"（AT-047），
 * 所以数据类失败（来源停用/漂移、查询失败、绑定不一致、并发冲突）都记录尝试并作为正常结果返回；
 * 只有"报表不属于当前主体"（404）与"当前范围无法覆盖保存时范围"（409，AT-048）是前置条件失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReportRefreshServiceImpl implements AiReportRefreshService {

    /** 语义版本分页上限（版本数量有界且按需增长，取一页足够覆盖）。 */
    private static final int VERSION_PAGE_SIZE = 100;

    /** 未变化的原因码（数据与上次成功刷新一致）。 */
    static final String REASON_UNCHANGED = "UNCHANGED";

    private final AiReportMapper reportMapper;

    private final AiReportVersionMapper versionMapper;

    private final AiReportRefreshMapper refreshMapper;

    private final AiDatasetMapper datasetMapper;

    private final AiDatasetService datasetService;

    private final AiAuthorizationService authorizationService;

    private final AiConversationSubjectResolver subjectResolver;

    private final AiRunQueryExecutionService queryExecutionService;

    private final AiReportSpecValidator validator;

    private final AiReportDataBinder binder;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiReportRefreshResultDTO refresh(AiReportRefreshRequestDTO request) {
        if (request == null || request.getReportId() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiReportDO report = requireReport(request.getReportId());
        if (!AiReportDO.MODE_REFRESHABLE.equals(report.getMode())) {
            // 快照报表的数据是"保存时的样子"：刷新语义不适用（AT-048 的快照侧由读取复核覆盖）
            throw exception(AI_REPORT_REFRESH_NOT_SUPPORTED);
        }
        AiReportVersionDO base = requireVersion(report);
        // 失权即停止：当前范围无法证明覆盖保存时范围时，连旧结果都不再展示（AT-048）
        requireScopeStillCovers(report, base.getScopeRefsJson(), base.getScopeFingerprint());

        AiReportSpec spec = AiReportSpec.parse(base.getSpecJson());
        Map<String, AiReportRefreshTarget> targets = new LinkedHashMap<>();
        try {
            targets = resolveTargets(spec);
        } catch (ServiceException failure) {
            // 只留稳定原因码：异常正文可能含上游细节，不写入留痕与响应
            return failed(report, base, String.valueOf(failure.getCode()), "刷新失败，已保留旧结果");
        }
        if (request.getRowScope() == null || !request.getRowScope().isEffective()) {
            // 授权层没有给出行范围：留痕并保留旧结果，绝不退回全库
            return failed(report, base, String.valueOf(AI_REPORT_REFRESH_SCOPE_REQUIRED.getCode()), "数据类刷新需要行范围上下文");
        }
        Map<String, AiReportDataBinder.ExecutionResult> datasets = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, AiReportRefreshTarget> entry : targets.entrySet()) {
                AiRunQueryExecutionResultDTO executed =
                        queryExecutionService.executeFixed(new AiRunQueryExecutionFixedRequestDTO()
                                .setDatasetId(entry.getValue().datasetId())
                                .setDatasetVersionId(entry.getValue().datasetVersionId())
                                .setPlanJson(entry.getValue().planJson())
                                .setRowScope(request.getRowScope())
                                .setRunKey(request.getCreatedByRun()));
                datasets.put(entry.getKey(), toExecutionResult(executed));
            }
        } catch (ServiceException failure) {
            // 查询失败：保留旧结果并留痕（失败不是异常）
            // 只留稳定原因码：异常正文可能含上游细节，不写入留痕与响应
            return failed(report, base, String.valueOf(failure.getCode()), "刷新失败，已保留旧结果");
        }

        String candidateSpecJson = refreshedSpec(base.getSpecJson(), datasets);
        AiReportDataBinder.BoundReport bound;
        String dataJson;
        try {
            AiReportSpec candidate = validator.validate(AiReportSpec.parse(candidateSpecJson));
            bound = binder.bind(candidate, datasets);
            dataJson = AiReportRevisionData.build(candidate, bound, datasets);
        } catch (ServiceException failure) {
            // 声明与真实结果不一致（列/行数/完整性）：留痕并保留旧结果，绝不写入半成品
            // 只留稳定原因码：异常正文可能含上游细节，不写入留痕与响应
            return failed(report, base, String.valueOf(failure.getCode()), "刷新失败，已保留旧结果");
        }
        String completeness = completeness(datasets);

        AiReportRefreshDO lastOk = refreshMapper.selectLastOkByReport(report.getId());
        if (lastOk != null && dataJson.equals(lastOk.getDataJson())) {
            // 重复刷新幂等：上游数据没变就不产生新版本
            record(report, base, AiReportRefreshDO.STATUS_UNCHANGED, REASON_UNCHANGED, dataJson, completeness, null);
            return result(
                    AiReportRefreshResultDTO.STATUS_UNCHANGED,
                    report,
                    base,
                    null,
                    completeness,
                    REASON_UNCHANGED,
                    dataJson,
                    "上游数据与上次刷新一致，未产生新版本");
        }

        int nextVersionNo = report.getLatestVersionNo() + 1;
        if (reportMapper.updateWithVersion(
                        new AiReportDO()
                                .setId(report.getId())
                                .setLatestVersionNo(nextVersionNo)
                                .setPublishedVersionNo(nextVersionNo)
                                .setVersion(report.getVersion() + 1),
                        report.getVersion())
                == 0) {
            // 并发修改/刷新：CAS 失败即失败，旧结果保持生效
            return failed(report, base, String.valueOf(AI_STATE_CONFLICT.getCode()), "并发修改，请重试");
        }
        versionMapper.insert(new AiReportVersionDO()
                .setReportId(report.getId())
                .setVersionNo(nextVersionNo)
                .setMode(report.getMode())
                .setSpecJson(candidateSpecJson)
                .setDataJson(null)
                .setSourcesJson(base.getSourcesJson())
                .setScopeRefsJson(base.getScopeRefsJson())
                .setScopeFingerprint(base.getScopeFingerprint())
                .setAsOf(LocalDateTime.now())
                .setCompleteness(completeness)
                .setCreatedByRun(request.getCreatedByRun())
                .setVersion(0));
        record(report, base, AiReportRefreshDO.STATUS_OK, null, dataJson, completeness, nextVersionNo);
        return result(
                AiReportRefreshResultDTO.STATUS_OK,
                report,
                base,
                nextVersionNo,
                completeness,
                null,
                dataJson,
                "已按当前权限重新执行固定查询版本");
    }

    @Override
    public AiReportRefreshStateDTO lastState(Long reportId) {
        if (reportId == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiReportDO report = requireReport(reportId);
        AiReportRefreshDO last = refreshMapper.selectLastByReport(report.getId());
        if (last == null) {
            return new AiReportRefreshStateDTO().setReportId(report.getId()).setAttempted(false);
        }
        AiReportRefreshDO lastOk = refreshMapper.selectLastOkByReport(report.getId());
        String dataJson = null;
        if (lastOk != null && StringUtils.hasText(lastOk.getDataJson())) {
            // 旧结果同样按当前 ACL：读取前逐项复核范围指纹（AT-048）
            requireScopeStillCovers(report, lastOk.getScopeRefsJson(), lastOk.getScopeFingerprint());
            dataJson = lastOk.getDataJson();
        }
        return new AiReportRefreshStateDTO()
                .setReportId(report.getId())
                .setAttempted(true)
                .setStatus(last.getStatus())
                .setAsOf(last.getAsOf())
                .setReason(last.getReason())
                .setCompleteness(lastOk == null ? last.getCompleteness() : lastOk.getCompleteness())
                .setResultVersionNo(lastOk == null ? null : lastOk.getResultVersionNo())
                .setDataJson(dataJson);
    }

    // ==================== 前置条件与来源解析 ====================

    /**
     * 归属判定：有会话时按会话身份校验归属；没有会话（作业）时以**报表自身的归属列**为主体。
     *
     * <p>越权与不存在同语义（404），不借错误码枚举他人报表编号；任何情况下调用方都不能指定身份。
     */
    private AiReportDO requireReport(Long reportId) {
        AiReportDO report = reportMapper.selectById(reportId);
        if (report == null) {
            throw exception(AI_REPORT_NOT_FOUND);
        }
        AiConversationSubject session = subjectResolver.resolveCurrent().orElse(null);
        if (session == null) {
            return report;
        }
        if (!session.sameAs(report.getApplicationId(), report.getSubjectType(), report.getExternalUserId())) {
            throw exception(AI_REPORT_NOT_FOUND);
        }
        return report;
    }

    private AiReportVersionDO requireVersion(AiReportDO report) {
        AiReportVersionDO version = versionMapper.selectByVersionNo(report.getId(), report.getPublishedVersionNo());
        if (version == null) {
            throw exception(AI_REPORT_VERSION_NOT_FOUND);
        }
        return version;
    }

    /**
     * 范围复核（与 R04 读取口径一致）：整体指纹先自洽，再逐项 {@code reauthorizeHistorical}。
     *
     * <p>任一项判定拒绝或指纹不一致即拒绝（失权时刷新与读取旧结果都停止，AT-048）。
     */
    private void requireScopeStillCovers(AiReportDO report, String scopeRefsJson, String scopeFingerprint) {
        List<AiReportScopeRef> refs = AiReportScopeRefs.fromJson(scopeRefsJson);
        if (!AiReportScopeRefs.fingerprint(refs).equals(scopeFingerprint)) {
            throw exception(AI_REPORT_SCOPE_CHANGED);
        }
        for (AiReportScopeRef ref : refs) {
            AiAuthorizationDecisionDTO decision = authorizationService.reauthorizeHistorical(
                    report.getApplicationId(),
                    report.getSubjectType(),
                    report.getExternalUserId(),
                    AiResourceType.parse(ref.getResourceType()).orElse(null),
                    ref.getResourceKey(),
                    AiAction.READ,
                    ref.getFingerprint());
            if (!decision.isAllowed() || !String.valueOf(ref.getFingerprint()).equals(decision.getScopeFingerprint())) {
                throw exception(AI_REPORT_SCOPE_CHANGED);
            }
        }
    }

    /**
     * 解析每个数据集引用的可执行版本：数据集必须启用、语义版本必须已发布且已验证未漂移。
     *
     * <p>解析失败抛稳定错误码（由调用方留痕），不"跳过这个数据集继续刷新别的"——
     * 部分刷新会产出与版本声明不一致的报表。
     */
    private Map<String, AiReportRefreshTarget> resolveTargets(AiReportSpec spec) {
        Map<String, Map<String, Object>> plansByQueryRef = new LinkedHashMap<>();
        for (AiReportSpec.QueryRef queryRef : spec.queryRefs()) {
            plansByQueryRef.put(queryRef.id(), JsonUtils.parseObject(queryRef.planJson(), Map.class));
        }
        Map<String, AiReportRefreshTarget> targets = new LinkedHashMap<>();
        for (AiReportSpec.DatasetRef ref : spec.datasetRefs()) {
            Map<String, Object> plan = plansByQueryRef.get(ref.queryRef());
            if (plan == null || plan.get("datasetId") == null || plan.get("datasetVersion") == null) {
                // 版本里缺少计划锚点：无法证明要执行的是哪一份查询，停止
                throw exception(AI_REPORT_VERSION_NOT_FOUND);
            }
            String planId = String.valueOf(plan.get("datasetId"));
            int planVersionNo = Integer.parseInt(String.valueOf(plan.get("datasetVersion")));
            String code = planId.startsWith("dset_") ? planId.substring("dset_".length()) : planId;
            AiDatasetDO dataset = datasetMapper.selectByCode(code);
            if (dataset == null) {
                throw exception(AI_DATASET_VERSION_NOT_FOUND);
            }
            if (!AiDatasetDO.STATUS_ENABLED.equals(dataset.getStatus())) {
                // 来源停用即停止（不继续执行后续来源）
                throw exception(AI_DATASET_DISABLED);
            }
            AiDatasetVersionDO version =
                    datasetService
                            .getVersionPage(dataset.getId(), new PageParam().setPageSize(VERSION_PAGE_SIZE))
                            .getList()
                            .stream()
                            .filter(item -> item.getVersionNo() != null && item.getVersionNo() == planVersionNo)
                            .findFirst()
                            .orElseThrow(() -> exception(AI_DATASET_VERSION_NOT_FOUND));
            if (!AiDatasetVersionDO.STATUS_PUBLISHED.equals(version.getStatus())) {
                throw exception(AI_DATASET_VERSION_NOT_PUBLISHED);
            }
            if (AiDatasetVersionDO.VERIFICATION_DRIFTED.equals(version.getVerificationStatus())) {
                // schema 漂移：停止并给稳定原因
                throw exception(AI_DATASET_VERSION_DRIFTED);
            }
            if (!AiDatasetVersionDO.VERIFICATION_VERIFIED.equals(version.getVerificationStatus())) {
                throw exception(AI_DATASET_VERSION_NOT_VERIFIED);
            }
            String planJson = spec.queryRefsById().get(ref.queryRef()).planJson();
            targets.put(ref.id(), new AiReportRefreshTarget(dataset.getId(), version.getId(), planJson));
        }
        return targets;
    }

    // ==================== 结果组装 ====================

    private static AiReportDataBinder.ExecutionResult toExecutionResult(AiRunQueryExecutionResultDTO executed) {
        List<String> columns = new ArrayList<>();
        for (AiRunQueryExecutionResultDTO.Column column : executed.getColumns()) {
            columns.add(column.code());
        }
        return new AiReportDataBinder.ExecutionResult(
                executed.getResultRef(), columns, executed.getRows(), executed.getCompleteness());
    }

    /** 候选规格：只更新数据集引用的结果标识/行数/完整性（计划与列声明不变，由绑定器核对）。 */
    private static String refreshedSpec(String baseSpecJson, Map<String, AiReportDataBinder.ExecutionResult> datasets) {
        Map<String, Object> root = JsonUtils.parseObject(baseSpecJson, Map.class);
        List<Map<String, Object>> refs = new ArrayList<>();
        for (Object item : (List<?>) root.get("datasetRefs")) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ((Map<?, ?>) item).forEach((key, value) -> ref.put(String.valueOf(key), value));
            AiReportDataBinder.ExecutionResult result = datasets.get(String.valueOf(ref.get("id")));
            if (result != null) {
                ref.put("resultRef", result.resultRef());
                ref.put("rowCount", result.rows().size());
                ref.put("completeness", result.completeness());
            }
            refs.add(ref);
        }
        root.put("datasetRefs", refs);
        return JsonUtils.toJsonString(root);
    }

    /** 完整性：任一来源 PARTIAL 即 PARTIAL（不谎称完整统计）。 */
    private static String completeness(Map<String, AiReportDataBinder.ExecutionResult> datasets) {
        boolean partial = datasets.values().stream()
                .anyMatch(result -> !AiRunQueryExecutionResultDTO.COMPLETE.equals(result.completeness()));
        return partial ? AiRunQueryExecutionResultDTO.PARTIAL : AiRunQueryExecutionResultDTO.COMPLETE;
    }

    private AiReportRefreshResultDTO failed(AiReportDO report, AiReportVersionDO base, String reason, String note) {
        record(report, base, AiReportRefreshDO.STATUS_FAILED, reason, null, null, null);
        log.info(
                "[refresh][刷新失败][reportId={}, baseVersionNo={}, reason={}]",
                report.getId(),
                base.getVersionNo(),
                reason);
        return result(AiReportRefreshResultDTO.STATUS_FAILED, report, base, null, null, reason, null, note);
    }

    private void record(
            AiReportDO report,
            AiReportVersionDO base,
            String status,
            String reason,
            String dataJson,
            String completeness,
            Integer resultVersionNo) {
        refreshMapper.insert(new AiReportRefreshDO()
                .setReportId(report.getId())
                .setBaseVersionNo(base.getVersionNo())
                .setResultVersionNo(resultVersionNo)
                .setStatus(status)
                .setReason(reason)
                .setAsOf(LocalDateTime.now())
                .setCompleteness(completeness)
                .setDataJson(dataJson)
                .setSourcesJson(base.getSourcesJson())
                .setScopeRefsJson(base.getScopeRefsJson())
                .setScopeFingerprint(base.getScopeFingerprint())
                .setVersion(0));
    }

    private static AiReportRefreshResultDTO result(
            String status,
            AiReportDO report,
            AiReportVersionDO base,
            Integer resultVersionNo,
            String completeness,
            String reason,
            String dataJson,
            String note) {
        return new AiReportRefreshResultDTO()
                .setStatus(status)
                .setReportId(report.getId())
                .setBaseVersionNo(base.getVersionNo())
                .setResultVersionNo(resultVersionNo)
                .setAsOf(LocalDateTime.now())
                .setCompleteness(completeness)
                .setReason(reason)
                .setDataJson(dataJson)
                .setNote(note);
    }

    /** 一个数据集引用的可执行目标：数据集 + 语义版本 + 已校验计划。 */
    private record AiReportRefreshTarget(Long datasetId, Long datasetVersionId, String planJson) {}
}
