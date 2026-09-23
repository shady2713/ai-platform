package com.basicframework.module.ai.service.report.persistence;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_ACCESS_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_CODE_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_SCOPE_CHANGED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_SNAPSHOT_DATA_REQUIRED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_SOURCE_RUN_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_VERSION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.report.AiReportDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportVersionDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.mysql.report.AiReportMapper;
import com.basicframework.module.ai.dal.mysql.report.AiReportVersionMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.report.persistence.dto.AiReportSaveDTO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 报表保存与版本存储实现（R04）。
 *
 * <p>为什么"归属判定"和"范围复核"要分开：归属回答"这份报表是不是你的"（私人报表按会话身份，
 * 越权与不存在同语义，不借错误码枚举他人报表编号）；范围复核回答"你保存时能看到的资源，
 * 现在是否仍被你的授权覆盖"（A03 逐项范围指纹）。两者都不满足时必须拒绝。
 *
 * <p>为什么版本不可变是结构性的：本类**没有**"更新版本内容"的方法，保存/对话修改只会新增版本号；
 * 表上还有 {@code (report_id, version_no)} 在存活行内的唯一索引兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReportServiceImpl implements AiReportService {

    /** 模式白名单。 */
    private static final Set<String> MODES = Set.of(AiReportDO.MODE_SNAPSHOT, AiReportDO.MODE_REFRESHABLE);

    /** 报表标识格式：小写字母开头，仅小写字母/数字/下划线/连字符。 */
    private static final String CODE_PATTERN = "^[a-z][a-z0-9_-]{2,63}$";

    private final AiReportMapper reportMapper;

    private final AiReportVersionMapper versionMapper;

    private final AiRunMapper runMapper;

    private final AiConversationSubjectResolver subjectResolver;

    private final AiAuthorizationService authorizationService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AiReportSaveDTO saveDTO) {
        AiConversationSubject subject = requireSubject();
        requireCreateFields(saveDTO);
        if (reportMapper.selectByCode(subject.applicationId(), saveDTO.getCode()) != null) {
            throw exception(AI_REPORT_CODE_DUPLICATE, saveDTO.getCode());
        }
        List<AiReportScopeRef> refs = authorizedRefs(saveDTO, subject);
        AiReportDO report = new AiReportDO()
                .setCode(saveDTO.getCode())
                .setName(saveDTO.getName())
                .setDescription(saveDTO.getDescription() == null ? "" : saveDTO.getDescription())
                .setApplicationId(subject.applicationId())
                .setSubjectType(subject.subjectType().name())
                .setExternalUserId(subject.externalUserId())
                .setMode(saveDTO.getMode())
                .setServiceId(saveDTO.getServiceId())
                .setReleaseId(saveDTO.getReleaseId())
                .setThemeId(saveDTO.getThemeId())
                .setThemeRevision(saveDTO.getThemeRevision())
                .setSchemaVersion(StringUtils.hasText(saveDTO.getSchemaVersion()) ? saveDTO.getSchemaVersion() : "1.0")
                .setLatestVersionNo(1)
                .setPublishedVersionNo(1)
                .setVersion(0);
        try {
            reportMapper.insert(report);
        } catch (DuplicateKeyException exception) {
            // 并发创建同标识：唯一索引是最终裁判（存活行内唯一）
            throw exception(AI_REPORT_CODE_DUPLICATE, saveDTO.getCode());
        }
        insertVersion(report, 1, saveDTO, refs);
        return report.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveVersion(AiReportSaveDTO saveDTO) {
        AiConversationSubject subject = requireSubject();
        if (saveDTO == null || saveDTO.getId() == null || saveDTO.getVersion() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        requireSaveFields(saveDTO);
        AiReportDO report = requireOwnedReport(saveDTO.getId(), subject);
        if (!report.getMode().equals(saveDTO.getMode())) {
            // 模式是报表级语义：保存新版本不能把快照偷偷改成可刷新
            throw exception(AI_REQUEST_INVALID);
        }
        List<AiReportScopeRef> refs = authorizedRefs(saveDTO, subject);
        int nextVersionNo = report.getLatestVersionNo() + 1;
        if (reportMapper.updateWithVersion(
                        new AiReportDO()
                                .setId(report.getId())
                                .setName(saveDTO.getName())
                                .setDescription(saveDTO.getDescription() == null ? "" : saveDTO.getDescription())
                                .setThemeId(saveDTO.getThemeId())
                                .setThemeRevision(saveDTO.getThemeRevision())
                                .setLatestVersionNo(nextVersionNo)
                                .setPublishedVersionNo(nextVersionNo)
                                .setVersion(report.getVersion() + 1),
                        saveDTO.getVersion())
                == 0) {
            // 并发修改：乐观锁失败即版本冲突（AT-046），不覆盖他人改动
            throw exception(AI_STATE_CONFLICT);
        }
        insertVersion(report, nextVersionNo, saveDTO, refs);
        return report.getId();
    }

    @Override
    public AiReportDO getReport(Long id) {
        return requireOwnedReport(id, requireSubject());
    }

    @Override
    public PageResult<AiReportDO> getReportPage(PageParam pageParam, String mode) {
        AiConversationSubject subject = requireSubject();
        if (mode != null && !MODES.contains(mode)) {
            throw exception(AI_REQUEST_INVALID);
        }
        return reportMapper.selectPage(
                pageParam, subject.applicationId(), subject.subjectType().name(), subject.externalUserId(), mode);
    }

    @Override
    public AiReportVersionDO getVersion(Long reportId, Integer versionNo) {
        AiConversationSubject subject = requireSubject();
        AiReportDO report = requireOwnedReport(reportId, subject);
        if (versionNo == null) {
            throw exception(AI_REPORT_VERSION_NOT_FOUND);
        }
        return requireReadableVersion(report, versionNo, subject);
    }

    @Override
    public List<AiReportVersionDO> listVersions(Long reportId) {
        requireOwnedReport(reportId, requireSubject());
        return versionMapper.selectByReport(reportId);
    }

    @Override
    public AiReportVersionDO readCurrent(Long reportId) {
        AiConversationSubject subject = requireSubject();
        AiReportDO report = requireOwnedReport(reportId, subject);
        return requireReadableVersion(report, report.getPublishedVersionNo(), subject);
    }

    /** 读取版本前必须做范围复核：**旧版本编号也是读取入口**，复制编号绕不过去（第 5 步）。 */
    private AiReportVersionDO requireReadableVersion(
            AiReportDO report, Integer versionNo, AiConversationSubject subject) {
        AiReportVersionDO version = versionMapper.selectByVersionNo(report.getId(), versionNo);
        if (version == null) {
            throw exception(AI_REPORT_VERSION_NOT_FOUND);
        }
        requireScopeStillCovers(version, subject);
        return version;
    }

    /**
     * 范围复核（第 4 步）：逐项用 A03 的历史再鉴权接口比对保存时的指纹。
     * 当前范围无法证明覆盖原范围（判定拒绝或指纹不一致）→ 拒绝显示（AT-048）。
     */
    private void requireScopeStillCovers(AiReportVersionDO version, AiConversationSubject subject) {
        List<AiReportScopeRef> refs = AiReportScopeRefs.fromJson(version.getScopeRefsJson());
        if (!AiReportScopeRefs.fingerprint(refs).equals(version.getScopeFingerprint())) {
            // 存储的依赖集合与整体指纹对不上：视为范围无法证明，拒绝显示
            throw exception(AI_REPORT_SCOPE_CHANGED);
        }
        for (AiReportScopeRef ref : refs) {
            AiAuthorizationDecisionDTO decision = authorizationService.reauthorizeHistorical(
                    subject.applicationId(),
                    subject.subjectType().name(),
                    subject.externalUserId(),
                    AiResourceType.parse(ref.getResourceType()).orElse(null),
                    ref.getResourceKey(),
                    AiAction.READ,
                    ref.getFingerprint());
            if (!decision.isAllowed() || !String.valueOf(ref.getFingerprint()).equals(decision.getScopeFingerprint())) {
                throw exception(AI_REPORT_SCOPE_CHANGED);
            }
        }
    }

    /** 引用资源再鉴权（第 3 步）：逐项 READ 判定并记下本次指纹，任一项拒绝即拒绝保存。 */
    private List<AiReportScopeRef> authorizedRefs(AiReportSaveDTO saveDTO, AiConversationSubject subject) {
        requireOwnedRun(saveDTO.getCreatedByRun(), subject);
        List<AiReportScopeRef> declared;
        try {
            declared = AiReportScopeRefs.parse(saveDTO.getSourcesJson());
        } catch (IllegalArgumentException exception) {
            // 依赖声明不合法：不静默跳过，直接拒绝
            throw exception(AI_REQUEST_INVALID);
        }
        List<AiReportScopeRef> verified = new ArrayList<>(declared.size());
        for (AiReportScopeRef ref : declared) {
            AiAuthorizationDecisionDTO decision = authorizationService.authorize(
                    subject.applicationId(),
                    subject.subjectType().name(),
                    subject.externalUserId(),
                    AiResourceType.parse(ref.getResourceType()).orElse(null),
                    ref.getResourceKey(),
                    AiAction.READ,
                    List.of());
            if (!decision.isAllowed() || !StringUtils.hasText(decision.getScopeFingerprint())) {
                throw exception(AI_ACCESS_DENIED);
            }
            verified.add(AiReportScopeRefs.withFingerprint(ref, decision.getScopeFingerprint()));
        }
        return verified;
    }

    /** 跨用户保存他人运行必须拒绝：来源运行不存在或不属于当前主体同语义（404）。 */
    private void requireOwnedRun(String runKey, AiConversationSubject subject) {
        if (!StringUtils.hasText(runKey)) {
            return;
        }
        AiRunDO run = runMapper.selectOne(new LambdaQueryWrapperX<AiRunDO>().eq(AiRunDO::getRunKey, runKey));
        if (run == null || !subject.sameAs(run.getApplicationId(), run.getSubjectType(), run.getExternalUserId())) {
            throw exception(AI_REPORT_SOURCE_RUN_NOT_FOUND);
        }
    }

    private void insertVersion(AiReportDO report, int versionNo, AiReportSaveDTO saveDTO, List<AiReportScopeRef> refs) {
        boolean snapshot = AiReportDO.MODE_SNAPSHOT.equals(report.getMode());
        if (snapshot && !StringUtils.hasText(saveDTO.getDataJson())) {
            // 快照模式必须带数据：没有数据的"快照"无法按保存时的样子展示
            throw exception(AI_REPORT_SNAPSHOT_DATA_REQUIRED);
        }
        AiReportVersionDO version = new AiReportVersionDO()
                .setReportId(report.getId())
                .setVersionNo(versionNo)
                .setMode(report.getMode())
                .setSpecJson(saveDTO.getSpecJson())
                .setDataJson(snapshot ? saveDTO.getDataJson() : null)
                .setSourcesJson(saveDTO.getSourcesJson())
                .setScopeRefsJson(AiReportScopeRefs.toJson(refs))
                .setScopeFingerprint(AiReportScopeRefs.fingerprint(refs))
                .setAsOf(snapshot ? LocalDateTime.now() : null)
                .setCompleteness(saveDTO.getCompleteness())
                .setCreatedByRun(saveDTO.getCreatedByRun())
                .setVersion(0);
        versionMapper.insert(version);
    }

    private AiReportDO requireOwnedReport(Long reportId, AiConversationSubject subject) {
        AiReportDO report = reportId == null ? null : reportMapper.selectById(reportId);
        if (report == null
                || !subject.sameAs(report.getApplicationId(), report.getSubjectType(), report.getExternalUserId())) {
            // 越权与不存在同语义：不借错误码枚举他人报表编号
            throw exception(AI_REPORT_NOT_FOUND);
        }
        return report;
    }

    private AiConversationSubject requireSubject() {
        return subjectResolver.resolveCurrent().orElseThrow(() -> exception(AI_ACCESS_DENIED));
    }

    private static void requireCreateFields(AiReportSaveDTO saveDTO) {
        if (saveDTO == null
                || !StringUtils.hasText(saveDTO.getCode())
                || !saveDTO.getCode().matches(CODE_PATTERN)
                || !MODES.contains(saveDTO.getMode())) {
            throw exception(AI_REQUEST_INVALID);
        }
        requireSaveFields(saveDTO);
    }

    private static void requireSaveFields(AiReportSaveDTO saveDTO) {
        if (saveDTO == null || !StringUtils.hasText(saveDTO.getName()) || !StringUtils.hasText(saveDTO.getSpecJson())) {
            throw exception(AI_REQUEST_INVALID);
        }
    }
}
