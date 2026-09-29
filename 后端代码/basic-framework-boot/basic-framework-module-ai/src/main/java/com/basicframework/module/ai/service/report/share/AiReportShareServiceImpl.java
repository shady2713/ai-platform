package com.basicframework.module.ai.service.report.share;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_ACCESS_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_SHARE_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_VERSION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.adapter.file.AiFileSubjectResolver;
import com.basicframework.module.ai.dal.dataobject.report.AiReportDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportShareAccessDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportShareDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportVersionDO;
import com.basicframework.module.ai.dal.dataobject.subject.AiSubjectDO;
import com.basicframework.module.ai.dal.mysql.report.AiReportMapper;
import com.basicframework.module.ai.dal.mysql.report.AiReportShareAccessMapper;
import com.basicframework.module.ai.dal.mysql.report.AiReportShareMapper;
import com.basicframework.module.ai.dal.mysql.report.AiReportVersionMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.ai.service.file.dto.AiFileSubject;
import com.basicframework.module.ai.service.report.persistence.AiReportScopeRef;
import com.basicframework.module.ai.service.report.persistence.AiReportScopeRefs;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareCreateDTO;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareCreateResultDTO;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareReadDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 报表受控分享实现（X11）：**可见权与源数据读取权分离**。
 *
 * <p>为什么拒绝是"404 + 原因码"而不是各自错误码：读取入口拿着凭据，任何"这凭据存在但……"的
 * 区分都会帮攻击者枚举；因此五类前置失败（主体不符/已撤销/已过期/授予者停用/凭据未知）对外
 * 同一个 {@code AI_REPORT_SHARE_NOT_EXISTS}，稳定原因只进访问审计（授予者可查）。降级态
 * （scope-uncovered）不同：凭据本身有效——可见性是授予者有意签发的——只是接收者当前源权限
 * 覆盖不了分享时的范围，所以返回 200 + 空内容，不假装分享不存在。
 *
 * <p>为什么授予者停用默认拒绝：分享是"人给人"的授权，授予者离职后入口必须关闭，
 * 否则报表所有者变更/离职会留下永久可见通道（保留规则可验证，见验收）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReportShareServiceImpl implements AiReportShareService {

    private final AiFileSubjectResolver subjectResolver;

    private final AiReportShareMapper shareMapper;

    private final AiReportShareAccessMapper accessMapper;

    private final AiReportMapper reportMapper;

    private final AiReportVersionMapper versionMapper;

    private final AiSubjectService subjectService;

    private final AiAuthorizationService authorizationService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiReportShareCreateResultDTO create(AiReportShareCreateDTO createDTO) {
        AiFileSubject subject = requireUserSubject();
        if (createDTO == null
                || createDTO.getReportId() == null
                || !StringUtils.hasText(createDTO.getGranteeExternalUserId())) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiReportDO report = reportMapper.selectById(createDTO.getReportId());
        if (report == null || !subject.sameAs(ownerOf(report))) {
            // 越权与不存在同语义：不借错误码枚举他人报表编号
            throw exception(AI_REPORT_NOT_FOUND);
        }
        if (subject.externalUserId().equals(createDTO.getGranteeExternalUserId())) {
            // 自己本来就是所有者：给自己发凭据只会制造第二条等价路径
            throw exception(AI_REQUEST_INVALID);
        }
        AiSubjectDO grantee = subjectService
                .findActiveSubject(subject.applicationId(), AiSubjectType.USER, createDTO.getGranteeExternalUserId())
                .orElseThrow(() -> exception(AI_REQUEST_INVALID));
        Integer versionNo = createDTO.getVersionNo() == null ? report.getLatestVersionNo() : createDTO.getVersionNo();
        if (versionMapper.selectByVersionNo(report.getId(), versionNo) == null) {
            throw exception(AI_REPORT_VERSION_NOT_FOUND);
        }
        if (shareMapper.selectActiveByReportAndGrantee(
                        subject.applicationId(),
                        report.getId(),
                        AiSubjectType.USER.name(),
                        createDTO.getGranteeExternalUserId())
                != null) {
            throw exception(AI_REPORT_SHARE_DUPLICATE);
        }
        String token = AiReportShareTokens.generate();
        AiReportShareDO share = new AiReportShareDO()
                .setTokenHash(AiReportShareTokens.digest(token))
                .setReportId(report.getId())
                .setVersionNo(versionNo)
                .setApplicationId(subject.applicationId())
                .setGrantorSubjectType(subject.subjectType().name())
                .setGrantorExternalUserId(subject.externalUserId())
                .setGranteeSubjectType(AiSubjectType.USER.name())
                .setGranteeExternalUserId(createDTO.getGranteeExternalUserId())
                .setGranteeDisplayName(displayName(grantee, createDTO.getGranteeExternalUserId()))
                .setStatus(AiReportShareDO.STATUS_ACTIVE)
                .setExpiresTime(normalizeExpiresTime(createDTO.getExpiresTime()))
                .setVersion(0);
        try {
            shareMapper.insert(share);
        } catch (DuplicateKeyException exception) {
            // 256 位随机摘要的碰撞不可达；撞唯一键只能说明出现了同摘要的既有行，按状态冲突拒绝
            throw exception(AI_STATE_CONFLICT);
        }
        return new AiReportShareCreateResultDTO()
                .setShareId(share.getId())
                .setToken(token)
                .setVersionNo(versionNo)
                .setGranteeExternalUserId(share.getGranteeExternalUserId())
                .setGranteeDisplayName(share.getGranteeDisplayName())
                .setExpiresTime(share.getExpiresTime());
    }

    /**
     * noRollbackFor 是**承重**的：拒绝路径要先落审计再抛 404，如果审计随异常回滚，
     * "谁试过读取"这一事实就丢了（集成测试实测暴露过）；因此读取事务对业务异常提交，
     * 惰性物化与审计行一并落库。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = ServiceException.class)
    public AiReportShareReadDTO readByToken(String token) {
        AiFileSubject subject = subjectResolver.resolveCurrent().orElseThrow(() -> exception(AI_ACCESS_DENIED));
        if (!StringUtils.hasText(token)) {
            return deny(subject, null, AiReportShareAccessDO.REASON_SUBJECT_MISMATCH);
        }
        AiReportShareDO share = shareMapper.selectByTokenHash(AiReportShareTokens.digest(token.trim()));
        if (share == null
                || subject.subjectType() != AiSubjectType.USER
                || !subject.applicationId().equals(share.getApplicationId())
                || !subject.externalUserId().equals(share.getGranteeExternalUserId())) {
            // 凭据未知与凭据不属于当前主体共用同一原因：不帮攻击者区分"错"与"无"
            return deny(subject, share, AiReportShareAccessDO.REASON_SUBJECT_MISMATCH);
        }
        if (AiReportShareDO.STATUS_REVOKED.equals(share.getStatus())) {
            return deny(subject, share, AiReportShareAccessDO.REASON_REVOKED);
        }
        if (AiReportShareDO.STATUS_EXPIRED.equals(share.getStatus())) {
            return deny(subject, share, AiReportShareAccessDO.REASON_EXPIRED);
        }
        if (share.getExpiresTime() != null && share.getExpiresTime().isBefore(LocalDateTime.now())) {
            // 到期惰性物化：读取时把仍 ACTIVE 的行置为 EXPIRED（不设常驻扫描任务）；
            // 并发撤销会让 CAS 落 0 行，但本次读取照样拒绝，物化留给撞到 ACTIVE 的下一次读取
            shareMapper.expireIfActive(share.getId(), share.getVersion());
            return deny(subject, share, AiReportShareAccessDO.REASON_EXPIRED);
        }
        AiSubjectType grantorType =
                AiSubjectType.parse(share.getGrantorSubjectType()).orElse(null);
        if (grantorType == null
                || subjectService
                        .findActiveSubject(share.getApplicationId(), grantorType, share.getGrantorExternalUserId())
                        .isEmpty()) {
            // 授予者主体已停用（离职）：默认拒绝，入口关闭
            return deny(subject, share, AiReportShareAccessDO.REASON_GRANTOR_UNAVAILABLE);
        }
        AiReportDO report = reportMapper.selectById(share.getReportId());
        AiReportVersionDO version = versionMapper.selectByVersionNo(share.getReportId(), share.getVersionNo());
        if (version == null || !scopeStillCovers(version, subject)) {
            // 降级态：可见性成立（授予者有意签发），但内容按接收者当前源权限复核不通过——统计与快照不出库
            recordAccess(
                    subject,
                    share,
                    AiReportShareAccessDO.OUTCOME_GRANTED,
                    AiReportShareAccessDO.REASON_SCOPE_UNCOVERED,
                    false);
            return degraded(share, report, version);
        }
        recordAccess(subject, share, AiReportShareAccessDO.OUTCOME_GRANTED, null, true);
        return degraded(share, report, version)
                .setContentAuthorized(true)
                .setReasonCode(null)
                .setSpecJson(version.getSpecJson())
                .setDataJson(version.getDataJson())
                .setAsOf(version.getAsOf())
                .setCompleteness(version.getCompleteness());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long shareId, Integer expectedVersion) {
        AiFileSubject subject = requireUserSubject();
        if (expectedVersion == null || expectedVersion < 0) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiReportShareDO share = shareId == null ? null : shareMapper.selectById(shareId);
        if (share == null || !isGrantor(subject, share)) {
            // 越权与不存在同语义：接收者或陌生人探测 shareId 拿不到任何区分信息
            throw exception(AI_REPORT_SHARE_NOT_EXISTS);
        }
        if (shareMapper.revokeIfActive(
                        share.getId(), subject.subjectType().name(), subject.externalUserId(), expectedVersion)
                > 0) {
            return;
        }
        AiReportShareDO fresh = shareMapper.selectById(share.getId());
        if (fresh != null && AiReportShareDO.STATUS_REVOKED.equals(fresh.getStatus())) {
            // 已撤销：幂等成功（重复撤销不报错）
            return;
        }
        // 仍 ACTIVE（乐观锁版本冲突）或已 EXPIRED：按当前事实拒绝，调用方回读后重试
        throw exception(AI_STATE_CONFLICT);
    }

    @Override
    public PageResult<AiReportShareDO> getSharePage(PageParam pageParam) {
        AiFileSubject subject = requireUserSubject();
        return shareMapper.selectGrantorPage(
                pageParam, subject.applicationId(), subject.subjectType().name(), subject.externalUserId());
    }

    @Override
    public List<AiReportShareAccessDO> getAccessRecords(Long shareId) {
        AiFileSubject subject = requireUserSubject();
        AiReportShareDO share = shareId == null ? null : shareMapper.selectById(shareId);
        if (share == null || !isGrantor(subject, share)) {
            throw exception(AI_REPORT_SHARE_NOT_EXISTS);
        }
        return accessMapper.selectByShare(share.getId());
    }

    /**
     * 内容范围复核（镜像 {@code AiReportServiceImpl.requireScopeStillCovers}，按接收者主体）：
     * 存储依赖与整体指纹自洽，且每一项在接收者当前范围下再鉴权并复现保存时的指纹。
     * 任一不通过返回 false（降级），而不是抛错。
     */
    private boolean scopeStillCovers(AiReportVersionDO version, AiFileSubject grantee) {
        List<AiReportScopeRef> refs = AiReportScopeRefs.fromJson(version.getScopeRefsJson());
        if (!AiReportScopeRefs.fingerprint(refs).equals(version.getScopeFingerprint())) {
            // 存储的依赖集合与整体指纹对不上：视为范围无法证明
            return false;
        }
        for (AiReportScopeRef ref : refs) {
            AiAuthorizationDecisionDTO decision = authorizationService.reauthorizeHistorical(
                    grantee.applicationId(),
                    grantee.subjectType().name(),
                    grantee.externalUserId(),
                    AiResourceType.parse(ref.getResourceType()).orElse(null),
                    ref.getResourceKey(),
                    AiAction.READ,
                    ref.getFingerprint());
            if (!decision.isAllowed() || !String.valueOf(ref.getFingerprint()).equals(decision.getScopeFingerprint())) {
                return false;
            }
        }
        return true;
    }

    /** 拒绝并留痕（404 防枚举）：与"分享不存在"同语义，原因只进审计。 */
    private AiReportShareReadDTO deny(AiFileSubject subject, AiReportShareDO share, String reasonCode) {
        recordAccess(subject, share, AiReportShareAccessDO.OUTCOME_DENIED, reasonCode, false);
        throw exception(AI_REPORT_SHARE_NOT_EXISTS);
    }

    private void recordAccess(
            AiFileSubject accessor,
            AiReportShareDO share,
            String outcome,
            String reasonCode,
            boolean contentAuthorized) {
        accessMapper.insert(new AiReportShareAccessDO()
                .setShareId(share == null ? null : share.getId())
                .setReportId(share == null ? null : share.getReportId())
                .setApplicationId(share == null ? accessor.applicationId() : share.getApplicationId())
                .setSubjectType(accessor.subjectType().name())
                .setExternalUserId(accessor.externalUserId())
                .setOutcome(outcome)
                .setReasonCode(reasonCode)
                .setContentAuthorized(contentAuthorized));
    }

    /** 降级态/成功共用的可见性字段（内容字段按调用方覆盖）。 */
    private static AiReportShareReadDTO degraded(AiReportShareDO share, AiReportDO report, AiReportVersionDO version) {
        return new AiReportShareReadDTO()
                .setShareId(share.getId())
                .setReportId(share.getReportId())
                .setReportName(report == null ? null : report.getName())
                .setVersionNo(share.getVersionNo())
                .setMode(version == null ? null : version.getMode())
                .setContentAuthorized(false)
                .setReasonCode(AiReportShareAccessDO.REASON_SCOPE_UNCOVERED)
                .setExpiresTime(share.getExpiresTime());
    }

    private static boolean isGrantor(AiFileSubject subject, AiReportShareDO share) {
        return subject.applicationId().equals(share.getApplicationId())
                && subject.subjectType().name().equals(share.getGrantorSubjectType())
                && subject.externalUserId().equals(share.getGrantorExternalUserId());
    }

    private static AiFileSubject.AiFileBindingOwner ownerOf(AiReportDO report) {
        return new AiFileSubject.AiFileBindingOwner(
                report.getApplicationId(), report.getSubjectType(), report.getExternalUserId());
    }

    /** 分享是"人给人"的功能：APP 主体（应用身份）不能作为授予者或接收者。 */
    private AiFileSubject requireUserSubject() {
        AiFileSubject subject = subjectResolver.resolveCurrent().orElseThrow(() -> exception(AI_ACCESS_DENIED));
        if (subject.subjectType() != AiSubjectType.USER) {
            throw exception(AI_ACCESS_DENIED);
        }
        return subject;
    }

    /** 过期时间必须在未来；写库前截断到秒（datetime(0) 会把亚秒值向上取整成"未来 1 秒"）。 */
    private static LocalDateTime normalizeExpiresTime(LocalDateTime expiresTime) {
        if (expiresTime == null) {
            return null;
        }
        LocalDateTime truncated = expiresTime.truncatedTo(ChronoUnit.SECONDS);
        if (!truncated.isAfter(LocalDateTime.now())) {
            throw exception(AI_REQUEST_INVALID);
        }
        return truncated;
    }

    private static String displayName(AiSubjectDO grantee, String externalUserId) {
        return StringUtils.hasText(grantee.getDisplayName()) ? grantee.getDisplayName() : externalUserId;
    }
}
