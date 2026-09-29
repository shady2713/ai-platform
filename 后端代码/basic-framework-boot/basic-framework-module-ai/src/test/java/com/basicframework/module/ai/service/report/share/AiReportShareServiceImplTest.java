package com.basicframework.module.ai.service.report.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.adapter.file.AiFileSubjectResolver;
import com.basicframework.module.ai.dal.dataobject.report.AiReportDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportShareDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportVersionDO;
import com.basicframework.module.ai.dal.dataobject.subject.AiSubjectDO;
import com.basicframework.module.ai.dal.mysql.report.AiReportMapper;
import com.basicframework.module.ai.dal.mysql.report.AiReportShareAccessMapper;
import com.basicframework.module.ai.dal.mysql.report.AiReportShareMapper;
import com.basicframework.module.ai.dal.mysql.report.AiReportVersionMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.file.dto.AiFileSubject;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareCreateDTO;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareCreateResultDTO;
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
import org.springframework.dao.DuplicateKeyException;

/**
 * 报表受控分享——创建/撤销/授予者视角（X11 单测）：
 * 归属判定、接收者校验、去重、版本固定、过期时间收窄、CAS 撤销与幂等重放。
 */
@ExtendWith(MockitoExtension.class)
class AiReportShareServiceImplTest {

    private static final Long APPLICATION_ID = 7L;

    private static final Long REPORT_ID = 100L;

    private static final Long TICKET_ID = 33L;

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

    // ========== create ==========

    @Test
    void createPinsVersionStoresOnlyDigestAndReturnsPlaintextOnce() {
        loginAs("alice");
        stubOwnedReport(2);
        stubActiveGrantee("bob", "Bob");
        when(versionMapper.selectByVersionNo(REPORT_ID, 2)).thenReturn(new AiReportVersionDO());
        when(shareMapper.selectActiveByReportAndGrantee(APPLICATION_ID, REPORT_ID, "USER", "bob"))
                .thenReturn(null);
        when(shareMapper.insert(any(AiReportShareDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AiReportShareDO.class).setId(501L);
            return 1;
        });

        AiReportShareCreateResultDTO result = service.create(
                new AiReportShareCreateDTO().setReportId(REPORT_ID).setGranteeExternalUserId("bob"));

        assertThat(result.getShareId()).isEqualTo(501L);
        assertThat(result.getVersionNo()).isEqualTo(2);
        assertThat(result.getGranteeDisplayName()).isEqualTo("Bob");
        // 明文令牌只在结果里出现一次；落库的是它的 SHA-256 摘要，且摘要与明文不同
        ArgumentCaptor<AiReportShareDO> captor = ArgumentCaptor.forClass(AiReportShareDO.class);
        verify(shareMapper).insert(captor.capture());
        AiReportShareDO inserted = captor.getValue();
        assertThat(inserted.getTokenHash()).isEqualTo(AiReportShareTokens.digest(result.getToken()));
        assertThat(inserted.getTokenHash()).hasSize(64).isNotEqualTo(result.getToken());
        assertThat(inserted.getStatus()).isEqualTo(AiReportShareDO.STATUS_ACTIVE);
        assertThat(inserted.getGranteeSubjectType()).isEqualTo("USER");
        assertThat(inserted.getGranteeExternalUserId()).isEqualTo("bob");
        assertThat(inserted.getApplicationId()).isEqualTo(APPLICATION_ID);
        assertThat(inserted.getExpiresTime()).isNull();
    }

    @Test
    void createRejectsReportsTheCurrentSubjectDoesNotOwn() {
        loginAs("alice");
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report("bob", 2));

        assertCode(
                () -> service.create(
                        new AiReportShareCreateDTO().setReportId(REPORT_ID).setGranteeExternalUserId("carol")),
                AiErrorCodeConstants.AI_REPORT_NOT_FOUND);
    }

    @Test
    void createTreatsUnknownReportTheSameAsForeignReport() {
        loginAs("alice");
        when(reportMapper.selectById(REPORT_ID)).thenReturn(null);

        assertCode(
                () -> service.create(
                        new AiReportShareCreateDTO().setReportId(REPORT_ID).setGranteeExternalUserId("carol")),
                AiErrorCodeConstants.AI_REPORT_NOT_FOUND);
    }

    @Test
    void createRejectsAppSubjectAndMissingSubject() {
        when(subjectResolver.resolveCurrent())
                .thenReturn(Optional.of(new AiFileSubject(APPLICATION_ID, AiSubjectType.APP, "", TICKET_ID)));
        assertCode(() -> service.create(createDTO()), AiErrorCodeConstants.AI_ACCESS_DENIED);

        when(subjectResolver.resolveCurrent()).thenReturn(Optional.empty());
        assertCode(() -> service.create(createDTO()), AiErrorCodeConstants.AI_ACCESS_DENIED);
    }

    @Test
    void createRejectsSelfShareAndUnknownGrantee() {
        loginAs("alice");
        stubOwnedReport(1);

        assertCode(
                () -> service.create(
                        new AiReportShareCreateDTO().setReportId(REPORT_ID).setGranteeExternalUserId("alice")),
                AiErrorCodeConstants.AI_REQUEST_INVALID);

        when(subjectService.findActiveSubject(APPLICATION_ID, AiSubjectType.USER, "nobody"))
                .thenReturn(Optional.empty());
        assertCode(
                () -> service.create(
                        new AiReportShareCreateDTO().setReportId(REPORT_ID).setGranteeExternalUserId("nobody")),
                AiErrorCodeConstants.AI_REQUEST_INVALID);
    }

    @Test
    void createRejectsDuplicateActiveShareForSameReportAndGrantee() {
        loginAs("alice");
        stubOwnedReport(2);
        stubActiveGrantee("bob", "Bob");
        when(versionMapper.selectByVersionNo(REPORT_ID, 2)).thenReturn(new AiReportVersionDO());
        when(shareMapper.selectActiveByReportAndGrantee(APPLICATION_ID, REPORT_ID, "USER", "bob"))
                .thenReturn(new AiReportShareDO());

        assertCode(
                () -> service.create(
                        new AiReportShareCreateDTO().setReportId(REPORT_ID).setGranteeExternalUserId("bob")),
                AiErrorCodeConstants.AI_REPORT_SHARE_DUPLICATE);
    }

    @Test
    void createRequiresThePinnedVersionToExist() {
        loginAs("alice");
        stubOwnedReport(3);
        stubActiveGrantee("bob", "Bob");
        when(versionMapper.selectByVersionNo(REPORT_ID, 2)).thenReturn(null);

        assertCode(
                () -> service.create(new AiReportShareCreateDTO()
                        .setReportId(REPORT_ID)
                        .setVersionNo(2)
                        .setGranteeExternalUserId("bob")),
                AiErrorCodeConstants.AI_REPORT_VERSION_NOT_FOUND);
    }

    @Test
    void createRejectsExpiresTimeThatIsNotInTheFuture() {
        loginAs("alice");
        stubOwnedReport(1);
        stubActiveGrantee("bob", "Bob");
        // 边界：恰好等于当前秒也不允许（必须在未来）
        when(versionMapper.selectByVersionNo(eq(REPORT_ID), anyInt())).thenReturn(new AiReportVersionDO());

        assertCode(
                () -> service.create(new AiReportShareCreateDTO()
                        .setReportId(REPORT_ID)
                        .setGranteeExternalUserId("bob")
                        .setExpiresTime(LocalDateTime.now())),
                AiErrorCodeConstants.AI_REQUEST_INVALID);
    }

    @Test
    void createTruncatesExpiresTimeToSeconds() {
        loginAs("alice");
        stubOwnedReport(1);
        stubActiveGrantee("bob", "Bob");
        when(versionMapper.selectByVersionNo(REPORT_ID, 1)).thenReturn(new AiReportVersionDO());
        when(shareMapper.insert(any(AiReportShareDO.class))).thenReturn(1);

        LocalDateTime expires = LocalDateTime.now().plusDays(1).withNano(123_000_000);
        service.create(new AiReportShareCreateDTO()
                .setReportId(REPORT_ID)
                .setGranteeExternalUserId("bob")
                .setExpiresTime(expires));

        ArgumentCaptor<AiReportShareDO> captor = ArgumentCaptor.forClass(AiReportShareDO.class);
        verify(shareMapper).insert(captor.capture());
        assertThat(captor.getValue().getExpiresTime().getNano()).isZero();
        assertThat(captor.getValue().getExpiresTime())
                .isEqualTo(expires.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void createMapsUniqueKeyCollisionToStateConflictInsteadOfMaskingIt() {
        loginAs("alice");
        stubOwnedReport(1);
        stubActiveGrantee("bob", "Bob");
        when(versionMapper.selectByVersionNo(REPORT_ID, 1)).thenReturn(new AiReportVersionDO());
        when(shareMapper.insert(any(AiReportShareDO.class)))
                .thenThrow(new DuplicateKeyException("uk_ai_report_share_token"));

        assertCode(
                () -> service.create(
                        new AiReportShareCreateDTO().setReportId(REPORT_ID).setGranteeExternalUserId("bob")),
                AiErrorCodeConstants.AI_STATE_CONFLICT);
    }

    @Test
    void createRequiresReportIdAndGrantee() {
        loginAs("alice");
        assertCode(() -> service.create(null), AiErrorCodeConstants.AI_REQUEST_INVALID);
        assertCode(
                () -> service.create(new AiReportShareCreateDTO().setReportId(REPORT_ID)),
                AiErrorCodeConstants.AI_REQUEST_INVALID);
    }

    // ========== revoke ==========

    @Test
    void revokeRevokesActiveShareThroughCas() {
        loginAs("alice");
        AiReportShareDO share = share();
        when(shareMapper.selectById(501L)).thenReturn(share);
        when(shareMapper.revokeIfActive(501L, "USER", "alice", 3)).thenReturn(1);

        assertThatCode(() -> service.revoke(501L, 3)).doesNotThrowAnyException();
        verify(shareMapper).revokeIfActive(501L, "USER", "alice", 3);
    }

    @Test
    void revokeIsIdempotentWhenShareIsAlreadyRevoked() {
        loginAs("alice");
        AiReportShareDO share = share();
        AiReportShareDO fresh = share();
        fresh.setStatus(AiReportShareDO.STATUS_REVOKED);
        when(shareMapper.selectById(501L)).thenReturn(share, fresh);
        when(shareMapper.revokeIfActive(501L, "USER", "alice", 3)).thenReturn(0);

        assertThatCode(() -> service.revoke(501L, 3)).doesNotThrowAnyException();
    }

    @Test
    void revokeRejectsStaleVersionWhileShareIsStillActive() {
        loginAs("alice");
        AiReportShareDO fresh = share();
        when(shareMapper.selectById(501L)).thenReturn(share(), fresh);
        when(shareMapper.revokeIfActive(501L, "USER", "alice", 3)).thenReturn(0);

        assertCode(() -> service.revoke(501L, 3), AiErrorCodeConstants.AI_STATE_CONFLICT);
    }

    @Test
    void revokeRejectsExpiredShareBecauseTheOperationDidNotApply() {
        loginAs("alice");
        AiReportShareDO fresh = share();
        fresh.setStatus(AiReportShareDO.STATUS_EXPIRED);
        when(shareMapper.selectById(501L)).thenReturn(share(), fresh);
        when(shareMapper.revokeIfActive(501L, "USER", "alice", 3)).thenReturn(0);

        assertCode(() -> service.revoke(501L, 3), AiErrorCodeConstants.AI_STATE_CONFLICT);
    }

    @Test
    void revokeHidesSharesTheCurrentSubjectDoesNotOwn() {
        loginAs("bob");
        when(shareMapper.selectById(501L)).thenReturn(share());

        // 接收者或陌生人探测 shareId：越权与不存在同语义
        assertCode(() -> service.revoke(501L, 3), AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);
        // 未知的分享编号同语义
        when(shareMapper.selectById(404L)).thenReturn(null);
        assertCode(() -> service.revoke(404L, 3), AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);
    }

    @Test
    void revokeRequiresTheOptimisticLockVersion() {
        loginAs("alice");
        assertCode(() -> service.revoke(501L, null), AiErrorCodeConstants.AI_REQUEST_INVALID);
        assertCode(() -> service.revoke(501L, -1), AiErrorCodeConstants.AI_REQUEST_INVALID);
    }

    // ========== grantor views ==========

    @Test
    void sharePageIsScopedToTheCurrentGrantor() {
        loginAs("alice");
        PageParam pageParam = new PageParam();
        when(shareMapper.selectGrantorPage(pageParam, APPLICATION_ID, "USER", "alice"))
                .thenReturn(new PageResult<>(List.of(share()), 1L));

        PageResult<AiReportShareDO> page = service.getSharePage(pageParam);

        assertThat(page.getTotal()).isEqualTo(1L);
        verify(shareMapper).selectGrantorPage(pageParam, APPLICATION_ID, "USER", "alice");
    }

    @Test
    void accessRecordsAreGrantorOnly() {
        loginAs("alice");
        when(shareMapper.selectById(501L)).thenReturn(share());
        when(accessMapper.selectByShare(501L)).thenReturn(List.of());

        assertThat(service.getAccessRecords(501L)).isEmpty();

        // 他人分享的审计不可见（越权与不存在同语义）
        when(shareMapper.selectById(502L)).thenReturn(null);
        assertCode(() -> service.getAccessRecords(502L), AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);
    }

    // ========== fixtures ==========

    private void loginAs(String externalUserId) {
        when(subjectResolver.resolveCurrent())
                .thenReturn(
                        Optional.of(new AiFileSubject(APPLICATION_ID, AiSubjectType.USER, externalUserId, TICKET_ID)));
    }

    private AiReportShareCreateDTO createDTO() {
        return new AiReportShareCreateDTO().setReportId(REPORT_ID).setGranteeExternalUserId("bob");
    }

    /** 报表归属当前主体（alice），最新版本号可指定；接收者按需另行登记。 */
    private void stubOwnedReport(int latestVersionNo) {
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report("alice", latestVersionNo));
    }

    private void stubActiveGrantee(String externalUserId, String displayName) {
        AiSubjectDO grantee = new AiSubjectDO();
        grantee.setDisplayName(displayName);
        when(subjectService.findActiveSubject(APPLICATION_ID, AiSubjectType.USER, externalUserId))
                .thenReturn(Optional.of(grantee));
    }

    private AiReportDO report(String owner, int latestVersionNo) {
        return new AiReportDO()
                .setId(REPORT_ID)
                .setApplicationId(APPLICATION_ID)
                .setSubjectType("USER")
                .setExternalUserId(owner)
                .setLatestVersionNo(latestVersionNo);
    }

    private AiReportShareDO share() {
        return new AiReportShareDO()
                .setId(501L)
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

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }
}
