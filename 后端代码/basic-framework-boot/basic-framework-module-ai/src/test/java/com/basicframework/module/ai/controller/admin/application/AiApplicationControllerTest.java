package com.basicframework.module.ai.controller.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.application.vo.AiApplicationCredentialIssueRespVO;
import com.basicframework.module.ai.controller.admin.application.vo.AiApplicationPageReqVO;
import com.basicframework.module.ai.controller.admin.application.vo.AiApplicationRespVO;
import com.basicframework.module.ai.controller.admin.application.vo.AiApplicationSaveReqVO;
import com.basicframework.module.ai.controller.admin.application.vo.AiApplicationStatusReqVO;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.domain.application.ApplicationOrigins;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * A01 应用控制面契约：权限码与 V50 种子一致；除创建/轮换外任何响应都不含秘密（AT-011）；
 * appCode 原样透传（服务层负责归一化）。
 */
class AiApplicationControllerTest {

    private final AiApplicationService applicationService = mock(AiApplicationService.class);

    private final AiApplicationController controller = new AiApplicationController(applicationService);

    private static AiApplicationDO application() {
        return new AiApplicationDO()
                .setId(5L)
                .setAppCode("crm-portal")
                .setName("CRM 门户")
                .setDescription("对接 CRM")
                .setOrigins(ApplicationOrigins.normalizeToJson(List.of("https://crm.example.com")))
                .setEnabled(true)
                .setVersion(2);
    }

    @Test
    void createReturnsIssueOnceAndDelegatesNormalizationToService() {
        when(applicationService.createApplication(any()))
                .thenReturn(new AiApplicationCredentialIssueDTO()
                        .setApplication(application())
                        .setCredentialId(11L)
                        .setSecret("aiapp_plaintext-once"));

        AiApplicationSaveReqVO reqVO = new AiApplicationSaveReqVO()
                .setAppCode("crm-portal")
                .setName("CRM 门户")
                .setOrigins(List.of("https://crm.example.com"));
        AiApplicationCredentialIssueRespVO respVO =
                controller.createApplication(reqVO).getData();

        assertThat(respVO.getSecret()).isEqualTo("aiapp_plaintext-once");
        assertThat(respVO.getCredentialId()).isEqualTo(11L);
        assertThat(respVO.getAppCode()).isEqualTo("crm-portal");
        verify(applicationService).createApplication(any());
    }

    @Test
    void detailExposesOnlyConfiguredFlagNeverDigestOrSecret() {
        when(applicationService.getApplication(5L)).thenReturn(application());
        when(applicationService.hasActiveCredential(5L)).thenReturn(true);

        AiApplicationRespVO respVO = controller.getApplication(5L).getData();

        assertThat(respVO.getCredentialConfigured()).isTrue();
        assertThat(respVO.getOrigins()).containsExactly("https://crm.example.com");
        Set<String> fieldNames = Arrays.stream(AiApplicationRespVO.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());
        assertThat(fieldNames)
                .as("响应模型不得出现任何秘密字段")
                .containsExactlyInAnyOrder(
                        "id",
                        "appCode",
                        "name",
                        "description",
                        "origins",
                        "enabled",
                        "credentialConfigured",
                        "version",
                        "createTime");
    }

    @Test
    void pageAndLifecycleCommandsDelegate() {
        when(applicationService.getApplicationPage(any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(application()), 1L));
        AiApplicationPageReqVO pageReqVO = new AiApplicationPageReqVO();
        pageReqVO.setAppCode("crm");

        assertThat(controller.getApplicationPage(pageReqVO).getData().getList()).hasSize(1);
        // 列表不回查凭据状态
        verify(applicationService).getApplicationPage(any(), any(), any());

        AiApplicationStatusReqVO statusReqVO = new AiApplicationStatusReqVO();
        statusReqVO.setId(5L);
        statusReqVO.setEnabled(false);
        statusReqVO.setVersion(2);
        controller.updateStatus(statusReqVO);
        verify(applicationService).updateStatus(5L, 2, false);

        when(applicationService.rotateCredential(5L, 2))
                .thenReturn(new AiApplicationCredentialIssueDTO()
                        .setApplication(application())
                        .setCredentialId(12L)
                        .setSecret("aiapp_rotated-once"));
        assertThat(controller.rotateCredential(5L, 2).getData().getSecret()).isEqualTo("aiapp_rotated-once");
        controller.revokeCredential(5L, 2);
        controller.deleteApplication(5L, 2);
        verify(applicationService).rotateCredential(5L, 2);
        verify(applicationService).revokeCredential(5L, 2);
        verify(applicationService).deleteApplication(5L, 2);
    }

    @Test
    void permissionsMatchMigrationSeeds() throws Exception {
        assertThat(permissionOf("createApplication", AiApplicationSaveReqVO.class))
                .isEqualTo("ai:application:create");
        assertThat(permissionOf("updateApplication", AiApplicationSaveReqVO.class))
                .isEqualTo("ai:application:update");
        assertThat(permissionOf("updateStatus", AiApplicationStatusReqVO.class)).isEqualTo("ai:application:update");
        assertThat(permissionOf("getApplication", Long.class)).isEqualTo("ai:application:query");
        assertThat(permissionOf("getApplicationPage", AiApplicationPageReqVO.class))
                .isEqualTo("ai:application:query");
        assertThat(permissionOf("rotateCredential", Long.class, Integer.class)).isEqualTo("ai:application:rotate");
        assertThat(permissionOf("revokeCredential", Long.class, Integer.class)).isEqualTo("ai:application:revoke");
        assertThat(permissionOf("deleteApplication", Long.class, Integer.class)).isEqualTo("ai:application:delete");
    }

    private static String permissionOf(String methodName, Class<?>... parameterTypes) throws Exception {
        PreAuthorize annotation = AiApplicationController.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("%s 必须声明服务端权限表达式", methodName).isNotNull();
        return annotation
                .value()
                .replace("@ss.hasPermission(", "")
                .replace(")", "")
                .replace("'", "");
    }
}
