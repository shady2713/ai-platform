package com.basicframework.module.ai.service.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationDO;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationMessageDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.mysql.conversation.AiConversationMapper;
import com.basicframework.module.ai.dal.mysql.conversation.AiConversationMessageMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.runtime.AiRunSnapshot;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.conversation.dto.AiConversationCreateDTO;
import com.basicframework.module.ai.service.conversation.dto.AiConversationMessageSaveDTO;
import com.basicframework.module.ai.service.conversation.dto.AiConversationRunContextDTO;
import com.basicframework.module.ai.service.serviceconfig.AiServiceReleaseService;
import com.basicframework.module.ai.service.serviceconfig.AiServiceService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** O01 会话与消息：主体归属、删除先关闭访问、分页稳定与旧上下文的重新鉴权。 */
class AiConversationServiceImplTest {

    private static final Long APP_ID = 5L;

    private static final String EXTERNAL_USER = "u-1001";

    private static final Long CONVERSATION_ID = 31L;

    private static final Long SERVICE_ID = 9L;

    private static final Long RELEASE_ID = 21L;

    private final AiConversationMapper conversationMapper = mock(AiConversationMapper.class);

    private final AiConversationMessageMapper messageMapper = mock(AiConversationMessageMapper.class);

    private final AiConversationSubjectResolver subjectResolver = mock(AiConversationSubjectResolver.class);

    private final AiServiceService serviceService = mock(AiServiceService.class);

    private final AiServiceReleaseService releaseService = mock(AiServiceReleaseService.class);

    private final AiConversationServiceImpl service = new AiConversationServiceImpl(
            conversationMapper, messageMapper, subjectResolver, serviceService, releaseService);

    @BeforeEach
    void setUp() {
        when(subjectResolver.resolveCurrent())
                .thenReturn(Optional.of(new AiConversationSubject(APP_ID, AiSubjectType.USER, EXTERNAL_USER)));
        when(serviceService.getService(SERVICE_ID))
                .thenReturn(new AiServiceDO().setId(SERVICE_ID).setAppId(APP_ID));
        when(conversationMapper.updateWithVersion(any(), anyInt())).thenReturn(1);
    }

    private static AiConversationDO conversation() {
        return new AiConversationDO()
                .setId(CONVERSATION_ID)
                .setApplicationId(APP_ID)
                .setSubjectType(AiSubjectType.USER.name())
                .setExternalUserId(EXTERNAL_USER)
                .setConversationKey("conv_order_qa")
                .setTitle("订单问答")
                .setServiceId(SERVICE_ID)
                .setBusinessContext("{}")
                .setMessageCount(0)
                .setStatus(AiConversationDO.STATUS_ACTIVE)
                .setVersion(2);
    }

    private static AiServiceReleaseDO release(String status) {
        return new AiServiceReleaseDO()
                .setId(RELEASE_ID)
                .setServiceId(SERVICE_ID)
                .setReleaseVersion(2)
                .setModelEndpointId(1L)
                .setEndpointConfigRevision(3)
                .setContentHash("a".repeat(64))
                .setStatus(status)
                .setVersion(1);
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void createUsesServerSideSubjectAndRejectsDuplicateKey() {
        when(conversationMapper.selectByKey(any(), any(), any(), any())).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
                    ((AiConversationDO) invocation.getArgument(0)).setId(CONVERSATION_ID);
                    return 1;
                })
                .when(conversationMapper)
                .insert(any(AiConversationDO.class));

        Long id = service.create(new AiConversationCreateDTO()
                .setConversationKey("conv_order_qa")
                .setTitle("订单问答")
                .setServiceId(SERVICE_ID)
                .setBusinessContext("{\"page\":\"order\"}"));

        assertThat(id).isEqualTo(CONVERSATION_ID);
        ArgumentCaptor<AiConversationDO> captor = ArgumentCaptor.forClass(AiConversationDO.class);
        verify(conversationMapper).insert(captor.capture());
        AiConversationDO created = captor.getValue();
        assertThat(created.getApplicationId()).as("归属来自服务端身份，不接受请求体自报").isEqualTo(APP_ID);
        assertThat(created.getSubjectType()).isEqualTo("USER");
        assertThat(created.getExternalUserId()).isEqualTo(EXTERNAL_USER);
        assertThat(created.getBusinessContext()).isEqualTo("{\"page\":\"order\"}");
        assertThat(created.getStatus()).isEqualTo(AiConversationDO.STATUS_ACTIVE);

        when(conversationMapper.selectByKey(any(), any(), any(), any())).thenReturn(conversation());
        assertThatThrownBy(() -> service.create(new AiConversationCreateDTO().setConversationKey("conv_order_qa")))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONVERSATION_KEY_DUPLICATE));

        assertThatThrownBy(() -> service.create(new AiConversationCreateDTO().setConversationKey("order-qa")))
                .as("业务键必须带 conv_ 前缀")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
    }

    @Test
    void createRejectsServiceOfAnotherApplication() {
        when(conversationMapper.selectByKey(any(), any(), any(), any())).thenReturn(null);
        when(serviceService.getService(SERVICE_ID))
                .thenReturn(new AiServiceDO().setId(SERVICE_ID).setAppId(77L));

        assertThatThrownBy(() -> service.create(new AiConversationCreateDTO()
                        .setConversationKey("conv_order_qa")
                        .setServiceId(SERVICE_ID)))
                .as("会话不能绑定其它应用的服务")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
        verify(conversationMapper, never()).insert(any(AiConversationDO.class));
    }

    @Test
    void crossSubjectAccessIsIndistinguishableFromMissing() {
        when(conversationMapper.selectOwned(CONVERSATION_ID, APP_ID, "USER", EXTERNAL_USER))
                .thenReturn(null);

        assertThatThrownBy(() -> service.getConversation(CONVERSATION_ID))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.rename(CONVERSATION_ID, "新标题", 2))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.listMessages(CONVERSATION_ID, null, 10))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
        verify(conversationMapper, never()).updateWithVersion(any(), anyInt());
    }

    @Test
    void deleteClosesAccessBeforePurgingContent() {
        when(conversationMapper.selectOwned(CONVERSATION_ID, APP_ID, "USER", EXTERNAL_USER))
                .thenReturn(conversation());

        service.delete(CONVERSATION_ID, 2);

        ArgumentCaptor<AiConversationDO> captor = ArgumentCaptor.forClass(AiConversationDO.class);
        verify(conversationMapper).updateWithVersion(captor.capture(), eq(2));
        assertThat(captor.getValue().getStatus()).as("先关闭访问：状态立即置 DELETED").isEqualTo(AiConversationDO.STATUS_DELETED);
        verify(messageMapper).releaseAll(CONVERSATION_ID);
        verify(conversationMapper).deleteById(CONVERSATION_ID);

        // 关闭访问后：读取按"不存在"处理（逻辑删除行不再命中）
        when(conversationMapper.selectOwned(CONVERSATION_ID, APP_ID, "USER", EXTERNAL_USER))
                .thenReturn(null);
        assertThatThrownBy(() -> service.getConversation(CONVERSATION_ID))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
    }

    @Test
    void renameAndDeleteUseOptimisticLock() {
        when(conversationMapper.selectOwned(CONVERSATION_ID, APP_ID, "USER", EXTERNAL_USER))
                .thenReturn(conversation());
        when(conversationMapper.updateWithVersion(any(), anyInt())).thenReturn(0);

        assertThatThrownBy(() -> service.rename(CONVERSATION_ID, "新标题", 2))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));
        assertThatThrownBy(() -> service.delete(CONVERSATION_ID, 2))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));
        verify(messageMapper, never()).releaseAll(any());
    }

    @Test
    void bindingServiceAndReleaseFollowsThePinnedVersionRule() {
        when(conversationMapper.selectOwned(CONVERSATION_ID, APP_ID, "USER", EXTERNAL_USER))
                .thenReturn(conversation());
        service.bindService(CONVERSATION_ID, SERVICE_ID, 2);
        verify(conversationMapper).updateWithVersion(any(), eq(2));

        // 已固定发布版本：不能悄悄换服务
        AiConversationDO pinned = conversation().setReleaseId(RELEASE_ID);
        when(conversationMapper.selectOwned(CONVERSATION_ID, APP_ID, "USER", EXTERNAL_USER))
                .thenReturn(pinned);
        assertThatThrownBy(() -> service.bindService(CONVERSATION_ID, SERVICE_ID, 2))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));
        assertThatThrownBy(() -> service.bindRelease(CONVERSATION_ID, RELEASE_ID, 2))
                .as("会话版本只固定一次")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));
    }

    @Test
    void bindReleaseRequiresPublishedReleaseOfTheBoundService() {
        when(conversationMapper.selectOwned(CONVERSATION_ID, APP_ID, "USER", EXTERNAL_USER))
                .thenReturn(conversation());
        when(releaseService.listReleases(SERVICE_ID)).thenReturn(List.of(release("CANDIDATE")));
        assertThatThrownBy(() -> service.bindRelease(CONVERSATION_ID, RELEASE_ID, 2))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_RELEASE_NOT_PUBLISHED));

        when(releaseService.listReleases(SERVICE_ID)).thenReturn(List.of(release("RETIRED")));
        service.bindRelease(CONVERSATION_ID, RELEASE_ID, 2);
        ArgumentCaptor<AiConversationDO> captor = ArgumentCaptor.forClass(AiConversationDO.class);
        verify(conversationMapper).updateWithVersion(captor.capture(), eq(2));
        assertThat(captor.getValue().getReleaseId()).isEqualTo(RELEASE_ID);

        when(releaseService.listReleases(SERVICE_ID)).thenReturn(List.of());
        assertThatThrownBy(() -> service.bindRelease(CONVERSATION_ID, 999L, 2))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
    }

    @Test
    void appendMessageAssignsSequenceAndBumpsConversation() {
        when(conversationMapper.selectOwned(CONVERSATION_ID, APP_ID, "USER", EXTERNAL_USER))
                .thenReturn(conversation());
        when(messageMapper.selectMaxSequence(CONVERSATION_ID)).thenReturn(4);
        org.mockito.Mockito.doAnswer(invocation -> {
                    ((AiConversationMessageDO) invocation.getArgument(0)).setId(77L);
                    return 1;
                })
                .when(messageMapper)
                .insert(any(AiConversationMessageDO.class));

        Long messageId = service.appendMessage(new AiConversationMessageSaveDTO()
                .setConversationId(CONVERSATION_ID)
                .setRole("user")
                .setContent("帮我查订单"));

        assertThat(messageId).isEqualTo(77L);
        ArgumentCaptor<AiConversationMessageDO> captor = ArgumentCaptor.forClass(AiConversationMessageDO.class);
        verify(messageMapper).insert(captor.capture());
        AiConversationMessageDO message = captor.getValue();
        assertThat(message.getSequenceNo()).as("序号在会话内递增").isEqualTo(5);
        assertThat(message.getContentHash()).as("只保存摘要，摘要不等于正文").hasSize(64).isNotEqualTo("帮我查订单");
        assertThat(message.getApplicationId()).isEqualTo(APP_ID);
        assertThat(message.getSubjectType()).isEqualTo("USER");
        assertThat(message.getExternalUserId()).isEqualTo(EXTERNAL_USER);

        ArgumentCaptor<AiConversationDO> conversationCaptor = ArgumentCaptor.forClass(AiConversationDO.class);
        verify(conversationMapper).updateWithVersion(conversationCaptor.capture(), eq(2));
        assertThat(conversationCaptor.getValue().getMessageCount()).isEqualTo(1);
        assertThat(conversationCaptor.getValue().getLastMessageTime()).isNotNull();
    }

    @Test
    void appendMessageRejectsInvalidInputAndLockConflicts() {
        when(conversationMapper.selectOwned(CONVERSATION_ID, APP_ID, "USER", EXTERNAL_USER))
                .thenReturn(conversation());

        assertThatThrownBy(() -> service.appendMessage(new AiConversationMessageSaveDTO()
                        .setConversationId(CONVERSATION_ID)
                        .setRole("root")
                        .setContent("x")))
                .as("角色必须在稳定词表内")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> service.appendMessage(new AiConversationMessageSaveDTO()
                        .setConversationId(CONVERSATION_ID)
                        .setRole("user")
                        .setContent("x".repeat(16_001))))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));

        when(messageMapper.selectMaxSequence(CONVERSATION_ID)).thenReturn(null);
        when(conversationMapper.updateWithVersion(any(), anyInt())).thenReturn(0);
        assertThatThrownBy(() -> service.appendMessage(new AiConversationMessageSaveDTO()
                        .setConversationId(CONVERSATION_ID)
                        .setRole("user")
                        .setContent("并发写入")))
                .as("会话行 CAS 失败时整个事务回滚，消息不会留下")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));
    }

    @Test
    void listMessagesBoundsLimitAndAdvancesBySequence() {
        when(conversationMapper.selectOwned(CONVERSATION_ID, APP_ID, "USER", EXTERNAL_USER))
                .thenReturn(conversation());
        when(messageMapper.selectBySequence(CONVERSATION_ID, 3, 100)).thenReturn(List.of());

        service.listMessages(CONVERSATION_ID, 3, 1_000);

        assertThatThrownBy(() -> service.listMessages(CONVERSATION_ID, 3, 0))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
    }

    @Test
    void loadRunContextReauthorizesThePinnedReleaseBeforeReturningHistory() {
        when(conversationMapper.selectOwned(CONVERSATION_ID, APP_ID, "USER", EXTERNAL_USER))
                .thenReturn(conversation().setReleaseId(RELEASE_ID));
        AiServiceReleaseDO published = release("ACTIVE");
        when(releaseService.listReleases(SERVICE_ID)).thenReturn(List.of(published));
        AiConversationMessageDO message = new AiConversationMessageDO()
                .setId(77L)
                .setConversationId(CONVERSATION_ID)
                .setSequenceNo(1)
                .setRole("user")
                .setStatus(AiConversationMessageDO.STATUS_ACTIVE);
        when(releaseService.listReleaseBindings(RELEASE_ID)).thenReturn(List.of());
        when(messageMapper.selectBySequence(CONVERSATION_ID, null, 20)).thenReturn(List.of(message));

        AiConversationRunContextDTO context = service.loadRunContext(CONVERSATION_ID, null);

        ArgumentCaptor<AiRunSnapshot> pinCaptor = ArgumentCaptor.forClass(AiRunSnapshot.class);
        verify(releaseService).resolvePinnedRun(pinCaptor.capture());
        assertThat(pinCaptor.getValue().getReleaseId()).isEqualTo(RELEASE_ID);
        assertThat(pinCaptor.getValue().getContentHash()).isEqualTo(published.getContentHash());
        assertThat(context.getPin().getReleaseId()).isEqualTo(RELEASE_ID);
        assertThat(context.getHistory()).containsExactly(message);

        // 当前授权失效时：旧上下文不得进入新运行
        when(releaseService.resolvePinnedRun(any()))
                .thenThrow(com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception(
                        AiErrorCodeConstants.AI_AUTHORIZATION_DENIED));
        assertThatThrownBy(() -> service.loadRunContext(CONVERSATION_ID, null))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_AUTHORIZATION_DENIED));
    }

    @Test
    void pageIsScopedToTheCurrentSubject() {
        PageResult<AiConversationDO> expected = new PageResult<>(List.of(conversation()), 1L);
        when(conversationMapper.selectPageBySubject(any(), eq(APP_ID), eq("USER"), eq(EXTERNAL_USER)))
                .thenReturn(expected);

        assertThat(service.getPage(new PageParam())).isEqualTo(expected);
    }

    @Test
    void rejectsUntrustedSessionWithoutSubject() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getConversation(CONVERSATION_ID))
                .as("无可信身份时不区分越权与不存在")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.create(new AiConversationCreateDTO().setConversationKey("conv_order_qa")))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
    }
}
