package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.report.AiReportDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportVersionDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.report.persistence.AiReportScopeRefs;
import com.basicframework.module.ai.service.report.persistence.AiReportService;
import com.basicframework.module.ai.service.report.persistence.dto.AiReportSaveDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
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
 * R04 报表持久化与归属（真实 MySQL + 真实 A01–A07 链路）。
 *
 * <p>本套件不 mock 授权：应用/主体/授权目录/票据都用真实服务与数据库，因此
 * "跨用户拒绝""失权后拒绝显示"都是端到端事实，而不是桩返回值。
 */
@Import(AiReportPersistenceIT.ResolverConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiReportPersistenceIT extends AbstractPersistenceIntegrationTest {

    private static final String APP_CODE = "it-r04-app";

    private static final String ALICE = "alice";

    private static final String BOB = "bob";

    private static final String KNOWLEDGE_BASE = "kb-1";

    /** 报表标识（应用内唯一，且清理后不残留）。 */
    private static final String REPORT_CODE = "it_r04_sales";

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver reportScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L), Set.of(KNOWLEDGE_BASE), request.scopeSource(), request.scopeVersion()));
        }
    }

    @Autowired
    private AiReportService reportService;

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiTicketService ticketService;

    private Long applicationId;

    private String appSecret;

    private Long grantId;

    private String aliceRunKey;

    private String bobRunKey;

    /** 前置：应用（启用）+ 主体 + 授权 + 各自的一次运行（运行行与其 FK 链直接落库）。 */
    private void prepare() {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("R04 报表应用")
                .setOrigins(List.of("https://r04.example.com")));
        applicationId = issue.getApplication().getId();
        appSecret = issue.getSecret();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, ALICE, ALICE, "crm-auth", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, BOB, BOB, "crm-auth", 1L);
        grantId = grantService.createGrant(
                applicationId, "USER", ALICE, "KNOWLEDGE_BASE", KNOWLEDGE_BASE, Set.of("READ"));
        // bob 同样持有依赖资源授权：跨用户用例拒绝的是"他人的运行"，不是"没有资源授权"
        grantService.createGrant(applicationId, "USER", BOB, "KNOWLEDGE_BASE", KNOWLEDGE_BASE, Set.of("READ"));

        jdbcTemplate.update(
                "INSERT INTO ai_service (app_id, code, name, status, model_endpoint_id, prompt_template,"
                        + " input_schema, required_capabilities, run_subject_type, creator, updater)"
                        + " VALUES (?, 'it-r04-service', 'R04 服务', 'READY', 1, 'prompt', '{}', 'TEXT', 'USER', 'it', 'it')",
                applicationId);
        Long serviceId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_service WHERE code = 'it-r04-service' AND app_id = ?", Long.class, applicationId);
        jdbcTemplate.update(
                "INSERT INTO ai_service_release (service_id, model_endpoint_id, endpoint_config_revision,"
                        + " prompt_template, input_schema, required_capabilities, content_hash, status, release_version,"
                        + " creator, updater)"
                        + " VALUES (?, 1, 1, 'prompt', '{}', 'TEXT', ?, 'ACTIVE', 1, 'it', 'it')",
                serviceId,
                "a".repeat(64));
        Long releaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_service_release WHERE service_id = ?", Long.class, serviceId);
        aliceRunKey = "run_r04_" + System.nanoTime();
        bobRunKey = "run_r04_bob_" + System.nanoTime();
        for (String subject : List.of(ALICE, BOB)) {
            jdbcTemplate.update(
                    "INSERT INTO ai_conversation (application_id, subject_type, external_user_id, conversation_key,"
                            + " title, business_context, creator, updater)"
                            + " VALUES (?, 'USER', ?, ?, 'R04 会话', '{}', 'it', 'it')",
                    applicationId,
                    subject,
                    "conv_r04_" + subject + "_" + System.nanoTime());
            Long conversationId = jdbcTemplate.queryForObject(
                    "SELECT id FROM ai_conversation WHERE external_user_id = ? ORDER BY id DESC LIMIT 1",
                    Long.class,
                    subject);
            jdbcTemplate.update(
                    "INSERT INTO ai_run (run_key, application_id, subject_type, external_user_id, conversation_id,"
                            + " service_id, release_id, model_endpoint_id, endpoint_config_revision, content_hash,"
                            + " input_digest, status, step_count, creator, updater)"
                            + " VALUES (?, ?, 'USER', ?, ?, ?, ?, 1, 1, ?, ?, 'RUNNING', 0, 'it', 'it')",
                    ALICE.equals(subject) ? aliceRunKey : bobRunKey,
                    applicationId,
                    subject,
                    conversationId,
                    serviceId,
                    releaseId,
                    "b".repeat(64),
                    "c".repeat(64));
        }
    }

    /** 以某个主体登录（签发真实票据，会话信息与 A05 写入的一致）。 */
    private void loginAs(String subject) {
        var ticket = ticketService.issue(APP_CODE, appSecret, AiSubjectType.USER, subject, List.of(KNOWLEDGE_BASE));
        LoginUser loginUser = new LoginUser()
                .setId(ticketService.verify(ticket.getToken()).getTicketId())
                .setUserType(UserTypeEnum.MEMBER.getValue())
                .setInfo(Map.of(
                        AiUserSessionCommonApi.INFO_KEY_APPLICATION_ID,
                        String.valueOf(applicationId),
                        AiUserSessionCommonApi.INFO_KEY_SUBJECT_TYPE,
                        "USER",
                        AiUserSessionCommonApi.INFO_KEY_EXTERNAL_USER_ID,
                        subject,
                        AiUserSessionCommonApi.INFO_KEY_SCOPE_FINGERPRINT,
                        ticket.getScopeFingerprint()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    private AiReportSaveDTO snapshotDTO(String runKey) {
        return new AiReportSaveDTO()
                .setCode(REPORT_CODE)
                .setName("销售总览")
                .setDescription("R04 集成用例")
                .setMode(AiReportDO.MODE_SNAPSHOT)
                .setSchemaVersion("1.0")
                .setSpecJson("{\"schemaVersion\":\"1.0\"}")
                .setDataJson("{\"blocks\":[{\"blockId\":\"b1\"}]}")
                .setSourcesJson("[{\"resourceType\":\"KNOWLEDGE_BASE\",\"resourceKey\":\"" + KNOWLEDGE_BASE
                        + "\",\"resultRef\":\"res_1\"}]")
                .setCompleteness("COMPLETE")
                .setCreatedByRun(runKey);
    }

    private static void assertCode(Throwable throwable, Integer expectedCode) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expectedCode);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        if (applicationId == null) {
            return;
        }
        jdbcTemplate.update(
                "DELETE FROM ai_report_version WHERE report_id IN (SELECT id FROM ai_report WHERE application_id = ?)",
                applicationId);
        jdbcTemplate.update("DELETE FROM ai_report WHERE application_id = ?", applicationId);
        jdbcTemplate.update("DELETE FROM ai_run WHERE application_id = ?", applicationId);
        jdbcTemplate.update("DELETE FROM ai_conversation WHERE application_id = ?", applicationId);
        jdbcTemplate.update(
                "DELETE FROM ai_service_release WHERE service_id IN (SELECT id FROM ai_service WHERE app_id = ?)",
                applicationId);
        jdbcTemplate.update("DELETE FROM ai_service WHERE app_id = ?", applicationId);
        jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE application_id = ?", applicationId);
        jdbcTemplate.update("DELETE FROM ai_subject WHERE application_id = ?", applicationId);
        jdbcTemplate.update("DELETE FROM ai_access_ticket WHERE application_id = ?", applicationId);
        jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", applicationId);
        jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", applicationId);
        applicationId = null;
        grantId = null;
    }

    @Test
    void snapshotVersionKeepsOwnershipScopeRefsAndIsReadableOnlyByOwner() {
        prepare();
        loginAs(ALICE);
        Long reportId = reportService.create(snapshotDTO(aliceRunKey));

        // 归属与元信息落库（请求体不能自报归属）
        Map<String, Object> report = jdbcTemplate.queryForMap(
                "SELECT application_id, subject_type, external_user_id, mode, latest_version_no,"
                        + " published_version_no, version FROM ai_report WHERE id = ?",
                reportId);
        assertThat(report.get("application_id")).isEqualTo(applicationId);
        assertThat(report.get("subject_type")).isEqualTo("USER");
        assertThat(report.get("external_user_id")).isEqualTo(ALICE);
        assertThat(report.get("mode")).isEqualTo("SNAPSHOT");
        assertThat(report.get("latest_version_no")).isEqualTo(1);
        assertThat(report.get("published_version_no")).isEqualTo(1);

        // 快照元信息完整：数据、截至时间、完整性、来源运行、逐项范围指纹
        Map<String, Object> version = jdbcTemplate.queryForMap(
                "SELECT data_json, as_of, completeness, created_by_run, scope_refs_json, scope_fingerprint"
                        + " FROM ai_report_version WHERE report_id = ?",
                reportId);
        assertThat(version.get("data_json")).isNotNull();
        assertThat(version.get("as_of")).isNotNull();
        assertThat(version.get("completeness")).isEqualTo("COMPLETE");
        assertThat(version.get("created_by_run")).isEqualTo(aliceRunKey);
        assertThat(String.valueOf(version.get("scope_fingerprint"))).hasSize(64);
        assertThat(String.valueOf(version.get("scope_refs_json")))
                .contains("KNOWLEDGE_BASE")
                .contains(KNOWLEDGE_BASE);
        assertThat(AiReportScopeRefs.fingerprint(
                        AiReportScopeRefs.fromJson(String.valueOf(version.get("scope_refs_json")))))
                .isEqualTo(String.valueOf(version.get("scope_fingerprint")));

        // 本人可读，数据按保存时的样子返回
        AiReportVersionDO current = reportService.readCurrent(reportId);
        assertThat(current.getVersionNo()).isEqualTo(1);
        assertThat(current.getDataJson()).contains("b1");
        assertThat(reportService.getReport(reportId).getCode()).isEqualTo(REPORT_CODE);
        assertThat(reportService.listVersions(reportId)).hasSize(1);

        // 他主体：越权与不存在同语义，且列表里看不到
        loginAs(BOB);
        assertCode(
                assertThatThrownBy(() -> reportService.readCurrent(reportId)).actual(),
                AiErrorCodeConstants.AI_REPORT_NOT_FOUND.getCode());
        assertCode(
                assertThatThrownBy(() -> reportService.getVersion(reportId, 1)).actual(),
                AiErrorCodeConstants.AI_REPORT_NOT_FOUND.getCode());
        assertThat(reportService.getReportPage(new PageParam(), null).getList()).isEmpty();
    }

    @Test
    void newVersionAppendsAndHistoryStaysImmutable() {
        prepare();
        loginAs(ALICE);
        Long reportId = reportService.create(snapshotDTO(aliceRunKey));

        AiReportSaveDTO next = snapshotDTO(aliceRunKey)
                .setId(reportId)
                .setVersion(0)
                .setSpecJson("{\"schemaVersion\":\"1.0\",\"title\":\"第二版\"}")
                .setDataJson("{\"blocks\":[{\"blockId\":\"b2\"}]}");
        assertThat(reportService.saveVersion(next)).isEqualTo(reportId);

        Map<String, Object> report = jdbcTemplate.queryForMap(
                "SELECT latest_version_no, published_version_no, version FROM ai_report WHERE id = ?", reportId);
        assertThat(report.get("latest_version_no")).isEqualTo(2);
        assertThat(report.get("published_version_no")).isEqualTo(2);
        assertThat(report.get("version")).isEqualTo(1);

        // 历史版本内容不变（不可变）：第 1 版仍是第一版的数据
        AiReportVersionDO first = reportService.getVersion(reportId, 1);
        assertThat(first.getDataJson()).contains("b1");
        assertThat(first.getSpecJson()).isEqualTo("{\"schemaVersion\":\"1.0\"}");
        assertThat(reportService.listVersions(reportId)).hasSize(2);

        // 并发修改：用过期乐观锁保存必须 409，且不产生新版本
        AiReportSaveDTO stale = snapshotDTO(aliceRunKey).setId(reportId).setVersion(0);
        assertCode(
                assertThatThrownBy(() -> reportService.saveVersion(stale)).actual(),
                AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_report_version WHERE report_id = ?", Integer.class, reportId))
                .isEqualTo(2);

        // 数据库层兜底：同报表同版本号重复插入被唯一索引拒绝（历史版本不可覆盖）
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO ai_report_version (report_id, version_no, mode, spec_json, scope_refs_json,"
                                + " scope_fingerprint, version, creator, updater) VALUES (?, 1, 'SNAPSHOT', '{}', '[]', ?,"
                                + " 0, 'it', 'it')",
                        reportId,
                        AiReportScopeRefs.EMPTY_FINGERPRINT))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void refreshableModeStoresNoSnapshotDataAndRejectsModeChange() {
        prepare();
        loginAs(ALICE);
        Long reportId = reportService.create(
                snapshotDTO(aliceRunKey).setMode(AiReportDO.MODE_REFRESHABLE).setDataJson(null));

        Map<String, Object> version = jdbcTemplate.queryForMap(
                "SELECT data_json, as_of FROM ai_report_version WHERE report_id = ?", reportId);
        assertThat(version.get("data_json")).isNull();
        assertThat(version.get("as_of")).isNull();

        // 保存新版本不能把可刷新偷偷改成快照
        assertCode(
                assertThatThrownBy(() -> reportService.saveVersion(
                                snapshotDTO(aliceRunKey).setId(reportId).setVersion(0)))
                        .actual(),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
    }

    @Test
    void savingFromAnotherUsersRunIsRejected() {
        prepare();
        loginAs(BOB);
        assertCode(
                assertThatThrownBy(() -> reportService.create(snapshotDTO(aliceRunKey)))
                        .actual(),
                AiErrorCodeConstants.AI_REPORT_SOURCE_RUN_NOT_FOUND.getCode());
        // 不存在的运行同语义（不借错误码枚举他人运行）
        assertCode(
                assertThatThrownBy(() -> reportService.create(snapshotDTO("run_r04_missing")))
                        .actual(),
                AiErrorCodeConstants.AI_REPORT_SOURCE_RUN_NOT_FOUND.getCode());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_report WHERE application_id = ?", Integer.class, applicationId))
                .isZero();

        // 换成自己的运行可以保存（拒绝的是越权，不是"带运行就拒绝"）
        Long reportId = reportService.create(snapshotDTO(bobRunKey));
        assertThat(reportService.getReport(reportId).getExternalUserId()).isEqualTo(BOB);
    }

    @Test
    void revokingGrantMakesSavedSnapshotUnreadable() {
        prepare();
        loginAs(ALICE);
        Long reportId = reportService.create(snapshotDTO(aliceRunKey));
        assertThat(reportService.readCurrent(reportId).getVersionNo()).isEqualTo(1);

        // 失权：撤销该主体对依赖资源的授权
        grantService.revokeGrant(grantId, 0);

        // 快照与旧版本编号两个入口都拒绝显示（AT-048）
        assertCode(
                assertThatThrownBy(() -> reportService.readCurrent(reportId)).actual(),
                AiErrorCodeConstants.AI_REPORT_SCOPE_CHANGED.getCode());
        assertCode(
                assertThatThrownBy(() -> reportService.getVersion(reportId, 1)).actual(),
                AiErrorCodeConstants.AI_REPORT_SCOPE_CHANGED.getCode());
        // 归属仍然成立：元信息与版本列表可见，只是内容不再放行
        assertThat(reportService.getReport(reportId).getId()).isEqualTo(reportId);
        assertThat(reportService.listVersions(reportId)).hasSize(1);
    }
}
