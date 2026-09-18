package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.service.session.UserSessionService;
import com.basicframework.module.system.service.user.AdminUserService;
import jakarta.servlet.Filter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * A05 身份隔离（AT-007/AT-008）：ADMIN 会话与 MEMBER（AI 票据）会话互斥，跨端调用一律 401/403，
 * 未知用户类型不回退到其他 Provider。
 *
 * <p>正反对照：AI 票据能被 MEMBER Provider 接受；同一票据拿到 {@code /admin-api} 无效；
 * ADMIN 会话拿到 {@code /app-api} 无效；未装配会话校验器的用户类型直接拒绝。
 */
@Import(AiIdentityIsolationIT.ResolverConfiguration.class)
class AiIdentityIsolationIT extends AbstractPersistenceIntegrationTest {

    private static final String APP_CODE = "it-identity-app";

    /** 与其它 IT 一致的测试口令值（createSession 按存储值比对，集成环境专用）。 */
    private static final String ADMIN_PASSWORD = "identity-integration-password";

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiTicketService ticketService;

    @Autowired
    private AiUserSessionCommonApi aiUserSessionCommonApi;

    @Autowired
    private UserSessionService userSessionService;

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private org.springframework.web.context.WebApplicationContext webApplicationContext;

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver identityScopeResolver() {
            return request -> Optional.of(
                    new SubjectScope(Set.of(10L), Set.of("report-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    @AfterEach
    void cleanUp() {
        List<Long> appIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
        for (Long appId : appIds) {
            jdbcTemplate.update("DELETE FROM ai_access_ticket WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_subject WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
        }
    }

    /**
     * MockMvc 不会像真实容器那样填充 servletPath，框架的"按 URL 前缀推导用户类型"因此拿不到值；
     * 这里显式写入 {@code login_user_type} 属性（框架优先级最高的来源），语义与生产一致。
     */
    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(webApplicationContext.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    private String issueAiTicket() {
        AiApplicationCredentialIssueDTO application = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 身份应用")
                .setOrigins(List.of("https://crm.example.com")));
        applicationService.updateStatus(application.getApplication().getId(), 0, true);
        subjectService.syncSubject(
                application.getApplication().getId(), AiSubjectType.USER, "alice", "Alice", "crm-auth", 1L);
        return ticketService
                .issue(APP_CODE, application.getSecret(), AiSubjectType.USER, "alice", List.of("report-1"))
                .getToken();
    }

    /** 造一个可登录的管理端用户（种子用户可能停用或密码过期，IT 自行创建一个受控用户）。 */
    private String issueAdminAccessToken() {
        String username = "it-identity-admin";
        jdbcTemplate.update("DELETE FROM system_users WHERE username = ?", username);
        jdbcTemplate.update("INSERT INTO system_dept (name, status) VALUES (?, 0)", username);
        Long deptId = jdbcTemplate.queryForObject("SELECT id FROM system_dept WHERE name = ?", Long.class, username);
        jdbcTemplate.update(
                """
                INSERT INTO system_users
                    (username, password, nickname, dept_id, post_ids, email, remark, sex, avatar, status, must_change_password)
                VALUES (?, ?, 'IT 身份用户', ?, '[]', 'it-identity@example.com', '', 1, '', 0, b'0')
                """,
                username,
                ADMIN_PASSWORD,
                deptId);
        Long userId =
                jdbcTemplate.queryForObject("SELECT id FROM system_users WHERE username = ?", Long.class, username);
        UserSessionDO session = userSessionService.createSession(userId, UserTypeEnum.ADMIN.getValue(), ADMIN_PASSWORD);
        return session.getAccessToken();
    }

    @Test
    void aiTicketAuthenticatesAsMemberButNotAsAdminEndpoint() throws Exception {
        String ticket = issueAiTicket();

        // 正向：票据能被 MEMBER Provider 接受（会话字段来自服务端）
        var session = aiUserSessionCommonApi.checkAccessToken(ticket);
        assertThat(session.getUserType()).isEqualTo(UserTypeEnum.MEMBER.getValue());
        assertThat(session.getUserInfo()).containsEntry(AiUserSessionCommonApi.INFO_KEY_EXTERNAL_USER_ID, "alice");

        // 反向：同一票据用于管理端端点 → 401（没有身份降级/类型复用）
        mockMvc()
                .perform(get("/admin-api/ai/model-endpoint/page?pageNo=1&pageSize=10")
                        .header("Authorization", "Bearer " + ticket))
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("跨端/未知类型必须是 401 或 403，且不得回退身份")
                        .isIn(401, 403));
    }

    @Test
    void adminSessionIsRejectedOnAppApiEndpoints() throws Exception {
        String adminToken = issueAdminAccessToken();

        // 正向：管理端会话可认证（此处只验证会话本身有效，端点权限由管理端权限表达式负责）
        assertThat(userSessionService.checkAccessToken(adminToken)).isNotNull();

        // 反向：ADMIN 会话拿到应用端受保护端点 → 401（应用端只接受 AI 票据，且不回退到 ADMIN 身份）
        mockMvc()
                .perform(post("/app-api/infra/file/upload").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("跨端/未知类型必须是 401 或 403，且不得回退身份")
                        .isIn(401, 403));
    }

    @Test
    void unknownUserTypeWithoutProviderIsRejectedInsteadOfFallingBack() throws Exception {
        String adminToken = issueAdminAccessToken();

        // 未装配会话校验器的用户类型（SYSTEM=0）→ 直接拒绝，不回退到 ADMIN
        mockMvc()
                .perform(post("/app-api/infra/file/upload")
                        .header("Authorization", "Bearer " + adminToken)
                        .requestAttr("login_user_type", UserTypeEnum.SYSTEM.getValue()))
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("跨端/未知类型必须是 401 或 403，且不得回退身份")
                        .isIn(401, 403));
    }
}
