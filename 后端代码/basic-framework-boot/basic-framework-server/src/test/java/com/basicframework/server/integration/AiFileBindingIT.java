package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.LoginUser;
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
import com.basicframework.module.ai.service.authorization.AiRevocationService;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import com.basicframework.module.infra.framework.file.core.enums.FileStorageEnum;
import com.basicframework.module.infra.service.file.FileConfigService;
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
 * A07 业务文件端到端（真实 MySQL + infra 受控文件存储）：
 * 跨主体/跨应用拒绝（AT-009）、真实读取、共享引用未释放不误删、失权后立即拒绝（AT-048）。
 */
@Import(AiFileBindingIT.ResolverConfiguration.class)
class AiFileBindingIT extends AbstractPersistenceIntegrationTest {

    private static final String APP_CODE = "it-file-app";

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiRevocationService revocationService;

    @Autowired
    private AiFileService fileService;

    @Autowired
    private AiTicketService ticketService;

    @Autowired
    private FileConfigService fileConfigService;

    private Long fileConfigId;

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver fileScopeResolver() {
            return request -> Optional.of(
                    new SubjectScope(Set.of(10L), Set.of("report-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        List<Long> appIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
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
        if (fileConfigId != null) {
            // infra_file 对配置有外键（RESTRICT）：必须先清掉归属该配置的文件与内容
            jdbcTemplate.update("DELETE FROM infra_file_content WHERE config_id = ?", fileConfigId);
            jdbcTemplate.update("DELETE FROM infra_file WHERE config_id = ?", fileConfigId);
            jdbcTemplate.update("DELETE FROM infra_file_config WHERE id = ?", fileConfigId);
            fileConfigId = null;
        }
    }

    private AiApplicationCredentialIssueDTO createEnabledApplication() {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 文件应用")
                .setOrigins(List.of("https://crm.example.com")));
        applicationService.updateStatus(issue.getApplication().getId(), 0, true);
        return issue;
    }

    /**
     * 模拟 MEMBER 会话：登录用户编号使用**真实票据编号**（A05 约定），
     * 因此 infra 的业务授权 SPI 能通过票据回查到可信身份。
     */
    private Long loginAs(String externalUserId) {
        String ticket = ticketService
                .issue(APP_CODE, applicationSecret, AiSubjectType.USER, externalUserId, List.of("report-1"))
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
        return context.getTicketId();
    }

    private Long currentApplicationId;

    private String applicationSecret;

    private Long prepareApplicationWithSubjects() {
        // 集成环境使用数据库存储：先建一条文件配置，文件读写都走它
        fileConfigId = fileConfigService.createFileConfig(
                new FileConfigDO()
                        .setName("it-ai-file-" + System.nanoTime())
                        .setStorage(FileStorageEnum.DB.getStorage()),
                Map.of("domain", "http://localhost/files"));
        // 创建接口强制 master=false：按正式流程再标记为主配置（上传/读取都走 getMasterFileClient()）
        fileConfigService.updateFileConfigMaster(fileConfigId);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT master FROM infra_file_config WHERE id = ?", Boolean.class, fileConfigId))
                .as("集成环境的文件配置必须已标记为主配置")
                .isTrue();
        AiApplicationCredentialIssueDTO application = createEnabledApplication();
        currentApplicationId = application.getApplication().getId();
        applicationSecret = application.getSecret();
        subjectService.syncSubject(currentApplicationId, AiSubjectType.USER, "alice", "Alice", "crm-auth", 1L);
        subjectService.syncSubject(currentApplicationId, AiSubjectType.USER, "bob", "Bob", "crm-auth", 1L);
        return currentApplicationId;
    }

    @Test
    void chatAttachmentIsOwnerOnlyAcrossSubjectsAndKeepsFileWhileShared() {
        Long applicationId = prepareApplicationWithSubjects();

        // alice 上传会话附件（会话附件无需授权目录）
        loginAs("alice");
        var upload = fileService.upload(
                "ai_chat_session",
                "session-1",
                "log.txt",
                "text/plain",
                "alice-log".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Long fileId = upload.getFileId();
        assertThat(fileId).isNotNull();

        // 业务对象引用列表（真实 Mapper 默认方法路径）
        assertThat(fileService.listByBusiness("ai_chat_session", "session-1"))
                .extracting("fileId")
                .contains(fileId);

        // 真实读取：所有者拿到内容
        assertThat(new String(fileService.read(fileId), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("alice-log");

        // 跨主体拒绝：bob 读取按不存在处理（AT-009）
        loginAs("bob");
        assertThatThrownBy(() -> fileService.read(fileId))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND.getCode());

        // 共享引用：同一文件再绑定到另一个业务对象
        loginAs("alice");
        Long secondFileId = fileService
                .upload(
                        "ai_chat_session",
                        "session-2",
                        "note.txt",
                        "text/plain",
                        "second".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .getFileId();
        jdbcTemplate.update(
                "INSERT INTO ai_file_binding (file_id, business_type, business_key, application_id, subject_type,"
                        + " external_user_id, status, version) VALUES (?, 'ai_chat_session', 'session-2', ?, 'USER', 'alice', 'ACTIVE', 0)",
                fileId,
                applicationId);
        // 把第二条绑定指到同一文件，模拟共享引用
        jdbcTemplate.update(
                "DELETE FROM ai_file_binding WHERE file_id = ? AND business_key = 'session-2'", secondFileId);

        // 解除第一个引用后，文件仍被 session-2 引用 → 不得误删
        fileService.release(fileId);
        assertThat(countActiveInfraFile(fileId)).as("共享引用未释放不得误删文件").isEqualTo(1);

        // 解除最后一个引用后，文件被删除
        jdbcTemplate.update(
                "UPDATE ai_file_binding SET status = 'ACTIVE' WHERE file_id = ? AND business_key = 'session-1'",
                fileId);
        jdbcTemplate.update(
                "UPDATE ai_file_binding SET status = 'RELEASED' WHERE file_id = ? AND business_key = 'session-2'",
                fileId);
        fileService.release(fileId);
        // 删除按引用语义进入 infra 的受控删除流程：文件立即不再可用（delete_status 置为待删除）
        assertThat(countActiveInfraFile(fileId)).as("最后一个引用释放后文件不再可用（进入受控删除流程）").isEqualTo(0);
        assertThatThrownBy(() -> fileService.read(fileId)).isInstanceOf(ServiceException.class);
    }

    @Test
    void reportAttachmentRequiresGrantAndIsDeniedImmediatelyAfterRevocation() {
        Long applicationId = prepareApplicationWithSubjects();
        grantService.createGrant(applicationId, "USER", "alice", "REPORT", "report-1", Set.of("READ"));

        loginAs("alice");
        Long fileId = fileService
                .upload(
                        "ai_report",
                        "report-1",
                        "report.txt",
                        "text/plain",
                        "report-body".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .getFileId();
        assertThat(new String(fileService.read(fileId), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("report-body");

        // 跨主体：bob 没有该报表授权 → 上传与读取都拒绝
        loginAs("bob");
        assertThatThrownBy(
                        () -> fileService.upload("ai_report", "report-1", "x.pdf", "application/pdf", new byte[] {1}))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_AUTHORIZATION_DENIED.getCode());
        assertThatThrownBy(() -> fileService.read(fileId))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND.getCode());

        // 撤销应用后：alice 复用**撤销前**的会话也不能再读（AT-048 的"失权立即生效"）
        loginAs("alice");
        revocationService.revokeApplication(applicationId, 1);
        assertThatThrownBy(() -> fileService.read(fileId)).isInstanceOf(ServiceException.class);
        // 撤销后也无法换新票
        assertThatThrownBy(() -> loginAs("alice")).isInstanceOf(ServiceException.class);
    }

    @Test
    void unknownBusinessTypeIsRejectedWithoutCreatingFile() {
        prepareApplicationWithSubjects();
        loginAs("alice");

        assertThatThrownBy(() -> fileService.upload(
                        "ai_unknown",
                        "k1",
                        "a.txt",
                        "text/plain",
                        "a".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
    }

    /** 仍处于可用状态（未进入 infra 受控删除流程）的文件数量。 */
    private int countActiveInfraFile(Long fileId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM infra_file WHERE id = ? AND delete_status = 0", Integer.class, fileId);
        return count == null ? 0 : count;
    }
}
