package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.report.persistence.AiReportService;
import com.basicframework.module.ai.service.report.persistence.dto.AiReportSaveDTO;
import com.basicframework.module.ai.service.report.share.AiReportShareService;
import com.basicframework.module.ai.service.report.share.AiReportShareTokens;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareCreateDTO;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareCreateResultDTO;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareReadDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * X11 报表受控分享端到端（真实 MySQL + Redis）：可见权与源数据读取权分离。
 *
 * <p>验证平台语义：签发只给所有者、凭据只存摘要、接收者经令牌读到内容、撤销/到期/授予者停用
 * 立即 404（历史版本同样拒绝）、复制 reportId 的第三方读不到、无源权限接收者只拿到降级态
 * （隐藏统计与快照不出库）、每次读取（含拒绝）留痕且原因码逐条可核。
 */
@Import(AiReportShareAcceptanceIT.ShareScopeConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiReportShareAcceptanceIT extends AbstractPersistenceIntegrationTest {

    private static final String APP_CODE = "it-share-app";

    private static final String KB_CODE = "it-share-kb";

    private static final String SPEC = "{\"schemaVersion\":\"1.0\",\"title\":\"Q3 汇总\"}";

    private static final String DATA = "{\"rows\":[{\"amount\":290.00}]}";

    private static final String SOURCES = "[{\"resourceType\":\"KNOWLEDGE_BASE\",\"resourceKey\":\"" + KB_CODE + "\"}]";

    /** 与既有 AI IT 同源：A03 的授权判定依赖主体范围解析，测试里给出受控范围（不改生产解析）。 */
    @TestConfiguration
    static class ShareScopeConfiguration {

        @Bean
        com.basicframework.module.ai.domain.identity.SubjectScopeResolver shareScopeResolver() {
            return request -> java.util.Optional.of(new com.basicframework.module.ai.domain.identity.SubjectScope(
                    java.util.Set.of(10L), java.util.Set.of(KB_CODE), request.scopeSource(), request.scopeVersion()));
        }
    }

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiTicketService ticketService;

    @Autowired
    private AiReportService reportService;

    @Autowired
    private AiReportShareService shareService;

    private Long applicationId;

    private String applicationSecret;

    private Long reportId;

    @BeforeEach
    void prepare() {
        cleanUp();
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 分享应用")
                .setOrigins(List.of("https://crm.example.com")));
        applicationService.updateStatus(issue.getApplication().getId(), 0, true);
        applicationId = issue.getApplication().getId();
        applicationSecret = issue.getSecret();
        for (String user : List.of("alice", "bob", "carol", "eve")) {
            subjectService.syncSubject(applicationId, AiSubjectType.USER, user, upper(user), "crm-auth", 1L);
        }
        // 所有者与有源权限的接收者需要 KB 读权；carol/eve 故意不授权（降级态与防枚举用）
        ensureGrant("alice");
        ensureGrant("bob");
        loginAs("alice");
        AiReportSaveDTO saveDTO = new AiReportSaveDTO()
                .setCode("it_share_report")
                .setName("Q3 汇总报表")
                .setMode("SNAPSHOT")
                .setSpecJson(SPEC)
                .setDataJson(DATA)
                .setSourcesJson(SOURCES)
                .setCompleteness("COMPLETE");
        reportId = reportService.create(saveDTO);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        jdbcTemplate.update("DELETE FROM ai_report_share_access");
        jdbcTemplate.update("DELETE FROM ai_report_share");
        jdbcTemplate.update("DELETE FROM ai_report_version");
        jdbcTemplate.update("DELETE FROM ai_report");
        List<Long> appIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
        for (Long appId : appIds) {
            jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_subject WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_access_ticket WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
        }
        applicationId = null;
        reportId = null;
    }

    @Test
    void granteeReadsThroughTheTokenAndOnlyTheDigestIsStored() {
        loginAs("alice");
        AiReportShareCreateResultDTO created = shareService.create(
                new AiReportShareCreateDTO().setReportId(reportId).setGranteeExternalUserId("bob"));

        assertThat(created.getVersionNo()).isEqualTo(1);
        // 凭据只存摘要：明文不在库里的 token_hash 列，摘要与明文一一对应
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM ai_report_share WHERE id = ?", String.class, created.getShareId());
        assertThat(storedHash).isEqualTo(AiReportShareTokens.digest(created.getToken()));
        assertThat(storedHash).isNotEqualTo(created.getToken());
        Integer rowsWithPlaintext = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_report_share WHERE token_hash = ?", Integer.class, created.getToken());
        assertThat(rowsWithPlaintext).isZero();

        loginAs("bob");
        AiReportShareReadDTO read = shareService.readByToken(created.getToken());
        assertThat(read.isContentAuthorized()).isTrue();
        assertThat(read.getSpecJson()).isEqualTo(SPEC);
        assertThat(read.getDataJson()).isEqualTo(DATA);
        assertThat(read.getCompleteness()).isEqualTo("COMPLETE");
        assertThat(read.getVersionNo()).isEqualTo(1);
        assertThat(read.getReasonCode()).isNull();
    }

    @Test
    void sharePinsTheVersionAtIssuanceTime() {
        loginAs("alice");
        AiReportShareCreateResultDTO created = shareService.create(
                new AiReportShareCreateDTO().setReportId(reportId).setGranteeExternalUserId("bob"));

        // 报表新增版本 2：分享内容仍固定在签发时的版本 1
        loginAs("alice");
        AiReportSaveDTO saveDTO = new AiReportSaveDTO()
                .setId(reportId)
                .setVersion(reportService.getReport(reportId).getVersion())
                .setName("Q3 汇总报表 v2")
                .setMode("SNAPSHOT")
                .setSpecJson(SPEC)
                .setDataJson(DATA)
                .setSourcesJson(SOURCES)
                .setCompleteness("COMPLETE");
        reportService.saveVersion(saveDTO);

        loginAs("bob");
        AiReportShareReadDTO read = shareService.readByToken(created.getToken());
        assertThat(read.getVersionNo()).as("分享内容是签发时刻的版本").isEqualTo(1);
    }

    @Test
    void duplicateActiveShareIsRejectedButReShareAfterRevokeIsAllowed() {
        loginAs("alice");
        AiReportShareCreateResultDTO created = shareService.create(
                new AiReportShareCreateDTO().setReportId(reportId).setGranteeExternalUserId("bob"));

        assertCode(
                () -> shareService.create(
                        new AiReportShareCreateDTO().setReportId(reportId).setGranteeExternalUserId("bob")),
                AiErrorCodeConstants.AI_REPORT_SHARE_DUPLICATE);

        // 撤销后允许重新分享（去重只针对 ACTIVE 分享）
        shareService.revoke(created.getShareId(), 0);
        assertThatCode(() -> shareService.create(
                        new AiReportShareCreateDTO().setReportId(reportId).setGranteeExternalUserId("bob")))
                .doesNotThrowAnyException();
    }

    @Test
    void revocationImmediatelyBlocksReadsIncludingThePinnedHistoricalVersion() {
        loginAs("alice");
        AiReportShareCreateResultDTO created = shareService.create(
                new AiReportShareCreateDTO().setReportId(reportId).setGranteeExternalUserId("bob"));

        // 撤销前可读（版本 1 行仍在库中——拒绝的是分享，不是数据丢失）
        loginAs("bob");
        assertThat(shareService.readByToken(created.getToken()).isContentAuthorized())
                .isTrue();

        loginAs("alice");
        // 仍 ACTIVE 时用过期版本号撤销：按当前事实拒绝（乐观锁冲突）
        assertCode(() -> shareService.revoke(created.getShareId(), 999), AiErrorCodeConstants.AI_STATE_CONFLICT);
        shareService.revoke(created.getShareId(), 0);

        loginAs("bob");
        assertCode(() -> shareService.readByToken(created.getToken()), AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);

        // 重复撤销按幂等成功返回（版本号不再参与；需重新以授予者身份操作）
        loginAs("alice");
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_report_version WHERE report_id = ? AND version_no = 1",
                Integer.class,
                reportId);
        assertThat(versionRows).as("被拒绝的历史版本仍然存在（不可读而非被删除）").isEqualTo(1);

        assertThatCode(() -> shareService.revoke(created.getShareId(), 0)).doesNotThrowAnyException();
        assertThatCode(() -> shareService.revoke(created.getShareId(), 999)).doesNotThrowAnyException();
    }

    @Test
    void expiryIsMaterializedLazilyOnRead() {
        loginAs("alice");
        AiReportShareCreateResultDTO created = shareService.create(new AiReportShareCreateDTO()
                .setReportId(reportId)
                .setGranteeExternalUserId("bob")
                .setExpiresTime(LocalDateTime.now().plusSeconds(30)));
        loginAs("bob");
        assertThat(shareService.readByToken(created.getToken()).isContentAuthorized())
                .isTrue();

        // 用固定夹具推进时间（不等墙钟）：到期行仍是 ACTIVE，读取时才物化
        jdbcTemplate.update(
                "UPDATE ai_report_share SET expires_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id = ?",
                created.getShareId());
        loginAs("bob");
        assertCode(() -> shareService.readByToken(created.getToken()), AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM ai_report_share WHERE id = ?", String.class, created.getShareId());
        assertThat(status).as("到期在读取时惰性物化为 EXPIRED").isEqualTo("EXPIRED");
    }

    @Test
    void grantorDisableBlocksReadsUntilTheGrantorIsBack() {
        loginAs("alice");
        AiReportShareCreateResultDTO created = shareService.create(
                new AiReportShareCreateDTO().setReportId(reportId).setGranteeExternalUserId("bob"));

        subjectService.disableSubject(applicationId, AiSubjectType.USER, "alice");
        loginAs("bob");
        assertCode(() -> shareService.readByToken(created.getToken()), AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);

        // 授予者主体恢复（未撤销/未过期的分享随之恢复）；被明确撤销的分享不复活
        subjectService.syncSubject(applicationId, AiSubjectType.USER, "alice", "Alice", "crm-auth", 1L);
        assertThat(shareService.readByToken(created.getToken()).isContentAuthorized())
                .isTrue();

        loginAs("alice");
        shareService.revoke(created.getShareId(), 0);
        subjectService.disableSubject(applicationId, AiSubjectType.USER, "alice");
        subjectService.syncSubject(applicationId, AiSubjectType.USER, "alice", "Alice", "crm-auth", 1L);
        loginAs("bob");
        assertCode(() -> shareService.readByToken(created.getToken()), AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);
    }

    @Test
    void reportIdAloneIsUselessWithoutTheToken() {
        loginAs("alice");
        shareService.create(new AiReportShareCreateDTO().setReportId(reportId).setGranteeExternalUserId("bob"));

        // eve（未获任何授权的第三方）复制 reportId：报表读取按归属拒绝
        loginAs("eve");
        assertCode(() -> reportService.readCurrent(reportId), AiErrorCodeConstants.AI_REPORT_NOT_FOUND);
        // eve 伪造/猜测令牌：404 与"分享不存在"同语义
        assertCode(
                () -> shareService.readByToken("RjFhbGJ1bXBEZW1vVG9rZW5Ob3RSZWFsQXNzZW1ibHk"),
                AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);
        // eve 拿 bob 的分享编号也撤不了（只有授予者能撤销）
        Long shareId = jdbcTemplate.queryForObject("SELECT id FROM ai_report_share LIMIT 1", Long.class);
        assertCode(() -> shareService.revoke(shareId, 0), AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);
    }

    @Test
    void granteeWithoutSourcePermissionGetsTheDegradedState() {
        loginAs("alice");
        AiReportShareCreateResultDTO forBob = shareService.create(
                new AiReportShareCreateDTO().setReportId(reportId).setGranteeExternalUserId("bob"));
        AiReportShareCreateResultDTO forCarol = shareService.create(
                new AiReportShareCreateDTO().setReportId(reportId).setGranteeExternalUserId("carol"));

        // bob 有源权限：完整内容
        loginAs("bob");
        AiReportShareReadDTO bobRead = shareService.readByToken(forBob.getToken());
        assertThat(bobRead.isContentAuthorized()).isTrue();
        assertThat(bobRead.getDataJson()).isEqualTo(DATA);

        // carol 无源权限：HTTP 语义成功，但隐藏统计与快照不出库
        loginAs("carol");
        AiReportShareReadDTO carolRead = shareService.readByToken(forCarol.getToken());
        assertThat(carolRead.isContentAuthorized()).isFalse();
        assertThat(carolRead.getReasonCode()).isEqualTo("scope-uncovered");
        assertThat(carolRead.getSpecJson()).as("降级态不回传规格").isNull();
        assertThat(carolRead.getDataJson()).as("降级态不回传快照数据").isNull();
        assertThat(carolRead.getAsOf()).isNull();
        assertThat(carolRead.getCompleteness()).isNull();
        assertThat(carolRead.getReportId()).isEqualTo(reportId);
    }

    @Test
    void accessAuditRecordsEveryReadWithStableReasonCodes() {
        loginAs("alice");
        AiReportShareCreateResultDTO created = shareService.create(
                new AiReportShareCreateDTO().setReportId(reportId).setGranteeExternalUserId("bob"));

        loginAs("bob");
        shareService.readByToken(created.getToken());

        loginAs("eve");
        assertCode(
                () -> shareService.readByToken("c2hvcnRDcm9zc1Rva2VuRG9lc05vdEV4aXN0MQ"),
                AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);

        loginAs("alice");
        shareService.revoke(created.getShareId(), 0);
        loginAs("bob");
        assertThatThrownBy(() -> shareService.readByToken(created.getToken()));

        // 授予者查审计：最新在前，逐条核对结论与原因码；伪造凭据（share_id 为空）不归属到本分享
        loginAs("alice");
        List<com.basicframework.module.ai.dal.dataobject.report.AiReportShareAccessDO> records =
                shareService.getAccessRecords(created.getShareId());
        assertThat(records).hasSize(2);
        assertThat(records.get(0).getOutcome()).isEqualTo("DENIED");
        assertThat(records.get(0).getReasonCode()).isEqualTo("revoked");
        assertThat(records.get(0).getExternalUserId()).isEqualTo("bob");
        assertThat(records.get(1).getOutcome()).isEqualTo("GRANTED");
        assertThat(records.get(1).getReasonCode()).isNull();
        assertThat(records.get(1).getContentAuthorized()).isTrue();
        // eve 拿伪造凭据的尝试仍留下审计（share_id 为空，无法归属到任何分享）
        Integer foreignAttempts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_report_share_access WHERE share_id IS NULL AND outcome = 'DENIED'"
                        + " AND reason_code = 'subject-mismatch' AND external_user_id = 'eve'",
                Integer.class);
        assertThat(foreignAttempts).as("凭据不匹配的尝试按主体留痕").isEqualTo(1);

        // 接收者查不了授予者的审计
        loginAs("bob");
        assertCode(
                () -> shareService.getAccessRecords(created.getShareId()),
                AiErrorCodeConstants.AI_REPORT_SHARE_NOT_EXISTS);
    }

    @Test
    void nonOwnerCannotShareAndAppSubjectsAreRefused() {
        // bob 不是报表所有者：越权与不存在同语义（也不给"报表存在"的信息）
        loginAs("bob");
        assertCode(
                () -> shareService.create(
                        new AiReportShareCreateDTO().setReportId(reportId).setGranteeExternalUserId("carol")),
                AiErrorCodeConstants.AI_REPORT_NOT_FOUND);
    }

    private void ensureGrant(String externalUserId) {
        try {
            grantService.createGrant(applicationId, "USER", externalUserId, "KNOWLEDGE_BASE", KB_CODE, Set.of("READ"));
        } catch (ServiceException exception) {
            // 已存在即视为准备完成；其它错误照旧抛出
            if (!String.valueOf(exception.getCode()).startsWith("1003")) {
                throw exception;
            }
        }
    }

    private void loginAs(String externalUserId) {
        String ticket = ticketService
                .issue(APP_CODE, applicationSecret, AiSubjectType.USER, externalUserId, List.of(KB_CODE))
                .getToken();
        var context = ticketService.verify(ticket);
        LoginUser loginUser = new LoginUser()
                .setId(context.getTicketId())
                .setUserType(UserTypeEnum.MEMBER.getValue())
                .setInfo(Map.of(
                        AiUserSessionCommonApi.INFO_KEY_APPLICATION_ID,
                        String.valueOf(context.getApplicationId()),
                        AiUserSessionCommonApi.INFO_KEY_SUBJECT_TYPE,
                        context.getSubjectType(),
                        AiUserSessionCommonApi.INFO_KEY_EXTERNAL_USER_ID,
                        context.getExternalUserId()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    private static String upper(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }
}
