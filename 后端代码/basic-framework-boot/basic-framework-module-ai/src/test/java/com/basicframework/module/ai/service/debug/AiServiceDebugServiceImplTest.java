package com.basicframework.module.ai.service.debug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.ai.core.model.StructuredModelRequest;
import com.basicframework.framework.ai.core.model.StructuredModelResult;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import com.basicframework.module.ai.dal.dataobject.subject.AiSubjectDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.runtime.AiContextSection;
import com.basicframework.module.ai.domain.runtime.AiRunSnapshot;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.ai.service.context.AiContextBuilder;
import com.basicframework.module.ai.service.context.dto.AiContextBuildDTO;
import com.basicframework.module.ai.service.context.dto.AiContextResultDTO;
import com.basicframework.module.ai.service.context.dto.AiContextSectionStatDTO;
import com.basicframework.module.ai.service.debug.dto.AiDebugStageDTO;
import com.basicframework.module.ai.service.debug.dto.AiServiceDebugResultDTO;
import com.basicframework.module.ai.service.debug.dto.AiServiceDebugRunDTO;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.AiModelInvocationResult;
import com.basicframework.module.ai.service.model.AiModelInvocationService;
import com.basicframework.module.ai.service.serviceconfig.AiServiceReleaseService;
import com.basicframework.module.ai.service.serviceconfig.AiServiceService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceRunSnapshotDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.ai.service.usage.AiModelInvocationRecord;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** S04 服务调试：显式测试主体、当前生效版本、阶段摘要与上游失败的稳定收敛。 */
class AiServiceDebugServiceImplTest {

    private static final Long SERVICE_ID = 9L;

    private static final Long APP_ID = 5L;

    private static final Long ENDPOINT_ID = 3L;

    private static final Long RELEASE_ID = 21L;

    private static final String EXTERNAL_USER = "u-1001";

    private final AiServiceReleaseService releaseService = mock(AiServiceReleaseService.class);

    private final AiAuthorizationService authorizationService = mock(AiAuthorizationService.class);

    private final AiSubjectService subjectService = mock(AiSubjectService.class);

    private final AiModelEndpointService endpointService = mock(AiModelEndpointService.class);

    private final AiContextBuilder contextBuilder = mock(AiContextBuilder.class);

    private final AiModelInvocationService invocationService = mock(AiModelInvocationService.class);

    private final AiServiceService serviceService = mock(AiServiceService.class);

    private final AiServiceDebugServiceImpl service = new AiServiceDebugServiceImpl(
            releaseService,
            serviceService,
            authorizationService,
            subjectService,
            endpointService,
            contextBuilder,
            invocationService);

    @BeforeEach
    void setUp() {
        AiServiceReleaseDO release = new AiServiceReleaseDO()
                .setId(RELEASE_ID)
                .setServiceId(SERVICE_ID)
                .setReleaseVersion(2)
                .setModelEndpointId(ENDPOINT_ID)
                .setEndpointConfigRevision(7)
                .setPromptTemplate("你是订单助手")
                .setContentHash("a".repeat(64))
                .setStatus(AiServiceReleaseDO.STATUS_ACTIVE);
        AiServiceResourceDO binding = new AiServiceResourceDO()
                .setId(11L)
                .setServiceId(SERVICE_ID)
                .setReleaseId(RELEASE_ID)
                .setResourceType("REPORT")
                .setResourceKey("report-1")
                .setActions("READ")
                .setStatus(AiServiceResourceDO.STATUS_ACTIVE)
                .setVersion(0);
        when(releaseService.resolveForNewRun(SERVICE_ID))
                .thenReturn(new AiServiceRunSnapshotDTO()
                        .setRelease(release)
                        .setBindings(List.of(binding))
                        .setPin(AiRunSnapshot.of(release, List.of(binding)))
                        .setPinned(false));
        when(serviceService.getService(SERVICE_ID))
                .thenReturn(new AiServiceDO().setId(SERVICE_ID).setAppId(APP_ID));
        when(subjectService.findActiveSubject(eq(APP_ID), any(), any())).thenReturn(Optional.of(new AiSubjectDO()));
        when(authorizationService.authorize(any(), anyString(), anyString(), any(), anyString(), any(), anyList()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true));
        when(endpointService.getEndpoint(ENDPOINT_ID))
                .thenReturn(new AiModelEndpointDO()
                        .setId(ENDPOINT_ID)
                        .setConfigRevision(7)
                        .setEnabled(true));
        when(endpointService.getRevisions(ENDPOINT_ID))
                .thenReturn(List.of(new AiModelEndpointRevisionDO()
                        .setEndpointId(ENDPOINT_ID)
                        .setRevision(1)
                        .setModelId("gpt-4o-mini")));
        when(contextBuilder.build(any()))
                .thenReturn(new AiContextResultDTO()
                        .setPrompt("拼装后的提示词")
                        .setSections(List.of(new AiContextSectionStatDTO()
                                .setSection(AiContextSection.POLICY)
                                .setIncludedCount(1)
                                .setEstimatedTokens(35)))
                        .setEstimatedTokens(60)
                        .setMaxTokens(8_000)
                        .setTruncated(false));
    }

    private static AiServiceDebugRunDTO runRequest() {
        return new AiServiceDebugRunDTO()
                .setServiceId(SERVICE_ID)
                .setTestSubjectType("USER")
                .setTestSubjectId(EXTERNAL_USER)
                .setUserMessage("帮我查一下订单 A-1")
                .setDataLevel("L2_INTERNAL");
    }

    private static void assertCode(
            Throwable throwable, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void runsAsExplicitTestSubjectAndReturnsStageSummaries() {
        when(invocationService.generate(eq(ENDPOINT_ID), any(), any()))
                .thenReturn(new AiModelInvocationResult<>(
                        mock(AiModelInvocationRecord.class),
                        new ModelResponse("订单 A-1 已发货", ModelUsage.of(50, 20), List.of(), "gpt-4o-mini", "stop")));

        AiServiceDebugResultDTO result = service.debugRun(runRequest());

        assertThat(result.getStages())
                .extracting(AiDebugStageDTO::getStage)
                .containsExactly("RESOLVE", "AUTHORIZE", "CONTEXT", "MODEL");
        assertThat(result.getStages()).allSatisfy(stage -> {
            assertThat(stage.getStatus()).isEqualTo("OK");
            assertThat(stage.getDurationMs()).isNotNull();
        });
        assertThat(result.getReleaseVersion()).isEqualTo(2);
        assertThat(result.getModelRevision()).isEqualTo(7);
        assertThat(result.getTestSubjectType()).isEqualTo("USER");
        assertThat(result.getAuthorizedBindings()).containsExactly("REPORT:report-1");
        assertThat(result.getOutput()).isEqualTo("订单 A-1 已发货");
        assertThat(result.getStructured()).isFalse();
        assertThat(result.getInputTokens()).isEqualTo(50);
        assertThat(result.getOutputTokens()).isEqualTo(20);
        assertThat(result.getSections()).singleElement().satisfies(section -> assertThat(section.getSection())
                .isEqualTo(AiContextSection.POLICY));

        // 上下文用发布版本冻结的提示词，而不是调用方提交的内容
        ArgumentCaptor<AiContextBuildDTO> contextCaptor = ArgumentCaptor.forClass(AiContextBuildDTO.class);
        verify(contextBuilder).build(contextCaptor.capture());
        assertThat(contextCaptor.getValue().getSystemPrompt()).isEqualTo("你是订单助手");
        assertThat(contextCaptor.getValue().getUserMessage()).isEqualTo("帮我查一下订单 A-1");
        // 模型拿到的是拼装结果，授权按测试主体判定
        ArgumentCaptor<ModelRequest> requestCaptor = ArgumentCaptor.forClass(ModelRequest.class);
        verify(invocationService).generate(eq(ENDPOINT_ID), requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().prompt()).isEqualTo("拼装后的提示词");
        assertThat(requestCaptor.getValue().modelId()).isEqualTo("gpt-4o-mini");
        verify(authorizationService)
                .authorize(
                        eq(APP_ID),
                        eq(AiSubjectType.USER.name()),
                        eq(EXTERNAL_USER),
                        any(),
                        eq("report-1"),
                        any(),
                        eq(List.of("report-1")));
    }

    @Test
    void usesStructuredInvocationWhenTheReleaseDeclaresAnOutputSchema() {
        when(releaseService.resolveForNewRun(SERVICE_ID))
                .thenReturn(new AiServiceRunSnapshotDTO()
                        .setRelease(new AiServiceReleaseDO()
                                .setId(RELEASE_ID)
                                .setServiceId(SERVICE_ID)
                                .setReleaseVersion(2)
                                .setModelEndpointId(ENDPOINT_ID)
                                .setEndpointConfigRevision(7)
                                .setPromptTemplate("你是订单助手")
                                .setOutputSchema("{\"type\":\"object\"}")
                                .setContentHash("a".repeat(64))
                                .setStatus(AiServiceReleaseDO.STATUS_ACTIVE))
                        .setBindings(List.of())
                        .setPin(AiRunSnapshot.of(
                                new AiServiceReleaseDO()
                                        .setId(RELEASE_ID)
                                        .setServiceId(SERVICE_ID)
                                        .setReleaseVersion(2)
                                        .setModelEndpointId(ENDPOINT_ID)
                                        .setEndpointConfigRevision(7)
                                        .setContentHash("a".repeat(64)),
                                List.of()))
                        .setPinned(false));
        when(invocationService.generateStructured(eq(ENDPOINT_ID), any(), any()))
                .thenReturn(new AiModelInvocationResult<>(
                        mock(AiModelInvocationRecord.class),
                        new StructuredModelResult(
                                "{\"orderId\":\"A-1\"}", null, ModelUsage.UNKNOWN, "stop", "gpt-4o-mini")));

        AiServiceDebugResultDTO result = service.debugRun(runRequest());

        assertThat(result.getStructured()).isTrue();
        assertThat(result.getOutput()).isEqualTo("{\"orderId\":\"A-1\"}");
        assertThat(result.getInputTokens()).as("上游未给用量时不伪造 0").isNull();
        ArgumentCaptor<StructuredModelRequest> captor = ArgumentCaptor.forClass(StructuredModelRequest.class);
        verify(invocationService).generateStructured(eq(ENDPOINT_ID), captor.capture(), any());
        assertThat(captor.getValue().jsonSchema()).isEqualTo("{\"type\":\"object\"}");
        verify(invocationService, never()).generate(any(), any(), any());
    }

    @Test
    void deniesDebugWhenTheTestSubjectHasNoGrant() {
        when(authorizationService.authorize(any(), anyString(), anyString(), any(), anyString(), any(), anyList()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false).setDenyReason("NO_GRANT"));

        assertThatThrownBy(() -> service.debugRun(runRequest()))
                .as("调试不能越权：测试主体没有的权限调试也拿不到")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_AUTHORIZATION_DENIED));
        verify(invocationService, never()).generate(any(), any(), any());
    }

    @Test
    void rejectsUnknownOrDisabledTestSubject() {
        when(subjectService.findActiveSubject(eq(APP_ID), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.debugRun(runRequest()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
        verify(invocationService, never()).generate(any(), any(), any());
    }

    @Test
    void requiresExplicitSubjectIdentifierAndKnownDataLevel() {
        assertThatThrownBy(() -> service.debugRun(runRequest().setTestSubjectId(null)))
                .as("USER 测试主体必须显式给出标识，不接受隐式当前用户")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));

        assertThatThrownBy(() -> service.debugRun(runRequest().setDataLevel("L9")))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));

        assertThatThrownBy(() -> service.debugRun(runRequest().setTimeoutMillis(0)))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        verify(invocationService, never()).generate(any(), any(), any());
    }

    @Test
    void upstreamFailuresBecomeStablePlatformErrors() {
        when(invocationService.generate(eq(ENDPOINT_ID), any(), any()))
                .thenThrow(new ModelException(ModelException.Reason.TIMEOUT, "上游超时"));
        assertThatThrownBy(() -> service.debugRun(runRequest()))
                .as("超时按模型调用失败收敛，不返回假成功")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_MODEL_CALL_FAILED));

        when(invocationService.generate(eq(ENDPOINT_ID), any(), any()))
                .thenThrow(new ModelException(ModelException.Reason.RATE_LIMITED, "上游限流"));
        assertThatThrownBy(() -> service.debugRun(runRequest()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_QUOTA_EXCEEDED));

        when(invocationService.generate(eq(ENDPOINT_ID), any(), any()))
                .thenThrow(new ModelException(ModelException.Reason.ENDPOINT_DISABLED, "端点停用"));
        assertThatThrownBy(() -> service.debugRun(runRequest()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_MODEL_ENDPOINT_DISABLED));

        when(invocationService.generate(eq(ENDPOINT_ID), any(), any()))
                .thenThrow(new ModelException(ModelException.Reason.TARGET_NOT_ALLOWED, "出站策略拒绝"));
        assertThatThrownBy(() -> service.debugRun(runRequest()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_MODEL_OUTBOUND_BLOCKED));
    }
}
