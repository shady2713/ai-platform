package com.basicframework.module.ai.service.serviceconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceMapper;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceReleaseMapper;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceResourceMapper;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.ai.service.model.AiModelCapabilityProbeService;
import com.basicframework.module.ai.service.model.dto.AiModelCapabilityOverviewDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceResourceSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceSaveDTO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * S01 服务草稿：草稿校验、乐观锁、能力门禁与资源绑定越权校验。
 */
class AiServiceServiceImplTest {

    private AiServiceMapper serviceMapper;

    private AiServiceResourceMapper resourceMapper;

    private AiServiceReleaseMapper releaseMapper;

    private AiAuthorizationService authorizationService;

    private AiModelCapabilityProbeService probeService;

    private AiServiceServiceImpl service;

    @BeforeEach
    void setUp() {
        serviceMapper = mock(AiServiceMapper.class);
        resourceMapper = mock(AiServiceResourceMapper.class);
        releaseMapper = mock(AiServiceReleaseMapper.class);
        authorizationService = mock(AiAuthorizationService.class);
        probeService = mock(AiModelCapabilityProbeService.class);
        service = new AiServiceServiceImpl(
                serviceMapper, resourceMapper, releaseMapper, authorizationService, probeService);
    }

    private static AiServiceSaveDTO draft() {
        return new AiServiceSaveDTO()
                .setAppId(5L)
                .setCode("order-qa")
                .setName("订单问答")
                .setModelEndpointId(1L)
                .setPromptTemplate("你是订单助手")
                .setInputSchema("{\"type\":\"object\"}")
                .setRequiredCapabilities(List.of("TEXT"))
                .setRunSubjectType("USER");
    }

    private static AiServiceDO stored(int version, int revision) {
        return new AiServiceDO()
                .setId(9L)
                .setAppId(5L)
                .setCode("order-qa")
                .setName("订单问答")
                .setStatus(AiServiceDO.STATUS_DRAFT)
                .setModelEndpointId(1L)
                .setPromptTemplate("你是订单助手")
                .setInputSchema("{\"type\":\"object\"}")
                .setRequiredCapabilities("TEXT")
                .setRunSubjectType("USER")
                .setDraftRevision(revision)
                .setVersion(version);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, int code) {
        assertThatThrownBy(callable)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(code);
    }

    @Test
    void createRejectsInvalidDraftInputs() {
        when(serviceMapper.selectByAppAndCode(5L, "order-qa")).thenReturn(null);

        // 缺少提示词
        assertCode(
                () -> service.createDraft(draft().setPromptTemplate(" ")),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        // 输入 Schema 不是 JSON 对象
        assertCode(
                () -> service.createDraft(draft().setInputSchema("[1,2]")),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.createDraft(draft().setInputSchema("{不是 JSON}")),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        // 能力词表外
        assertCode(
                () -> service.createDraft(draft().setRequiredCapabilities(List.of("MAGIC"))),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        // 运行主体类型非法
        assertCode(
                () -> service.createDraft(draft().setRunSubjectType("ROBOT")),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        verify(serviceMapper, never()).insert(any(AiServiceDO.class));
    }

    @Test
    void createRejectsDuplicateCodeWithinApplication() {
        when(serviceMapper.selectByAppAndCode(5L, "order-qa")).thenReturn(stored(0, 1));

        assertCode(() -> service.createDraft(draft()), AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());
        verify(serviceMapper, never()).insert(any(AiServiceDO.class));
    }

    @Test
    void createNormalizesCapabilitiesAndStartsAsDraft() {
        when(serviceMapper.selectByAppAndCode(5L, "order-qa")).thenReturn(null);
        when(serviceMapper.insert(any(AiServiceDO.class))).thenAnswer(invocation -> {
            ((AiServiceDO) invocation.getArgument(0)).setId(9L);
            return 1;
        });

        service.createDraft(draft().setRequiredCapabilities(List.of("structured_output", "TEXT")));

        ArgumentCaptor<AiServiceDO> captor = ArgumentCaptor.forClass(AiServiceDO.class);
        verify(serviceMapper).insert(captor.capture());
        assertThat(captor.getValue().getRequiredCapabilities()).isEqualTo("STRUCTURED_OUTPUT,TEXT");
        assertThat(captor.getValue().getStatus()).isEqualTo(AiServiceDO.STATUS_DRAFT);
        assertThat(captor.getValue().getDraftRevision()).isEqualTo(1);
    }

    @Test
    void updateBumpsDraftRevisionAndRejectsStaleVersion() {
        when(serviceMapper.selectById(9L)).thenReturn(stored(3, 2));
        when(serviceMapper.updateWithVersion(any(AiServiceDO.class), eq(3))).thenReturn(1);

        service.updateDraft(draft().setId(9L).setVersion(3));

        ArgumentCaptor<AiServiceDO> captor = ArgumentCaptor.forClass(AiServiceDO.class);
        verify(serviceMapper).updateWithVersion(captor.capture(), eq(3));
        assertThat(captor.getValue().getDraftRevision()).isEqualTo(3);
        // 配置变更后回到 DRAFT（需要重新确认可发布）
        assertThat(captor.getValue().getStatus()).isEqualTo(AiServiceDO.STATUS_DRAFT);

        when(serviceMapper.updateWithVersion(any(AiServiceDO.class), eq(3))).thenReturn(0);
        assertCode(
                () -> service.updateDraft(draft().setId(9L).setVersion(3)),
                AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());
    }

    @Test
    void markReadyRequiresEndpointCapabilities() {
        when(serviceMapper.selectById(9L)).thenReturn(stored(1, 1));
        when(probeService.getCapabilityOverview(1L))
                .thenReturn(new AiModelCapabilityOverviewDTO()
                        .setEndpointId(1L)
                        .setDeclared(List.of("TEXT"))
                        .setSupported(List.of())
                        .setPublishable(List.of()));

        assertCode(() -> service.markReady(9L, 1), AiErrorCodeConstants.AI_MODEL_CAPABILITY_UNSUPPORTED.getCode());
        verify(serviceMapper, never()).updateWithVersion(any(AiServiceDO.class), any());

        // 能力齐备后允许标记
        when(probeService.getCapabilityOverview(1L))
                .thenReturn(new AiModelCapabilityOverviewDTO().setPublishable(List.of("TEXT")));
        when(serviceMapper.updateWithVersion(any(AiServiceDO.class), eq(1))).thenReturn(1);
        service.markReady(9L, 1);
        verify(serviceMapper).updateWithVersion(any(AiServiceDO.class), eq(1));
    }

    @Test
    void bindResourceRejectsUnauthorizedOrInvalidTargets() {
        when(serviceMapper.selectById(9L)).thenReturn(stored(1, 1));
        // 应用级授权缺失 → 越权绑定拒绝
        when(authorizationService.authorize(
                        eq(5L), eq("APP"), eq(""), any(AiResourceType.class), anyString(), any(AiAction.class), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false));

        assertCode(
                () -> service.bindResource(new AiServiceResourceSaveDTO()
                        .setServiceId(9L)
                        .setResourceType("REPORT")
                        .setResourceKey("report-1")
                        .setActions(List.of("READ"))),
                AiErrorCodeConstants.AI_AUTHORIZATION_DENIED.getCode());
        verify(resourceMapper, never()).insert(any(AiServiceResourceDO.class));

        // 目录外资源类型/动作
        assertCode(
                () -> service.bindResource(new AiServiceResourceSaveDTO()
                        .setServiceId(9L)
                        .setResourceType("MAGIC")
                        .setResourceKey("k1")),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.bindResource(new AiServiceResourceSaveDTO()
                        .setServiceId(9L)
                        .setResourceType("REPORT")
                        .setResourceKey("k1")
                        .setActions(List.of("DELETE"))),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
    }

    @Test
    void bindResourceSucceedsWithAuthorizationAndRejectsDuplicates() {
        when(serviceMapper.selectById(9L)).thenReturn(stored(1, 1));
        when(authorizationService.authorize(
                        eq(5L),
                        eq("APP"),
                        eq(""),
                        any(AiResourceType.class),
                        eq("report-1"),
                        any(AiAction.class),
                        any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true));
        when(resourceMapper.selectBinding(9L, null, "REPORT", "report-1")).thenReturn(null);
        when(resourceMapper.insert(any(AiServiceResourceDO.class))).thenAnswer(invocation -> {
            ((AiServiceResourceDO) invocation.getArgument(0)).setId(11L);
            return 1;
        });

        Long bindingId = service.bindResource(new AiServiceResourceSaveDTO()
                .setServiceId(9L)
                .setResourceType("REPORT")
                .setResourceKey("report-1")
                .setActions(List.of("READ", "EXECUTE")));

        assertThat(bindingId).isEqualTo(11L);
        ArgumentCaptor<AiServiceResourceDO> captor = ArgumentCaptor.forClass(AiServiceResourceDO.class);
        verify(resourceMapper).insert(captor.capture());
        assertThat(captor.getValue().getActions()).isEqualTo("READ,EXECUTE");
        assertThat(captor.getValue().getStatus()).isEqualTo(AiServiceResourceDO.STATUS_ACTIVE);

        when(resourceMapper.selectBinding(9L, null, "REPORT", "report-1"))
                .thenReturn(new AiServiceResourceDO().setId(11L));
        assertCode(
                () -> service.bindResource(new AiServiceResourceSaveDTO()
                        .setServiceId(9L)
                        .setResourceType("REPORT")
                        .setResourceKey("report-1")
                        .setActions(List.of("READ"))),
                AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());
    }

    @Test
    void unbindAndDeleteRespectStateAndVersion() {
        when(serviceMapper.selectById(9L)).thenReturn(stored(1, 1));
        when(resourceMapper.selectById(11L))
                .thenReturn(new AiServiceResourceDO()
                        .setId(11L)
                        .setServiceId(9L)
                        .setStatus(AiServiceResourceDO.STATUS_ACTIVE)
                        .setVersion(2));
        when(resourceMapper.updateWithVersion(any(AiServiceResourceDO.class), eq(2)))
                .thenReturn(1);

        service.unbindResource(11L, 2);
        verify(resourceMapper).updateWithVersion(any(AiServiceResourceDO.class), eq(2));

        // 已有绑定不允许直接删除服务
        when(resourceMapper.selectDraftBindings(9L)).thenReturn(List.of(new AiServiceResourceDO().setId(11L)));
        assertCode(() -> service.deleteDraft(9L, 1), AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());

        when(resourceMapper.selectDraftBindings(9L)).thenReturn(List.of());
        when(serviceMapper.updateWithVersion(any(AiServiceDO.class), eq(1))).thenReturn(1);
        service.deleteDraft(9L, 1);
        verify(serviceMapper).deleteById(9L);
    }
}
