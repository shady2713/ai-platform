package com.basicframework.module.ai.controller.app.v1.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.conversation.vo.AiConversationBindReleaseReqVO;
import com.basicframework.module.ai.controller.app.v1.conversation.vo.AiConversationBindReqVO;
import com.basicframework.module.ai.controller.app.v1.conversation.vo.AiConversationCreateReqVO;
import com.basicframework.module.ai.controller.app.v1.conversation.vo.AiConversationMessageReqVO;
import com.basicframework.module.ai.controller.app.v1.conversation.vo.AiConversationMessageRespVO;
import com.basicframework.module.ai.controller.app.v1.conversation.vo.AiConversationPageReqVO;
import com.basicframework.module.ai.controller.app.v1.conversation.vo.AiConversationRenameReqVO;
import com.basicframework.module.ai.controller.app.v1.conversation.vo.AiConversationRespVO;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationDO;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationMessageDO;
import com.basicframework.module.ai.service.conversation.AiConversationService;
import com.basicframework.module.ai.service.conversation.dto.AiConversationCreateDTO;
import com.basicframework.module.ai.service.conversation.dto.AiConversationMessageSaveDTO;
import jakarta.annotation.security.PermitAll;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/** O01 会话控制面契约：每个端点有且只有一种鉴权策略，映射不回显归属之外的字段。 */
class AiConversationControllerTest {

    private final AiConversationService conversationService = mock(AiConversationService.class);

    private final AiConversationController controller = new AiConversationController(conversationService);

    private static AiConversationDO conversation() {
        return new AiConversationDO()
                .setId(31L)
                .setConversationKey("conv_order_qa")
                .setTitle("订单问答")
                .setServiceId(9L)
                .setReleaseId(21L)
                .setBusinessContext("{\"page\":\"order\"}")
                .setMessageCount(2)
                .setStatus(AiConversationDO.STATUS_ACTIVE)
                .setVersion(3);
    }

    @Test
    void everyEndpointDeclaresExactlyOneAuthenticationStrategy() throws Exception {
        for (Method method : AiConversationController.class.getDeclaredMethods()) {
            if (method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class) == null
                    && method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class) == null
                    && method.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class) == null
                    && method.getAnnotation(org.springframework.web.bind.annotation.DeleteMapping.class) == null) {
                continue;
            }
            int strategies = 0;
            strategies += method.isAnnotationPresent(AuthenticatedOnly.class) ? 1 : 0;
            strategies += method.isAnnotationPresent(PermitAll.class) ? 1 : 0;
            strategies += method.isAnnotationPresent(PreAuthorize.class) ? 1 : 0;
            assertThat(strategies).as("%s 必须且只能声明一种鉴权策略", method.getName()).isEqualTo(1);
            assertThat(method.isAnnotationPresent(AuthenticatedOnly.class))
                    .as("%s 是应用端会话接口：归属在服务层按主体判定", method.getName())
                    .isTrue();
        }
    }

    @Test
    void createAndQueryDelegateWithSubjectScopedPayload() {
        when(conversationService.create(any())).thenReturn(31L);
        assertThat(controller
                        .create(new AiConversationCreateReqVO()
                                .setConversationKey("conv_order_qa")
                                .setTitle("订单问答")
                                .setServiceId(9L)
                                .setBusinessContext("{\"page\":\"order\"}"))
                        .getData())
                .isEqualTo(31L);
        verify(conversationService)
                .create(new AiConversationCreateDTO()
                        .setConversationKey("conv_order_qa")
                        .setTitle("订单问答")
                        .setServiceId(9L)
                        .setBusinessContext("{\"page\":\"order\"}"));

        when(conversationService.getPage(any())).thenReturn(new PageResult<>(List.of(conversation()), 1L));
        List<AiConversationRespVO> list =
                controller.page(new AiConversationPageReqVO()).getData().getList();
        assertThat(list).singleElement().satisfies(item -> {
            assertThat(item.getConversationKey()).isEqualTo("conv_order_qa");
            assertThat(item.getReleaseId()).isEqualTo(21L);
            assertThat(item.getStatus()).isEqualTo(AiConversationDO.STATUS_ACTIVE);
            assertThat(item.getVersion()).isEqualTo(3);
        });

        when(conversationService.getConversation(31L)).thenReturn(conversation());
        assertThat(controller.get(31L).getData().getMessageCount()).isEqualTo(2);
    }

    @Test
    void mutationsDelegateWithOptimisticLockVersions() {
        controller.rename(
                new AiConversationRenameReqVO().setId(31L).setTitle("新标题").setVersion(3));
        verify(conversationService).rename(31L, "新标题", 3);

        controller.delete(31L, 3);
        verify(conversationService).delete(31L, 3);

        controller.bindService(
                new AiConversationBindReqVO().setId(31L).setServiceId(9L).setVersion(3));
        verify(conversationService).bindService(31L, 9L, 3);

        controller.bindRelease(new AiConversationBindReleaseReqVO()
                .setId(31L)
                .setReleaseId(21L)
                .setVersion(4));
        verify(conversationService).bindRelease(31L, 21L, 4);
    }

    @Test
    void messagesMapSequenceAndHashWithoutExtraFields() {
        when(conversationService.appendMessage(any())).thenReturn(77L);
        assertThat(controller
                        .appendMessage(new AiConversationMessageReqVO()
                                .setConversationId(31L)
                                .setRole("user")
                                .setContent("帮我查订单"))
                        .getData())
                .isEqualTo(77L);
        verify(conversationService)
                .appendMessage(new AiConversationMessageSaveDTO()
                        .setConversationId(31L)
                        .setRole("user")
                        .setContent("帮我查订单"));

        when(conversationService.listMessages(31L, 0, 20))
                .thenReturn(List.of(new AiConversationMessageDO()
                        .setId(77L)
                        .setConversationId(31L)
                        .setSequenceNo(1)
                        .setRole("user")
                        .setContent("帮我查订单")
                        .setContentHash("b".repeat(64))));
        List<AiConversationMessageRespVO> messages =
                controller.messages(31L, 0, 20).getData();
        assertThat(messages).singleElement().satisfies(item -> {
            assertThat(item.getSequenceNo()).isEqualTo(1);
            assertThat(item.getContentHash()).hasSize(64);
        });
    }

    @Test
    void pageRequestCarriesNoOwnershipFields() {
        assertThat(PageParam.class.isAssignableFrom(AiConversationPageReqVO.class))
                .isTrue();
        assertThat(AiConversationPageReqVO.class.getDeclaredFields())
                .as("归属只来自服务端身份：分页请求不允许携带应用/主体条件")
                .isEmpty();
    }
}
