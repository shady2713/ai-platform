package com.basicframework.server.integration;

import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.connector.AiConnectorService;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import com.basicframework.module.ai.service.connector.importer.AiConnectorOperationService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import com.basicframework.module.ai.service.realtime.AiRealtimeSessionService;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeAcceptDTO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeAudioPushDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.ai.service.tool.AiToolService;
import com.basicframework.module.ai.service.tool.dto.AiToolSaveDTO;
import com.basicframework.module.ai.service.tool.dto.AiToolVersionSaveDTO;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 实时语音会话验收夹具（X05）：真实 MySQL/Redis + 本地协议替身 + 业务系统模拟器。
 *
 * <p>为什么用本地协议替身而不是真实供应商：真实实时语音需要供应商凭据与出网通道（本环境未授权，
 * 属 ADR 0052 的未验证项）。替身实现真实的 {@code RealtimeAdapter} 契约（真实探测结论 + 真实通道
 * 往返），被测代码没有任何测试开关；工具执行仍然走**真实**的连接器出站链路与业务系统模拟器，
 * 因此"重连不重复执行工具"是端到端事实，而不是打桩断言。
 */
abstract class AiRealtimeAcceptanceSupport extends AbstractPersistenceIntegrationTest {

    protected static final String APP_CODE = "it-realtime-app";

    protected static final String ENDPOINT_NAME = "IT 实时语音端点";

    protected static final String CONNECTOR_CODE = "it-realtime-connector";

    protected static final String TOOL_CODE = "it-realtime-lookup";

    /** 需要人工确认的工具（会话内必须拒绝：它必须走运行/动作流程）。 */
    protected static final String CONFIRM_TOOL_CODE = "it-realtime-confirm";

    protected static final String USER_A = "it-realtime-user-a";

    protected static final String USER_B = "it-realtime-user-b";

    /** 登录夹具用的授权资源键（实时语音本身不消费 scope；这里只为换取票据）。 */
    protected static final String GRANT_RESOURCE_KEY = "realtime-fixture-1";

    protected static final String AUDIO_FORMAT = "audio/pcm@16000:1:20";

    protected static final String OPEN_API_DOCUMENT =
            """
            {"openapi": "3.0.0",
             "info": {"title": "IT 实时语音业务系统", "version": "1.0.0"},
             "paths": {
               "/payments/query": {"get": {"operationId": "getPayment", "summary": "按业务键查收款",
                 "parameters": [{"name": "payment_no", "in": "query", "required": true,
                                 "schema": {"type": "string"}}],
                 "responses": {"200": {"description": "ok"}}}}}}
            """;

    @Autowired
    protected AiRealtimeSessionService sessionService;

    @Autowired
    protected AiRealtimeProtocolDouble protocolDouble;

    @Autowired
    protected AiBusinessWriteSimulator simulator;

    @Autowired
    protected AiApplicationService applicationService;

    @Autowired
    protected AiSubjectService subjectService;

    @Autowired
    protected com.basicframework.module.ai.service.authorization.AiResourceGrantService grantService;

    /** 能力验证台账（测试里用于清除"冷却窗口内"的失败结论；经 MyBatis 走，避免一级缓存读到旧行）。 */
    @Autowired
    protected com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeEndpointCapabilityMapper capabilityMapper;

    @Autowired
    protected AiTicketService ticketService;

    @Autowired
    protected AiModelEndpointService endpointService;

    @Autowired
    protected AiConnectorService connectorService;

    @Autowired
    protected AiConnectorOperationService operationService;

    @Autowired
    protected AiToolService toolService;

    protected Long applicationId;

    protected String applicationSecret;

    protected Long endpointId;

    /** 与既有 AI IT 同源：业务系统模拟器作为受控出站边界注入（真实边界只对未授权目标生效）。 */
    @TestConfiguration
    static class RealtimeFixtureConfiguration {

        @Bean
        @Primary
        ExternalHttpClient realtimeSimulatorHttpClient() {
            return new AiBusinessWriteSimulator();
        }

        @Bean
        AiRealtimeProtocolDouble realtimeProtocolDouble() {
            return new AiRealtimeProtocolDouble();
        }

        /**
         * 主体范围解析接缝（与既有 AI IT 同口径）：生产环境的解析器由接入方装配，
         * 测试上下文提供最小实现，让"换票 → MEMBER 会话"的登录夹具可运行。
         */
        @Bean
        com.basicframework.module.ai.domain.identity.SubjectScopeResolver realtimeScopeResolver() {
            return request -> java.util.Optional.of(new com.basicframework.module.ai.domain.identity.SubjectScope(
                    java.util.Set.of(10L),
                    java.util.Set.of(GRANT_RESOURCE_KEY),
                    request.scopeSource(),
                    request.scopeVersion()));
        }
    }

    @BeforeEach
    void prepareFixture() {
        simulator.reset();
        protocolDouble.reset();
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 实时语音应用")
                .setOrigins(List.of("https://realtime.example.com")));
        applicationId = issue.getApplication().getId();
        applicationSecret = issue.getSecret();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.APP, null, "IT 实时语音应用", "it-owner", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, USER_A, "实时语音用户 A", "it-owner", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, USER_B, "实时语音用户 B", "it-owner", 1L);

        AiModelEndpointSaveDTO endpointSave = new AiModelEndpointSaveDTO();
        endpointSave.setName(ENDPOINT_NAME);
        endpointSave.setProvider("openai_compatible");
        endpointSave.setBaseUrl("https://it-realtime.example.com/v1");
        endpointSave.setModelId("realtime-1");
        endpointSave.setCapabilities(List.of("TEXT"));
        endpointSave.setCredential("sk-it-realtime");
        endpointId = endpointService.createEndpoint(endpointSave);
        // 实时会话受理只接受启用端点（与模型调用链路同一准入口径；版本号取当前值）
        endpointService.updateEndpointStatus(
                endpointId, endpointService.getEndpoint(endpointId).getVersion(), true);

        Long connectorId = connectorService.create(new AiConnectorSaveDTO()
                .setCode(CONNECTOR_CODE)
                .setName("IT 实时语音连接器")
                .setConnectorType(AiConnectorDO.TYPE_HTTP)
                .setConfigJson(
                        "{\"baseUrl\":\"" + AiBusinessWriteSimulator.SIMULATOR_ORIGIN + "\",\"method\":\"POST\"}"));
        operationService.importOperations(connectorId, OPEN_API_DOCUMENT);
        for (var operation : operationService.listOperations(connectorId)) {
            operationService.publish(operation.getId(), operation.getVersion());
        }
        Long toolId = toolService.create(
                new AiToolSaveDTO().setCode(TOOL_CODE).setName("实时语音查询工具").setConnectorId(connectorId));
        Long versionId = toolService.createVersion(new AiToolVersionSaveDTO()
                .setToolId(toolId)
                .setToolType("READ")
                .setPolicy("AUTO")
                .setSourceKind(AiToolVersionDO.SOURCE_HTTP_OPERATION)
                .setSourceRef("getPayment")
                .setInputSchemaJson("{\"payment_no\": {\"type\": \"string\", \"required\": true}}")
                .setOutputSchemaJson("{\"columns\": []}"));
        toolService.publishVersion(versionId, toolService.getVersion(versionId).getVersion());
        // 需要确认的工具（已发布但政策 CONFIRM）：会话内执行必须被拒绝
        Long confirmToolId = toolService.create(new AiToolSaveDTO()
                .setCode(CONFIRM_TOOL_CODE)
                .setName("实时语音需确认工具")
                .setConnectorId(connectorId));
        Long confirmVersionId = toolService.createVersion(new AiToolVersionSaveDTO()
                .setToolId(confirmToolId)
                .setToolType("READ")
                .setPolicy("CONFIRM")
                .setSourceKind(AiToolVersionDO.SOURCE_HTTP_OPERATION)
                .setSourceRef("getPayment")
                .setInputSchemaJson("{\"payment_no\": {\"type\": \"string\", \"required\": true}}")
                .setOutputSchemaJson("{\"columns\": []}"));
        toolService.publishVersion(
                confirmVersionId, toolService.getVersion(confirmVersionId).getVersion());
        // 登录夹具需要一条生效授权（与既有 AI IT 同口径：应用级授权 + 资源提示换取票据）
        grantService.createGrant(applicationId, "APP", null, "REPORT", GRANT_RESOURCE_KEY, java.util.Set.of("READ"));
        loginAs(USER_A);
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        protocolDouble.reset();
        simulator.reset();
    }

    /** 受理请求（端点与格式由夹具决定；寿命取 120 秒以缩短用例时间）。 */
    protected AiRealtimeAcceptDTO acceptRequest(String requestKey) {
        return new AiRealtimeAcceptDTO()
                .setRequestKey(requestKey)
                .setEndpointId(endpointId)
                .setProtocol("WEBSOCKET")
                .setAudioFormat(AUDIO_FORMAT)
                .setSessionSeconds(120);
    }

    /** 一帧音频：PCM 用格式上限（640 字节），压缩封装用 64 KiB（便于触发背压）。 */
    protected static AiRealtimeAudioPushDTO frame(long turnNo, long frameSeq, String format) {
        int frameBytes = "audio/ogg@16000:1:20".equals(format) ? 64 * 1024 : 640;
        return new AiRealtimeAudioPushDTO()
                .setTurnNo(turnNo)
                .setFrameSeq(frameSeq)
                .setPayload("x".repeat(frameBytes).getBytes(StandardCharsets.UTF_8));
    }

    /** 模拟 MEMBER 会话：身份只来自服务端（与既有 AI IT 同一夹具口径）。 */
    protected void loginAs(String externalUserId) {
        String token = ticketService
                .issue(APP_CODE, applicationSecret, AiSubjectType.USER, externalUserId, List.of(GRANT_RESOURCE_KEY))
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
}
