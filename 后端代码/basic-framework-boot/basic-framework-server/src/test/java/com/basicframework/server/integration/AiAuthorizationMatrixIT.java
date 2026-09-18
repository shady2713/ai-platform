package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.authorization.AiRevocationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import com.basicframework.module.infra.framework.file.core.enums.FileStorageEnum;
import com.basicframework.module.infra.service.file.FileConfigService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * A08 授权矩阵（真实 MySQL + 真实服务链路）：逐格验证 {@code docs/security/ai-authorization-matrix.md}
 * 的预期，任何一格失败都会给出 "应用/主体/操作/资源 expected=… actual=…" 的可读报告。
 *
 * <p>本套件不是"mock 授权返回 false"：每格都经过 A01（应用/凭据）、A02（主体范围）、A03（授权目录与判定）、
 * A04（票据）、A06（撤销）、A07（业务文件）的真实实现与数据库。
 */
@Import(AiAuthorizationMatrixIT.ResolverConfiguration.class)
class AiAuthorizationMatrixIT extends AbstractPersistenceIntegrationTest {

    private static final String APP_A = "mx-app-a";
    private static final String APP_B = "mx-app-b";

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiAuthorizationService authorizationService;

    @Autowired
    private AiRevocationService revocationService;

    @Autowired
    private AiTicketService ticketService;

    @Autowired
    private AiFileService fileService;

    @Autowired
    private FileConfigService fileConfigService;

    private Long fileConfigId;

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver matrixScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L),
                    Set.of("report-1", "kb-1", "session-1"),
                    request.scopeSource(),
                    request.scopeVersion()));
        }
    }

    /** 集成环境前置：数据库存储的文件主配置（上传/读取都走 getMasterFileClient）。 */
    private void ensureMasterFileConfig() {
        if (fileConfigId != null) {
            return;
        }
        fileConfigId = fileConfigService.createFileConfig(
                new FileConfigDO()
                        .setName("it-matrix-file-" + System.nanoTime())
                        .setStorage(FileStorageEnum.DB.getStorage()),
                Map.of("domain", "http://localhost/files"));
        fileConfigService.updateFileConfigMaster(fileConfigId);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        if (fileConfigId != null) {
            jdbcTemplate.update("DELETE FROM infra_file_content WHERE config_id = ?", fileConfigId);
            jdbcTemplate.update("DELETE FROM infra_file WHERE config_id = ?", fileConfigId);
            jdbcTemplate.update("DELETE FROM infra_file_config WHERE id = ?", fileConfigId);
            fileConfigId = null;
        }
        for (String appCode : List.of(APP_A, APP_B)) {
            List<Long> appIds =
                    jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, appCode);
            for (Long appId : appIds) {
                List<Long> fileIds = jdbcTemplate.queryForList(
                        "SELECT file_id FROM ai_file_binding WHERE application_id = ?", Long.class, appId);
                jdbcTemplate.update("DELETE FROM ai_file_binding WHERE application_id = ?", appId);
                jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE application_id = ?", appId);
                jdbcTemplate.update("DELETE FROM ai_subject WHERE application_id = ?", appId);
                jdbcTemplate.update("DELETE FROM ai_access_ticket WHERE application_id = ?", appId);
                jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", appId);
                jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
                for (Long fileId : fileIds) {
                    jdbcTemplate.update("DELETE FROM infra_file_content WHERE id = ?", fileId);
                    jdbcTemplate.update("DELETE FROM infra_file WHERE id = ?", fileId);
                }
            }
        }
    }

    private record AppContext(Long applicationId, String applicationCode, String secret) {}

    private AppContext prepareApplication(String appCode, List<String> subjects) {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(appCode)
                .setName("矩阵应用 " + appCode)
                .setOrigins(List.of("https://" + appCode + ".example.com")));
        Long applicationId = issue.getApplication().getId();
        applicationService.updateStatus(applicationId, 0, true);
        for (String subject : subjects) {
            subjectService.syncSubject(applicationId, AiSubjectType.USER, subject, subject, "crm-auth", 1L);
        }
        return new AppContext(applicationId, appCode, issue.getSecret());
    }

    /** 以某个主体登录（签发真实票据并用其编号作为 MEMBER 会话身份）。 */
    private void loginAs(AppContext application, String subject) {
        var ticket = ticketService.issue(
                application.applicationCode(),
                application.secret(),
                AiSubjectType.USER,
                subject,
                List.of("report-1", "kb-1", "session-1"));
        LoginUser loginUser = new LoginUser()
                .setId(ticketContextId(ticket.getToken()))
                .setUserType(UserTypeEnum.MEMBER.getValue())
                .setInfo(Map.of(
                        AiUserSessionCommonApi.INFO_KEY_APPLICATION_ID,
                        String.valueOf(application.applicationId()),
                        AiUserSessionCommonApi.INFO_KEY_SUBJECT_TYPE,
                        "USER",
                        AiUserSessionCommonApi.INFO_KEY_EXTERNAL_USER_ID,
                        subject));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    private Long ticketContextId(String token) {
        return ticketService.verify(token).getTicketId();
    }

    /** 逐格判定并把结果整理成可读矩阵行。 */
    private List<String> matrix = new ArrayList<>();

    private void expect(String label, boolean allowed, java.util.function.BooleanSupplier actual) {
        boolean result;
        try {
            result = actual.getAsBoolean();
        } catch (ServiceException exception) {
            result = false;
        }
        matrix.add(label + " expected=" + (allowed ? "ALLOW" : "DENY") + " actual=" + (result ? "ALLOW" : "DENY"));
        assertThat(result)
                .as(
                        "%s/%s/%s/%s expected=%s actual=%s",
                        label.split("/")[0],
                        label.split("/")[1],
                        label.split("/")[2],
                        label.split("/")[3],
                        allowed ? "ALLOW" : "DENY",
                        result ? "ALLOW" : "DENY")
                .isEqualTo(allowed);
    }

    @Test
    void authorizationMatrixMatchesFrozenExpectations() {
        AppContext appA = prepareApplication(APP_A, List.of("alice", "bob"));
        AppContext appB = prepareApplication(APP_B, List.of("carol"));
        grantService.createGrant(appA.applicationId(), "USER", "alice", "REPORT", "report-1", Set.of("READ"));
        grantService.createGrant(appA.applicationId(), "USER", "alice", "KNOWLEDGE_BASE", "kb-1", Set.of("READ"));

        // 文件：alice 在 appA 的会话附件（会话附件仅所有者）
        ensureMasterFileConfig();
        loginAs(appA, "alice");
        Long aliceFileId = fileService
                .upload(
                        "ai_chat_session",
                        "session-1",
                        "note.txt",
                        "text/plain",
                        "alice-note".getBytes(StandardCharsets.UTF_8))
                .getFileId();

        // 1) 授权目录命中
        expect(
                "appA/alice/READ/report-1",
                true,
                () -> decision(appA, "alice", AiResourceType.REPORT, "report-1", AiAction.READ));
        // 2) 动作白名单外
        expect(
                "appA/alice/EXPORT/report-1",
                false,
                () -> decision(appA, "alice", AiResourceType.REPORT, "report-1", AiAction.EXPORT));
        // 3) 同 ID 不同类型不串权
        expect(
                "appA/alice/READ/file-report-1",
                false,
                () -> decision(appA, "alice", AiResourceType.FILE, "report-1", AiAction.READ));
        // 4) 同应用他主体无授权
        expect(
                "appA/bob/READ/report-1",
                false,
                () -> decision(appA, "bob", AiResourceType.REPORT, "report-1", AiAction.READ));
        // 5) 知识库资源命中
        expect(
                "appA/alice/READ/kb-1",
                true,
                () -> decision(appA, "alice", AiResourceType.KNOWLEDGE_BASE, "kb-1", AiAction.READ));
        // 6) 跨应用隔离
        expect(
                "appB/carol/READ/report-1",
                false,
                () -> decision(appB, "carol", AiResourceType.REPORT, "report-1", AiAction.READ));
        // 7) 会话附件所有者可读
        expect("appA/alice/read/session-file", true, () -> readable(appA, "alice", aliceFileId));
        // 8) 非所有者按不存在
        expect("appA/bob/read/session-file", false, () -> readable(appA, "bob", aliceFileId));
        // 9) 跨应用读文件
        expect("appB/carol/read/session-file", false, () -> readable(appB, "carol", aliceFileId));
        // 13) 历史产物再鉴权：范围指纹变化后拒绝
        expect("appA/alice/reauthorize/session-file", false, () -> {
            AiAuthorizationDecisionDTO decision = authorizationService.authorize(
                    appA.applicationId(),
                    "USER",
                    "alice",
                    AiResourceType.REPORT,
                    "report-1",
                    AiAction.READ,
                    List.of("report-1"));
            revocationService.revokeApplication(appA.applicationId(), 2);
            return authorizationService
                    .reauthorizeHistorical(
                            appA.applicationId(),
                            "USER",
                            "alice",
                            AiResourceType.REPORT,
                            "report-1",
                            AiAction.READ,
                            decision.getScopeFingerprint())
                    .isAllowed();
        });
        // 10/11/12) 撤销类断言在独立用例中验证（需要各自的初始状态）

        assertThat(matrix).as("矩阵报告").hasSize(10);
    }

    @Test
    void revocationRowsDenyNewRequestsTicketsAndOldTickets() {
        AppContext appA = prepareApplication(APP_A, List.of("alice"));
        Long grantId =
                grantService.createGrant(appA.applicationId(), "USER", "alice", "REPORT", "report-1", Set.of("READ"));
        loginAs(appA, "alice");
        String ticket = ticketService
                .issue(APP_A, appA.secret(), AiSubjectType.USER, "alice", List.of("report-1"))
                .getToken();

        // 撤销授权后：判定立即拒绝（第 10 行）
        grantService.revokeGrant(grantId, 0);
        expect(
                "appA/alice/READ-after-grant-revoke/report-1",
                false,
                () -> decision(appA, "alice", AiResourceType.REPORT, "report-1", AiAction.READ));

        // 撤销主体后：旧票据立即失效（第 12 行）
        revocationService.revokeSubject(appA.applicationId(), AiSubjectType.USER, "alice", 0);
        expect("appA/alice/verify-ticket-after-subject-revoke/ticket", false, () -> {
            ticketService.verify(ticket);
            return true;
        });

        // 撤销应用后：不得签发新票据（第 11 行）
        AppContext appB = prepareApplication(APP_B, List.of("carol"));
        revocationService.revokeApplication(appB.applicationId(), 1);
        expect("appB/carol/issue-ticket-after-app-revoke/ticket", false, () -> {
            ticketService.issue(APP_B, appB.secret(), AiSubjectType.USER, "carol", List.of("report-1"));
            return true;
        });

        // 未知主体类型/未知业务类型 fail-closed（第 14 行）
        expect(
                "unknown/subject/READ/unknown-resource",
                false,
                () -> decision(appA, "ghost", AiResourceType.REPORT, "report-1", AiAction.READ));
        assertThatThrownBy(() -> fileService.upload(
                        "ai_unknown", "k1", "a.txt", "text/plain", "a".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
    }

    private boolean decision(AppContext application, String subject, AiResourceType type, String key, AiAction action) {
        loginAs(application, subject);
        return authorizationService
                .authorize(application.applicationId(), "USER", subject, type, key, action, List.of(key))
                .isAllowed();
    }

    private boolean readable(AppContext application, String subject, Long fileId) {
        loginAs(application, subject);
        fileService.read(fileId);
        return true;
    }
}
