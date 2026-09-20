package com.basicframework.module.ai.controller.admin.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.dataset.vo.AiDatasetPageReqVO;
import com.basicframework.module.ai.controller.admin.dataset.vo.AiDatasetRespVO;
import com.basicframework.module.ai.controller.admin.dataset.vo.AiDatasetSaveReqVO;
import com.basicframework.module.ai.controller.admin.dataset.vo.AiDatasetVersionRespVO;
import com.basicframework.module.ai.controller.admin.dataset.vo.AiDatasetVersionSaveReqVO;
import com.basicframework.module.ai.controller.admin.dataset.vo.AiDatasetVersionVerifyReqVO;
import com.basicframework.module.ai.controller.admin.dataset.vo.AiDatasetVersionVerifyRespVO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetVersionDO;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetVersionVerifyResultDTO;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/** D04 数据集控制面契约：权限码与 V68 种子一致、响应不含上游数据与秘密。 */
class AiDatasetControllerTest {

    private final AiDatasetService datasetService = mock(AiDatasetService.class);

    private final AiDatasetController controller = new AiDatasetController(datasetService);

    private static AiDatasetDO dataset() {
        return new AiDatasetDO()
                .setId(81L)
                .setCode("crm-orders")
                .setName("CRM 订单")
                .setConnectorId(71L)
                .setSourceObject("crm.orders")
                .setStatus(AiDatasetDO.STATUS_ENABLED)
                .setLatestVersionNo(2)
                .setPublishedVersionNo(1)
                .setVersion(3);
    }

    private static AiDatasetVersionDO version() {
        return new AiDatasetVersionDO()
                .setId(91L)
                .setDatasetId(81L)
                .setVersionNo(1)
                .setStatus(AiDatasetVersionDO.STATUS_PUBLISHED)
                .setDefinitionJson("{\"grain\":\"一行一单\",\"fields\":[]}")
                .setSchemaHash("a".repeat(64))
                .setSourceSchemaHash("b".repeat(64))
                .setVerificationStatus(AiDatasetVersionDO.VERIFICATION_VERIFIED)
                .setVersion(2);
    }

    private static String permissionOf(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = AiDatasetController.class.getMethod(methodName, parameterTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("%s 必须声明服务端权限表达式", methodName).isNotNull();
        return annotation
                .value()
                .replace("@ss.hasPermission(", "")
                .replace(")", "")
                .replace("'", "");
    }

    @Test
    void everyEndpointDeclaresExactlyOnePermissionPolicy() {
        int endpoints = 0;
        for (Method method : AiDatasetController.class.getDeclaredMethods()) {
            boolean endpoint = method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class) != null
                    || method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class) != null
                    || method.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class) != null
                    || method.getAnnotation(org.springframework.web.bind.annotation.DeleteMapping.class) != null;
            if (!endpoint) {
                continue;
            }
            endpoints++;
            PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
            assertThat(annotation)
                    .as("%s 必须且只能声明一个 @PreAuthorize 权限", method.getName())
                    .isNotNull();
            assertThat(annotation.value()).contains("@ss.hasPermission('ai:dataset:");
        }
        assertThat(endpoints).as("端点数量与迁移菜单种子一致").isEqualTo(11);
    }

    @Test
    void permissionsMatchMigrationSeeds() throws Exception {
        assertThat(permissionOf("create", AiDatasetSaveReqVO.class)).isEqualTo("ai:dataset:create");
        assertThat(permissionOf("update", AiDatasetSaveReqVO.class)).isEqualTo("ai:dataset:update");
        assertThat(permissionOf("updateStatus", Long.class, Integer.class, Boolean.class))
                .isEqualTo("ai:dataset:update");
        assertThat(permissionOf("delete", Long.class, Integer.class)).isEqualTo("ai:dataset:delete");
        assertThat(permissionOf("get", Long.class)).isEqualTo("ai:dataset:query");
        assertThat(permissionOf("page", AiDatasetPageReqVO.class)).isEqualTo("ai:dataset:query");
        assertThat(permissionOf("createVersion", AiDatasetVersionSaveReqVO.class))
                .isEqualTo("ai:dataset:version:create");
        assertThat(permissionOf("verifyVersion", AiDatasetVersionVerifyReqVO.class))
                .isEqualTo("ai:dataset:version:verify");
        assertThat(permissionOf("publishVersion", AiDatasetVersionVerifyReqVO.class))
                .isEqualTo("ai:dataset:version:publish");
        assertThat(permissionOf("getVersion", Long.class)).isEqualTo("ai:dataset:query");
        assertThat(permissionOf("versionPage", Long.class, AiDatasetPageReqVO.class))
                .isEqualTo("ai:dataset:query");
    }

    @Test
    void responsesNeverCarryUpstreamDataOrSecrets() {
        List<Class<?>> voTypes =
                List.of(AiDatasetRespVO.class, AiDatasetVersionRespVO.class, AiDatasetVersionVerifyRespVO.class);
        for (Class<?> voType : voTypes) {
            List<String> fields = Arrays.stream(voType.getDeclaredFields())
                    .map(Field::getName)
                    .toList();
            assertThat(fields)
                    .as("%s 不得出现凭据/行数据字段", voType.getSimpleName())
                    .noneMatch(name -> name.matches("(?i).*(credential|password|secret|token|ciphertext|rows).*"));
        }
    }

    @Test
    void delegatesToServiceWithConvertedArguments() {
        when(datasetService.getDataset(81L)).thenReturn(dataset());
        when(datasetService.getVersion(91L)).thenReturn(version());
        when(datasetService.create(any())).thenReturn(81L);
        when(datasetService.createVersion(any())).thenReturn(91L);
        when(datasetService.verifyVersion(any(), any()))
                .thenReturn(new AiDatasetVersionVerifyResultDTO()
                        .setVersionId(91L)
                        .setVersionNo(1)
                        .setStatus(AiDatasetVersionDO.STATUS_DRAFT)
                        .setVerificationStatus(AiDatasetVersionDO.VERIFICATION_VERIFIED)
                        .setPublishable(true));
        when(datasetService.publishVersion(any(), any()))
                .thenReturn(new AiDatasetVersionVerifyResultDTO()
                        .setVersionId(91L)
                        .setVersionNo(1)
                        .setStatus(AiDatasetVersionDO.STATUS_PUBLISHED)
                        .setVerificationStatus(AiDatasetVersionDO.VERIFICATION_VERIFIED)
                        .setPublishable(true));
        when(datasetService.getDatasetPage(any(PageParam.class), any(), any()))
                .thenReturn(new PageResult<>(List.of(dataset()), 1L));
        when(datasetService.getVersionPage(any(), any(PageParam.class)))
                .thenReturn(new PageResult<>(List.of(version()), 1L));

        assertThat(controller
                        .create(new AiDatasetSaveReqVO()
                                .setCode("crm-orders")
                                .setName("CRM 订单")
                                .setConnectorId(71L)
                                .setSourceObject("crm.orders"))
                        .getData())
                .isEqualTo(81L);
        assertThat(controller
                        .update(new AiDatasetSaveReqVO()
                                .setId(81L)
                                .setName("CRM 订单")
                                .setVersion(3))
                        .getData())
                .isTrue();
        assertThat(controller.updateStatus(81L, 3, false).getData()).isTrue();
        assertThat(controller.delete(81L, 4).getData()).isTrue();
        assertThat(controller.get(81L).getData().getSourceObject()).isEqualTo("crm.orders");
        assertThat(controller.page(new AiDatasetPageReqVO()).getData().getTotal())
                .isEqualTo(1L);
        assertThat(controller
                        .createVersion(new AiDatasetVersionSaveReqVO()
                                .setDatasetId(81L)
                                .setDefinitionJson("{\"grain\":\"一行一单\",\"fields\":[]}"))
                        .getData())
                .isEqualTo(91L);
        assertThat(controller
                        .verifyVersion(new AiDatasetVersionVerifyReqVO()
                                .setVersionId(91L)
                                .setVersion(0))
                        .getData()
                        .getPublishable())
                .isTrue();
        assertThat(controller
                        .publishVersion(new AiDatasetVersionVerifyReqVO()
                                .setVersionId(91L)
                                .setVersion(1))
                        .getData()
                        .getStatus())
                .isEqualTo(AiDatasetVersionDO.STATUS_PUBLISHED);
        assertThat(controller.getVersion(91L).getData().getSchemaHash()).isEqualTo("a".repeat(64));
        assertThat(controller
                        .versionPage(81L, new AiDatasetPageReqVO())
                        .getData()
                        .getTotal())
                .isEqualTo(1L);

        verify(datasetService).updateStatus(81L, 3, false);
        verify(datasetService).delete(81L, 4);
    }
}
