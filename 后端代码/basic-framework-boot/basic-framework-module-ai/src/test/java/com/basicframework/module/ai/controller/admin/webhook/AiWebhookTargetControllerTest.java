package com.basicframework.module.ai.controller.admin.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookTargetPageReqVO;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookTargetRespVO;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookTargetRotateSecretReqVO;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookTargetSaveReqVO;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookTargetStatusReqVO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookTargetDO;
import com.basicframework.module.ai.service.webhook.AiWebhookTargetService;
import com.basicframework.module.ai.service.webhook.dto.AiWebhookTargetSaveDTO;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/** Webhook 目标控制面契约（X10）：权限码与 V88 种子一致、响应只给"是否已配置密钥"。 */
class AiWebhookTargetControllerTest {

    private static final String SECRET = "s3cret-signing-key-0123456789";

    private final AiWebhookTargetService targetService = mock(AiWebhookTargetService.class);

    private final AiWebhookTargetController controller = new AiWebhookTargetController(targetService);

    @Test
    void everyEndpointDeclaresExactlyOnePermissionPolicy() throws Exception {
        for (Method method : AiWebhookTargetController.class.getDeclaredMethods()) {
            if (method.getAnnotation(PostMapping.class) == null
                    && method.getAnnotation(PutMapping.class) == null
                    && method.getAnnotation(DeleteMapping.class) == null
                    && method.getAnnotation(GetMapping.class) == null) {
                continue;
            }
            String permission = permissionOf(method.getName(), method.getParameterTypes());
            assertThat(permission)
                    .as("%s 的权限码必须在 V88 种子里存在", method.getName())
                    .isIn(
                            "ai:webhook:query",
                            "ai:webhook:manage",
                            "ai:webhook:rotate",
                            "ai:webhook:delete",
                            "ai:webhook:redeliver");
        }
    }

    @Test
    void managementEndpointsRequireTheirOwnPermissions() throws Exception {
        assertThat(permissionOf("createTarget", AiWebhookTargetSaveReqVO.class)).isEqualTo("ai:webhook:manage");
        assertThat(permissionOf("updateTarget", AiWebhookTargetSaveReqVO.class)).isEqualTo("ai:webhook:manage");
        assertThat(permissionOf("updateStatus", AiWebhookTargetStatusReqVO.class))
                .isEqualTo("ai:webhook:manage");
        assertThat(permissionOf("rotateSecret", AiWebhookTargetRotateSecretReqVO.class))
                .isEqualTo("ai:webhook:rotate");
        assertThat(permissionOf("deleteTarget", Long.class, Integer.class)).isEqualTo("ai:webhook:delete");
        assertThat(permissionOf("getTarget", Long.class)).isEqualTo("ai:webhook:query");
        assertThat(permissionOf("getTargetPage", AiWebhookTargetPageReqVO.class))
                .isEqualTo("ai:webhook:query");
    }

    @Test
    void createAndUpdatePassTheSecretOnlyThroughTheServiceDto() {
        when(targetService.create(any())).thenReturn(91L);
        CommonResult<Long> created = controller.createTarget(new AiWebhookTargetSaveReqVO()
                .setApplicationId(7L)
                .setCode("erp-callback")
                .setName("ERP 回调")
                .setTargetUrl("https://erp.example.com/hook")
                .setEventTypes(List.of("RUN.SUCCEEDED"))
                .setSecret(SECRET));

        assertThat(created.getData()).isEqualTo(91L);
        ArgumentCaptor<AiWebhookTargetSaveDTO> captor = ArgumentCaptor.forClass(AiWebhookTargetSaveDTO.class);
        verify(targetService).create(captor.capture());
        assertThat(captor.getValue().getApplicationId()).isEqualTo(7L);
        assertThat(captor.getValue().getEventTypes()).containsExactly("RUN.SUCCEEDED");
        // DTO 的 toString 不带密钥（密钥只作为写入入参存在）
        assertThat(captor.getValue().toString()).doesNotContain(SECRET);

        controller.updateTarget(new AiWebhookTargetSaveReqVO()
                .setId(91L)
                .setName("ERP 回调")
                .setTargetUrl("https://erp.example.com/hook")
                .setEventTypes(List.of("RUN.SUCCEEDED"))
                .setVersion(1));
        verify(targetService).update(any(AiWebhookTargetSaveDTO.class));

        controller.updateStatus(
                new AiWebhookTargetStatusReqVO().setId(91L).setEnabled(false).setVersion(2));
        verify(targetService).updateStatus(91L, 2, false);

        controller.rotateSecret(new AiWebhookTargetRotateSecretReqVO()
                .setId(91L)
                .setSecret(SECRET)
                .setVersion(3));
        verify(targetService).rotateSecret(91L, 3, SECRET);

        controller.deleteTarget(91L, 4);
        verify(targetService).delete(91L, 4);
    }

    @Test
    void responseNeverExposesSecretMaterial() throws Exception {
        when(targetService.get(91L)).thenReturn(target());

        CommonResult<AiWebhookTargetRespVO> result = controller.getTarget(91L);

        AiWebhookTargetRespVO vo = result.getData();
        assertThat(vo.getSecretConfigured()).isTrue();
        assertThat(vo.getSecretRevision()).isEqualTo(2);
        assertThat(vo.getEventTypes()).containsExactly("RUN.SUCCEEDED", "RUN.FAILED");
        assertThat(vo.toString()).doesNotContain(SECRET).doesNotContain("v1.");
        assertThat(Arrays.stream(AiWebhookTargetRespVO.class.getDeclaredFields())
                        .map(Field::getName)
                        .toList())
                .noneMatch(name -> name.toLowerCase().contains("ciphertext"));
    }

    @Test
    void pageMapsRowsAndKeepsTheTotal() {
        when(targetService.getPage(any(), eq(7L), eq("erp"), eq("ENABLED")))
                .thenReturn(new PageResult<>(List.of(target()), 1L));

        AiWebhookTargetPageReqVO pageReqVO = new AiWebhookTargetPageReqVO();
        pageReqVO.setApplicationId(7L);
        pageReqVO.setCode("erp");
        pageReqVO.setStatus("ENABLED");

        PageResult<AiWebhookTargetRespVO> page =
                controller.getTargetPage(pageReqVO).getData();

        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getList()).hasSize(1);
        assertThat(page.getList().get(0).getCode()).isEqualTo("erp-callback");
    }

    private static AiWebhookTargetDO target() {
        return new AiWebhookTargetDO()
                .setId(91L)
                .setApplicationId(7L)
                .setCode("erp-callback")
                .setName("ERP 回调")
                .setTargetUrl("https://erp.example.com/hook")
                .setEventTypes("[\"RUN.SUCCEEDED\",\"RUN.FAILED\"]")
                .setSecretCiphertext("v1.a.b")
                .setSecretRevision(2)
                .setStatus(AiWebhookTargetDO.STATUS_ENABLED)
                .setMaxAttempts(3)
                .setVersion(3);
    }

    private static String permissionOf(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = AiWebhookTargetController.class.getMethod(methodName, parameterTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("%s 必须声明服务端权限表达式", methodName).isNotNull();
        return annotation
                .value()
                .replace("@ss.hasPermission(", "")
                .replace(")", "")
                .replace("'", "");
    }
}
