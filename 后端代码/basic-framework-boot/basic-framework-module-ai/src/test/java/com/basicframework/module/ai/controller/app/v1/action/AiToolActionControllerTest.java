package com.basicframework.module.ai.controller.app.v1.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.action.vo.AiToolActionConfirmReqVO;
import com.basicframework.module.ai.controller.app.v1.action.vo.AiToolActionPageReqVO;
import com.basicframework.module.ai.controller.app.v1.action.vo.AiToolActionRespVO;
import com.basicframework.module.ai.dal.dataobject.action.AiToolActionDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.tool.action.AiToolActionService;
import jakarta.annotation.security.PermitAll;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * D09 应用端工具动作控制面契约（D11 补齐：该文件此前未登记覆盖率基线）。
 *
 * <p>两条不变量：（1）每个端点有且只有一种鉴权策略（{@code @AuthenticatedOnly}）；
 * （2）归属只来自服务端会话解析出的主体，请求体不能自报主体——控制器只把
 * 应用/主体类型/外部用户标识原样交给服务层。
 */
class AiToolActionControllerTest {

    private static final AiConversationSubject SUBJECT = new AiConversationSubject(7L, AiSubjectType.USER, "ext-9");

    private final AiToolActionService actionService = mock(AiToolActionService.class);

    private final AiConversationSubjectResolver subjectResolver = mock(AiConversationSubjectResolver.class);

    private final AiToolActionController controller = new AiToolActionController(actionService, subjectResolver);

    private static AiToolActionDO action() {
        return new AiToolActionDO()
                .setId(41L)
                .setRunId(11L)
                .setToolId(21L)
                .setToolVersionId(31L)
                .setApplicationId(7L)
                .setSubjectType("USER")
                .setExternalUserId("ext-9")
                .setChallenge("challenge-abc")
                .setStatus(AiToolActionDO.STATUS_CONFIRMED)
                .setExpiresAt(LocalDateTime.of(2026, 9, 22, 12, 0))
                .setDecidedAt(LocalDateTime.of(2026, 9, 22, 11, 59))
                .setExecutedAt(LocalDateTime.of(2026, 9, 22, 12, 1))
                .setResultCode("OK")
                .setVersion(2);
    }

    private void withSubject() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.of(SUBJECT));
    }

    @Test
    void everyEndpointDeclaresExactlyOneAuthenticationStrategy() {
        for (Method method : AiToolActionController.class.getDeclaredMethods()) {
            if (method.getAnnotation(GetMapping.class) == null && method.getAnnotation(PostMapping.class) == null) {
                continue;
            }
            int strategies = 0;
            strategies += method.getAnnotation(AuthenticatedOnly.class) == null ? 0 : 1;
            strategies += method.getAnnotation(PermitAll.class) == null ? 0 : 1;
            strategies += method.getAnnotation(PreAuthorize.class) == null ? 0 : 1;
            assertThat(strategies).as("%s 必须且只能声明一种鉴权策略", method.getName()).isEqualTo(1);
            assertThat(method.getAnnotation(AuthenticatedOnly.class))
                    .as("%s 是应用端端点，只能走 @AuthenticatedOnly", method.getName())
                    .isNotNull();
        }
    }

    @Test
    void confirmAndRejectPassTheServerResolvedSubjectToTheService() {
        withSubject();
        when(actionService.confirm(eq(41L), eq(7L), eq("USER"), eq("ext-9"), eq("challenge-abc"), any()))
                .thenReturn(action());
        when(actionService.reject(41L, 7L, "USER", "ext-9", "challenge-abc")).thenReturn(action());

        AiToolActionConfirmReqVO reqVO = new AiToolActionConfirmReqVO()
                .setActionId(41L)
                .setChallenge("challenge-abc")
                .setArguments(Map.of("orderId", "A-1"));
        AiToolActionRespVO confirmed = controller.confirm(reqVO).getData();
        AiToolActionRespVO rejected = controller.reject(reqVO).getData();

        assertThat(confirmed.getId()).isEqualTo(41L);
        assertThat(confirmed.getStatus()).isEqualTo(AiToolActionDO.STATUS_CONFIRMED);
        assertThat(confirmed.getExecutedAt()).isNotNull();
        assertThat(confirmed.getResultCode()).isEqualTo("OK");
        assertThat(rejected.getId()).isEqualTo(41L);
        verify(actionService).confirm(41L, 7L, "USER", "ext-9", "challenge-abc", Map.of("orderId", "A-1"));
        verify(actionService).reject(41L, 7L, "USER", "ext-9", "challenge-abc");
    }

    @Test
    void executeGetAndPageAlwaysScopeByTheResolvedSubject() {
        withSubject();
        when(actionService.execute(41L, 7L, "USER", "ext-9")).thenReturn(action());
        when(actionService.getAction(41L, 7L, "USER", "ext-9")).thenReturn(action());
        when(actionService.getActionPage(eq(7L), eq("USER"), eq("ext-9"), eq(11L), any()))
                .thenReturn(new PageResult<>(List.of(action()), 1L));

        assertThat(controller.execute(41L).getData().getToolVersionId()).isEqualTo(31L);
        assertThat(controller.get(41L).getData().getRunId()).isEqualTo(11L);
        PageResult<AiToolActionRespVO> page =
                controller.page(new AiToolActionPageReqVO().setRunId(11L)).getData();

        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getList()).hasSize(1);
        assertThat(page.getList().get(0).getStatus()).isEqualTo(AiToolActionDO.STATUS_CONFIRMED);
        assertThat(Arrays.stream(AiToolActionRespVO.class.getDeclaredFields())
                        .map(Field::getName)
                        .toList())
                .as("响应不得带出一次性挑战（秘密不进协议层）")
                .doesNotContain("challenge");
        verify(actionService).getActionPage(eq(7L), eq("USER"), eq("ext-9"), eq(11L), any());
    }

    @Test
    void missingSessionSubjectIsDeniedBeforeAnyServiceCall() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get(41L))
                .isInstanceOf(ServiceException.class)
                .satisfies(throwable -> assertThat(((ServiceException) throwable).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_ACCESS_DENIED.getCode()));
    }
}
