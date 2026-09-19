package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationDO;
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
import com.basicframework.module.ai.service.conversation.AiConversationService;
import com.basicframework.module.ai.service.conversation.dto.AiConversationCreateDTO;
import com.basicframework.module.ai.service.conversation.dto.AiConversationMessageSaveDTO;
import com.basicframework.module.ai.service.conversation.dto.AiConversationRunContextDTO;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.AiServiceReleaseService;
import com.basicframework.module.ai.service.serviceconfig.AiServiceService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceEvaluationSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceResourceSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceSaveDTO;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * O01 会话与消息端到端（真实 MySQL）：主体归属、越权与不存在同语义、分页稳定、
 * 删除先关闭访问、旧上下文进入新运行前必须重新鉴权。
 */
@Import(AiConversationIT.ResolverConfiguration.class)
class AiConversationIT extends AbstractPersistenceIntegrationTest {

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver conversationScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L), Set.of("report-1", "kb-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    private static final String APP_CODE = "it-conversation-app";

    private static final String ENDPOINT_NAME = "it-conversation-endpoint";

    private static final String USER_A = "it-conv-user-a";

    private static final String USER_B = "it-conv-user-b";

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiTicketService ticketService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiModelEndpointService endpointService;

    @Autowired
    private AiServiceService serviceService;

    @Autowired
    private AiServiceReleaseService releaseService;

    @Autowired
    private AiConversationService conversationService;

    private Long applicationId;

    private String applicationSecret;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        List<Long> appIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
        for (Long appId : appIds) {
            List<Long> conversationIds = jdbcTemplate.queryForList(
                    "SELECT id FROM ai_conversation WHERE application_id = ?", Long.class, appId);
            for (Long conversationId : conversationIds) {
                jdbcTemplate.update("DELETE FROM ai_conversation_message WHERE conversation_id = ?", conversationId);
            }
            jdbcTemplate.update("DELETE FROM ai_conversation WHERE application_id = ?", appId);
            List<Long> serviceIds =
                    jdbcTemplate.queryForList("SELECT id FROM ai_service WHERE app_id = ?", Long.class, appId);
            for (Long serviceId : serviceIds) {
                List<Long> releaseIds = jdbcTemplate.queryForList(
                        "SELECT id FROM ai_service_release WHERE service_id = ?", Long.class, serviceId);
                for (Long releaseId : releaseIds) {
                    jdbcTemplate.update("DELETE FROM ai_service_release_evaluation WHERE release_id = ?", releaseId);
                }
                jdbcTemplate.update("DELETE FROM ai_service_resource WHERE service_id = ?", serviceId);
                jdbcTemplate.update("DELETE FROM ai_service_release WHERE service_id = ?", serviceId);
                jdbcTemplate.update("DELETE FROM ai_service WHERE id = ?", serviceId);
            }
            jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_subject WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_access_ticket WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
        }
        List<Long> endpointIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_model_endpoint WHERE name = ?", Long.class, ENDPOINT_NAME);
        for (Long endpointId : endpointIds) {
            jdbcTemplate.update("DELETE FROM ai_model_probe WHERE endpoint_id = ?", endpointId);
            jdbcTemplate.update("DELETE FROM ai_model_endpoint_revision WHERE endpoint_id = ?", endpointId);
            jdbcTemplate.update("DELETE FROM ai_model_endpoint WHERE id = ?", endpointId);
        }
    }

    private void prepareApplicationWithSubjects() {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 会话应用")
                .setOrigins(List.of("https://conversation.example.com")));
        applicationId = issue.getApplication().getId();
        applicationSecret = issue.getSecret();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.APP, null, "IT 会话应用", "it-owner", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, USER_A, "会话用户 A", "it-scope", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, USER_B, "会话用户 B", "it-scope", 1L);
    }

    /** 模拟 MEMBER 会话：登录用户编号使用真实票据编号（A05 约定），身份只来自服务端。 */
    private void loginAs(String externalUserId) {
        String token = ticketService
                .issue(APP_CODE, applicationSecret, AiSubjectType.USER, externalUserId, List.of("report-1"))
                .getToken();
        var context = ticketService.verify(token);
        LoginUser loginUser = new LoginUser()
                .setId(context.getTicketId())
                .setUserType(com.basicframework.framework.common.enums.UserTypeEnum.MEMBER.getValue())
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

    /** 建好一个已发布服务（含 APP 授权），返回服务编号与发布版本编号。 */
    private long[] preparePublishedService() {
        grantService.createGrant(applicationId, "APP", null, "REPORT", "report-1", Set.of("READ"));
        AiModelEndpointSaveDTO endpointSave = new AiModelEndpointSaveDTO();
        endpointSave.setName(ENDPOINT_NAME);
        endpointSave.setProvider("openai_compatible");
        endpointSave.setBaseUrl("https://it-conversation.example.com/v1");
        endpointSave.setModelId("gpt-4o-mini");
        endpointSave.setCapabilities(List.of("TEXT"));
        endpointSave.setCredential("sk-it-conversation");
        Long endpointId = endpointService.createEndpoint(endpointSave);
        var endpoint = endpointService.getEndpoint(endpointId);
        endpointService.updateEndpointStatus(endpointId, endpoint.getVersion(), true);
        var revision = endpointService.getRevisions(endpointId).get(0);
        jdbcTemplate.update(
                "INSERT INTO ai_model_probe (endpoint_id, config_revision, credential_revision, probe_kind, status,"
                        + " detail_code, latency_ms, creator, updater) VALUES (?, ?, 1, 'TEXT', 'SUPPORTED', NULL, 5,"
                        + " 'it', 'it')",
                endpointId,
                revision.getRevision());

        Long serviceId = serviceService.createDraft(new AiServiceSaveDTO()
                .setAppId(applicationId)
                .setCode("order-qa")
                .setName("订单问答")
                .setModelEndpointId(endpointId)
                .setPromptTemplate("你是订单助手")
                .setInputSchema("{\"type\":\"object\"}")
                .setRequiredCapabilities(List.of("TEXT"))
                .setRunSubjectType("USER")
                .setEvalThreshold(0));
        serviceService.bindResource(new AiServiceResourceSaveDTO()
                .setServiceId(serviceId)
                .setResourceType("REPORT")
                .setResourceKey("report-1")
                .setActions(List.of("READ")));
        var service = serviceService.getService(serviceId);
        serviceService.markReady(serviceId, service.getVersion());
        var ready = serviceService.getService(serviceId);
        Long releaseId = releaseService.createCandidate(serviceId, ready.getVersion());
        releaseService.recordEvaluation(new AiServiceEvaluationSaveDTO()
                .setReleaseId(releaseId)
                .setScore(90)
                .setCaseCount(5));
        releaseService.publish(releaseId, 0);
        return new long[] {serviceId, releaseId};
    }

    private static void assertCode(
            Throwable throwable, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void conversationsAreScopedToTheResolvedSubject() {
        prepareApplicationWithSubjects();

        loginAs(USER_A);
        Long conversationId = conversationService.create(new AiConversationCreateDTO()
                .setConversationKey("conv_order_qa")
                .setTitle("订单问答")
                .setBusinessContext("{\"page\":\"order\"}"));
        conversationService.appendMessage(new AiConversationMessageSaveDTO()
                .setConversationId(conversationId)
                .setRole("user")
                .setContent("帮我查订单 A-1"));

        // 另一个主体：看不到、改不了、也读不到消息（越权与不存在同语义）
        loginAs(USER_B);
        assertThatThrownBy(() -> conversationService.getConversation(conversationId))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> conversationService.rename(conversationId, "越权改名", 0))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> conversationService.listMessages(conversationId, null, 10))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
        assertThat(conversationService.getPage(new PageParam()).getList()).isEmpty();

        // 会话业务键在同一应用+主体内唯一
        loginAs(USER_A);
        assertThatThrownBy(() ->
                        conversationService.create(new AiConversationCreateDTO().setConversationKey("conv_order_qa")))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONVERSATION_KEY_DUPLICATE));
        assertThat(conversationService.getPage(new PageParam()).getList()).hasSize(1);
    }

    @Test
    void pagingStaysStableWhileMessagesAreAppended() {
        prepareApplicationWithSubjects();
        loginAs(USER_A);
        Long conversationId =
                conversationService.create(new AiConversationCreateDTO().setConversationKey("conv_paging"));

        for (int index = 1; index <= 5; index++) {
            conversationService.appendMessage(new AiConversationMessageSaveDTO()
                    .setConversationId(conversationId)
                    .setRole(index % 2 == 1 ? "user" : "assistant")
                    .setContent("第 " + index + " 条"));
        }

        // 按序号推进：翻页不重复、不跳过，即使期间又追加了新消息
        var firstPage = conversationService.listMessages(conversationId, null, 2);
        assertThat(firstPage).extracting(item -> item.getSequenceNo()).containsExactly(1, 2);
        conversationService.appendMessage(new AiConversationMessageSaveDTO()
                .setConversationId(conversationId)
                .setRole("user")
                .setContent("第 6 条"));
        var secondPage = conversationService.listMessages(
                conversationId, firstPage.get(firstPage.size() - 1).getSequenceNo(), 2);
        assertThat(secondPage).extracting(item -> item.getSequenceNo()).containsExactly(3, 4);

        AiConversationDO conversation = conversationService.getConversation(conversationId);
        assertThat(conversation.getMessageCount()).isEqualTo(6);
        assertThat(conversation.getLastMessageTime()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_conversation_message WHERE conversation_id = ? AND deleted = 0",
                        Integer.class,
                        conversationId))
                .as("每条消息都带正文摘要写入")
                .isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_conversation_message WHERE conversation_id = ? AND CHAR_LENGTH(content_hash) = 64",
                        Integer.class,
                        conversationId))
                .isEqualTo(6);
    }

    @Test
    void deleteClosesAccessBeforeContentIsPurged() {
        prepareApplicationWithSubjects();
        loginAs(USER_A);
        Long conversationId =
                conversationService.create(new AiConversationCreateDTO().setConversationKey("conv_delete"));
        conversationService.appendMessage(new AiConversationMessageSaveDTO()
                .setConversationId(conversationId)
                .setRole("user")
                .setContent("待删除会话的消息"));

        AiConversationDO before = conversationService.getConversation(conversationId);
        conversationService.delete(conversationId, before.getVersion());

        // 访问立即关闭：会话与消息都不可读
        assertThatThrownBy(() -> conversationService.getConversation(conversationId))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> conversationService.listMessages(conversationId, null, 10))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM ai_conversation WHERE id = ?", String.class, conversationId))
                .isEqualTo(AiConversationDO.STATUS_DELETED);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_conversation_message WHERE conversation_id = ? AND status = 'DELETED'",
                        Integer.class,
                        conversationId))
                .as("消息访问同时关闭")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT deleted FROM ai_conversation WHERE id = ?", Boolean.class, conversationId))
                .as("会话行走标准逻辑删除")
                .isTrue();
    }

    @Test
    void runContextReauthorizesThePinnedReleaseBeforeReusingOldContext() {
        prepareApplicationWithSubjects();
        long[] published = preparePublishedService();
        loginAs(USER_A);
        Long conversationId = conversationService.create(new AiConversationCreateDTO()
                .setConversationKey("conv_run_context")
                .setServiceId(published[0]));
        AiConversationDO created = conversationService.getConversation(conversationId);
        conversationService.bindRelease(conversationId, published[1], created.getVersion());
        conversationService.appendMessage(new AiConversationMessageSaveDTO()
                .setConversationId(conversationId)
                .setRole("user")
                .setContent("历史消息"));

        AiConversationRunContextDTO context = conversationService.loadRunContext(conversationId, 10);
        assertThat(context.getPin()).isNotNull();
        assertThat(context.getPin().getReleaseId()).isEqualTo(published[1]);
        assertThat(context.getHistory()).singleElement();

        // 当前授权失效：旧上下文不得进入新运行（固定版本不保留旧权限）
        Long grantId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_resource_grant WHERE application_id = ? AND subject_type = 'APP' AND"
                        + " resource_key = 'report-1'",
                Long.class,
                applicationId);
        grantService.revokeGrant(grantId, grantService.getGrant(grantId).getVersion());
        assertThatThrownBy(() -> conversationService.loadRunContext(conversationId, 10))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_AUTHORIZATION_DENIED));
    }
}
