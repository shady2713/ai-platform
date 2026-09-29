package com.basicframework.module.ai.service.report.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.file.AiFileSubjectResolver;
import com.basicframework.module.ai.dal.dataobject.report.AiReportDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportShareAccessDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportShareDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportVersionDO;
import com.basicframework.module.ai.dal.mysql.report.AiReportMapper;
import com.basicframework.module.ai.dal.mysql.report.AiReportShareAccessMapper;
import com.basicframework.module.ai.dal.mysql.report.AiReportShareMapper;
import com.basicframework.module.ai.dal.mysql.report.AiReportVersionMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.ai.service.file.dto.AiFileSubject;
import com.basicframework.module.ai.service.report.persistence.AiReportScopeRef;
import com.basicframework.module.ai.service.report.persistence.AiReportScopeRefs;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareReadDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 报表受控分享——按令牌读取（X11 单测）：五类前置拒绝（404 防枚举 + 审计 DENIED + 稳定原因码）、
 * 到期惰性物化、源权限复核失败的降级态（不抛错、内容全空）与完整成功。
 */
@ExtendWith(MockitoExtension.class)
class AiReportShareServiceImplReadTest {

    private static final Long APPLICATION_ID = 7L;

    private static final Long REPORT_ID = 100L;

    private static final Long SHARE_ID = 501L;

    private static final String TOKEN = "5zR7DQr3v0LhWuL9xQ1TzA2bC4dE6fG8hI0jK2lM4nY";

    @Mock
    private AiFileSubjectResolver subjectResolver;

    @Mock
    private AiReportShareMapper shareMapper;

    @Mock
    private AiReportShareAccessMapper accessMapper;

    @Mock
    private AiReportMapper reportMapper;

    @Mock
    private AiReportVersionMapper versionMapper;

    @Mock
    private AiSubjectService subjectService;

    @Mock
    private AiAuthorizationService authorizationService;

    private AiReportShareServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiReportShareServiceImpl(
                subjectResolver,
                shareMapper,
                accessMapper,
                reportMapper,
                versionMapper,
                subjectService,
                authorizationService);
    }

    @Test
    void readReturnsFullContentWhenTheGranteeStillCoversTheScope() {
        loginAs("bob");
        stubShare(share());
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report());
        when(versionMapper.selectByVersionNo(REPORT_ID, 2)).thenReturn(versionWithScope());
        when(authorizationService.reauthorizeHistorical(
                        eq(APPLICATION_ID),
                        eq("USER"),
                        eq("bob"),
                        eq(AiResourceType.KNOWLEDGE_BASE),
                        eq("it-share-kb"),
                        eq(AiAction.READ),
                        eq("fp-1")))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true).setScopeFingerprint("fp-1"));

        AiReportShareReadDTO result = service.readByToken(TOKEN);

        assertThat(result.isContentAuthorized()).isTrue();
        assertThat(result.getReasonCode()).isNull();
        assertThat(result.getSpecJson()).isEqualTo("{\"title\":\"Q3 汇总\"}");
        assertThat(result.getDataJson()).isEqualTo("{\"rows\":[1,2]}");
        assertThat(result.getAsOf()).isEqualTo(versionWithScope().getAsOf());
        assertThat(result.getCompleteness()).isEqualTo("COMPLETE");
        // 每次读取一条审计：GRANTED、完整成功、原因码为空
        ArgumentCaptor<AiReportShareAccessDO> captor = ArgumentCaptor.forClass(AiReportShareAccessDO.class);
        verify(accessMapper).insert(captor.capture());
        assertThat(captor.getValue().getOutcome()).isEqualTo(AiReportShareAccessDO.OUTCOME_GRANTED);
        assertThat(captor.getValue().getReasonCode()).isNull();
        assertThat(captor.getValue().getContentAuthorized()).isTrue();
        assertThat(captor.getValue().getShareId()).isEqualTo(SHARE_ID);
        assertThat(captor.getValue().getSubjectType()).isEqualTo("USER");
        assertThat(captor.getValue().getExternalUserId()).isEqualTo("bob");
    }

    @Test
    void readDeniesUnknownTokensAsSubjectMismatchWithoutLeakingDifference() {
        loginAs("bob");
        when(shareMapper.selectByTokenHash(AiReportShareTokens.digest(TOKEN))).thenReturn(null);

        assertDenied(TOKEN, AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);
        assertAuditDenied(null, null, AiReportShareAccessDO.REASON_SUBJECT_MISMATCH);
    }

    @Test
    void readDeniesBlankTokensWithoutAnyLookup() {
        loginAs("bob");

        assertDenied("   ", AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);
        assertAuditDenied(null, null, AiReportShareAccessDO.REASON_SUBJECT_MISMATCH);
    }

    @Test
    void readDeniesWhenTheTokenBelongsToAnotherGrantee() {
        loginAs("carol");
        when(shareMapper.selectByTokenHash(AiReportShareTokens.digest(TOKEN))).thenReturn(share());

        assertDenied(TOKEN, AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);
        assertAuditDenied(SHARE_ID, REPORT_ID, AiReportShareAccessDO.REASON_SUBJECT_MISMATCH);
    }

    @Test
    void readDeniesAppSubjectsBecauseSharesArePersonToPerson() {
        loggedInUser = "";
        when(subjectResolver.resolveCurrent())
                .thenReturn(Optional.of(new AiFileSubject(APPLICATION_ID, AiSubjectType.APP, "", 33L)));
        when(shareMapper.selectByTokenHash(AiReportShareTokens.digest(TOKEN))).thenReturn(share());

        assertDenied(TOKEN, AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);
        assertAuditDenied(SHARE_ID, REPORT_ID, AiReportShareAccessDO.REASON_SUBJECT_MISMATCH);
    }

    @Test
    void readDeniesRevokedSharesIncludingThePinnedHistoricalVersion() {
        loginAs("bob");
        AiReportShareDO share = share();
        share.setStatus(AiReportShareDO.STATUS_REVOKED);
        when(shareMapper.selectByTokenHash(AiReportShareTokens.digest(TOKEN))).thenReturn(share);

        assertDenied(TOKEN, AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);
        assertAuditDenied(SHARE_ID, REPORT_ID, AiReportShareAccessDO.REASON_REVOKED);
        // 已撤销：连版本内容都不再查询（历史版本同样拒绝）
        verify(shareMapper).selectByTokenHash(AiReportShareTokens.digest(TOKEN));
    }

    @Test
    void readDeniesAlreadyMaterializedExpiredShares() {
        loginAs("bob");
        AiReportShareDO share = share();
        share.setStatus(AiReportShareDO.STATUS_EXPIRED);
        when(shareMapper.selectByTokenHash(AiReportShareTokens.digest(TOKEN))).thenReturn(share);

        assertDenied(TOKEN, AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);
        assertAuditDenied(SHARE_ID, REPORT_ID, AiReportShareAccessDO.REASON_EXPIRED);
    }

    @Test
    void readMaterializesExpiryLazilyAndThenDenies() {
        loginAs("bob");
        AiReportShareDO share = share();
        share.setExpiresTime(LocalDateTime.now().minusSeconds(1));
        when(shareMapper.selectByTokenHash(AiReportShareTokens.digest(TOKEN))).thenReturn(share);

        assertDenied(TOKEN, AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);
        assertAuditDenied(SHARE_ID, REPORT_ID, AiReportShareAccessDO.REASON_EXPIRED);
        // 惰性物化：读取时把仍 ACTIVE 的行置为 EXPIRED（不设常驻扫描任务）
        verify(shareMapper).expireIfActive(SHARE_ID, share.getVersion());
    }

    @Test
    void readDeniesWhenTheGrantorSubjectIsNoLongerActive() {
        loginAs("bob");
        when(shareMapper.selectByTokenHash(AiReportShareTokens.digest(TOKEN))).thenReturn(share());
        when(subjectService.findActiveSubject(APPLICATION_ID, AiSubjectType.USER, "alice"))
                .thenReturn(Optional.empty());

        assertDenied(TOKEN, AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);
        assertAuditDenied(SHARE_ID, REPORT_ID, AiReportShareAccessDO.REASON_GRANTOR_UNAVAILABLE);
    }

    @Test
    void readDegradesWithoutContentWhenTheGranteeLosesTheSourceScope() {
        loginAs("bob");
        stubShare(share());
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report());
        when(versionMapper.selectByVersionNo(REPORT_ID, 2)).thenReturn(versionWithScope());
        when(authorizationService.reauthorizeHistorical(
                        eq(APPLICATION_ID),
                        eq("USER"),
                        eq("bob"),
                        eq(AiResourceType.KNOWLEDGE_BASE),
                        eq("it-share-kb"),
                        eq(AiAction.READ),
                        eq("fp-1")))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false).setDenyReason("GRANT_NOT_FOUND"));

        AiReportShareReadDTO result = service.readByToken(TOKEN);

        // 降级态：可见性保留（授予者有意签发），内容全空，不抛错
        assertThat(result.isContentAuthorized()).isFalse();
        assertThat(result.getReasonCode()).isEqualTo(AiReportShareAccessDO.REASON_SCOPE_UNCOVERED);
        assertThat(result.getSpecJson()).isNull();
        assertThat(result.getDataJson()).isNull();
        assertThat(result.getAsOf()).isNull();
        assertThat(result.getCompleteness()).isNull();
        assertThat(result.getShareId()).isEqualTo(SHARE_ID);
        assertThat(result.getReportId()).isEqualTo(REPORT_ID);
        assertThat(result.getReportName()).isEqualTo("Q3 汇总报表");
        assertThat(result.getVersionNo()).isEqualTo(2);
        ArgumentCaptor<AiReportShareAccessDO> captor = ArgumentCaptor.forClass(AiReportShareAccessDO.class);
        verify(accessMapper).insert(captor.capture());
        assertThat(captor.getValue().getOutcome()).isEqualTo(AiReportShareAccessDO.OUTCOME_GRANTED);
        assertThat(captor.getValue().getReasonCode()).isEqualTo(AiReportShareAccessDO.REASON_SCOPE_UNCOVERED);
        assertThat(captor.getValue().getContentAuthorized()).isFalse();
    }

    @Test
    void readDegradesWhenTheStoredScopeFingerprintDoesNotSelfVerify() {
        loginAs("bob");
        stubShare(share());
        AiReportVersionDO version = versionWithScope();
        version.setScopeFingerprint("tampered");
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report());
        when(versionMapper.selectByVersionNo(REPORT_ID, 2)).thenReturn(version);

        AiReportShareReadDTO result = service.readByToken(TOKEN);

        // 存储依赖与整体指纹对不上：fail-closed，且不再调用授权层
        assertThat(result.isContentAuthorized()).isFalse();
        assertThat(result.getSpecJson()).isNull();
        verify(authorizationService, org.mockito.Mockito.never())
                .reauthorizeHistorical(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void readDegradesWhenEveryRefMustCoverAndOneRefFailsTheFingerprintComparison() {
        loginAs("bob");
        stubShare(share());
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report());
        when(versionMapper.selectByVersionNo(REPORT_ID, 2)).thenReturn(versionWithScope());
        // 授权放行但指纹与保存时不一致：范围已变化，同样是"覆盖不了"
        when(authorizationService.reauthorizeHistorical(
                        eq(APPLICATION_ID),
                        eq("USER"),
                        eq("bob"),
                        eq(AiResourceType.KNOWLEDGE_BASE),
                        eq("it-share-kb"),
                        eq(AiAction.READ),
                        eq("fp-1")))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true).setScopeFingerprint("fp-2"));

        AiReportShareReadDTO result = service.readByToken(TOKEN);

        assertThat(result.isContentAuthorized()).isFalse();
        assertThat(result.getReasonCode()).isEqualTo(AiReportShareAccessDO.REASON_SCOPE_UNCOVERED);
    }

    @Test
    void readDegradesWhenThePinnedVersionRowIsMissing() {
        loginAs("bob");
        stubShare(share());
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report());
        when(versionMapper.selectByVersionNo(REPORT_ID, 2)).thenReturn(null);

        AiReportShareReadDTO result = service.readByToken(TOKEN);

        assertThat(result.isContentAuthorized()).isFalse();
        assertThat(result.getReasonCode()).isEqualTo(AiReportShareAccessDO.REASON_SCOPE_UNCOVERED);
        assertThat(result.getMode()).isNull();
    }

    @Test
    void readNeverRecordsThePlaintextTokenAnywhere() {
        loginAs("bob");
        stubShare(share());
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report());
        when(versionMapper.selectByVersionNo(REPORT_ID, 2)).thenReturn(versionWithScope());
        when(authorizationService.reauthorizeHistorical(
                        eq(APPLICATION_ID),
                        eq("USER"),
                        eq("bob"),
                        eq(AiResourceType.KNOWLEDGE_BASE),
                        eq("it-share-kb"),
                        eq(AiAction.READ),
                        eq("fp-1")))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true).setScopeFingerprint("fp-1"));

        service.readByToken(TOKEN);

        ArgumentCaptor<AiReportShareAccessDO> captor = ArgumentCaptor.forClass(AiReportShareAccessDO.class);
        verify(accessMapper).insert(captor.capture());
        assertThat(captor.getValue().toString()).doesNotContain(TOKEN);
        assertThat(captor.getValue().toString()).doesNotContain(AiReportShareTokens.digest(TOKEN));
    }

    // ========== fixtures ==========

    private void loginAs(String externalUserId) {
        loggedInUser = externalUserId;
        when(subjectResolver.resolveCurrent())
                .thenReturn(Optional.of(new AiFileSubject(APPLICATION_ID, AiSubjectType.USER, externalUserId, 33L)));
    }

    private void stubShare(AiReportShareDO share) {
        when(shareMapper.selectByTokenHash(AiReportShareTokens.digest(TOKEN))).thenReturn(share);
        when(subjectService.findActiveSubject(APPLICATION_ID, AiSubjectType.USER, "alice"))
                .thenReturn(Optional.of(new com.basicframework.module.ai.dal.dataobject.subject.AiSubjectDO()));
    }

    private AiReportShareDO share() {
        return new AiReportShareDO()
                .setId(SHARE_ID)
                .setTokenHash(AiReportShareTokens.digest(TOKEN))
                .setReportId(REPORT_ID)
                .setVersionNo(2)
                .setApplicationId(APPLICATION_ID)
                .setGrantorSubjectType("USER")
                .setGrantorExternalUserId("alice")
                .setGranteeSubjectType("USER")
                .setGranteeExternalUserId("bob")
                .setStatus(AiReportShareDO.STATUS_ACTIVE)
                .setVersion(3);
    }

    private AiReportDO report() {
        return new AiReportDO()
                .setId(REPORT_ID)
                .setName("Q3 汇总报表")
                .setApplicationId(APPLICATION_ID)
                .setSubjectType("USER")
                .setExternalUserId("alice");
    }

    /** 单一知识库依赖、指纹自洽的版本（接收者 bob 的授权判定由各用例 stub）。 */
    private AiReportVersionDO versionWithScope() {
        AiReportScopeRef ref = new AiReportScopeRef("KNOWLEDGE_BASE", "it-share-kb", "fp-1");
        List<AiReportScopeRef> refs = List.of(ref);
        return new AiReportVersionDO()
                .setReportId(REPORT_ID)
                .setVersionNo(2)
                .setMode("SNAPSHOT")
                .setSpecJson("{\"title\":\"Q3 汇总\"}")
                .setDataJson("{\"rows\":[1,2]}")
                .setScopeRefsJson(AiReportScopeRefs.toJson(refs))
                .setScopeFingerprint(AiReportScopeRefs.fingerprint(refs))
                .setAsOf(LocalDateTime.of(2026, 9, 1, 8, 0))
                .setCompleteness("COMPLETE");
    }

    private void assertDenied(String token, ErrorCode expected) {
        assertThatThrownBy(() -> service.readByToken(token))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }

    private void assertAuditDenied(Long shareId, Long reportId, String reasonCode) {
        ArgumentCaptor<AiReportShareAccessDO> captor = ArgumentCaptor.forClass(AiReportShareAccessDO.class);
        verify(accessMapper).insert(captor.capture());
        AiReportShareAccessDO access = captor.getValue();
        assertThat(access.getOutcome()).isEqualTo(AiReportShareAccessDO.OUTCOME_DENIED);
        assertThat(access.getReasonCode()).isEqualTo(reasonCode);
        assertThat(access.getContentAuthorized()).isFalse();
        assertThat(access.getShareId()).isEqualTo(shareId);
        assertThat(access.getReportId()).isEqualTo(reportId);
        assertThat(access.getApplicationId()).isEqualTo(APPLICATION_ID);
        assertThat(access.getExternalUserId()).isEqualTo(loggedInUser);
    }

    private String loggedInUser;
}
