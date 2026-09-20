package com.basicframework.module.ai.controller.admin.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.adapter.connector.http.AiHttpConnectorExecutor;
import com.basicframework.module.ai.controller.admin.connector.vo.AiConnectorPageReqVO;
import com.basicframework.module.ai.controller.admin.connector.vo.AiConnectorProbeRespVO;
import com.basicframework.module.ai.controller.admin.connector.vo.AiConnectorRespVO;
import com.basicframework.module.ai.controller.admin.connector.vo.AiConnectorSaveReqVO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorProbeDO;
import com.basicframework.module.ai.service.connector.AiConnectorService;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import com.basicframework.module.ai.service.connector.importer.AiConnectorOperationService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/** D01 连接器控制面契约：权限码与 V66 种子一致、响应不含秘密与密文。 */
class AiConnectorControllerTest {

    private final AiConnectorService connectorService = mock(AiConnectorService.class);

    private final AiConnectorOperationService operationService = mock(AiConnectorOperationService.class);

    private final AiHttpConnectorExecutor connectorExecutor = mock(AiHttpConnectorExecutor.class);

    private final AiConnectorController controller =
            new AiConnectorController(connectorService, operationService, connectorExecutor);

    private static AiConnectorDO connector() {
        return new AiConnectorDO()
                .setId(71L)
                .setCode("crm-http")
                .setName("CRM 接口")
                .setConnectorType("HTTP")
                .setStatus(AiConnectorDO.STATUS_ENABLED)
                .setConfigJson("{\"baseUrl\":\"https://crm.example.com\",\"method\":\"GET\"}")
                .setCredentialCiphertext("v1:encrypted")
                .setCredentialRevision(1)
                .setReferenced(false)
                .setVersion(0);
    }

    private static String permissionOf(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = AiConnectorController.class.getMethod(methodName, parameterTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("%s 必须声明服务端权限表达式", methodName).isNotNull();
        return annotation
                .value()
                .replace("@ss.hasPermission(", "")
                .replace(")", "")
                .replace("'", "");
    }

    @Test
    void everyEndpointDeclaresExactlyOnePermissionPolicy() throws Exception {
        for (Method method : AiConnectorController.class.getDeclaredMethods()) {
            if (method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class) == null
                    && method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class) == null
                    && method.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class) == null
                    && method.getAnnotation(org.springframework.web.bind.annotation.DeleteMapping.class) == null) {
                continue;
            }
            PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
            assertThat(annotation)
                    .as("%s 必须且只能声明一个 @PreAuthorize 权限", method.getName())
                    .isNotNull();
            assertThat(annotation.value()).contains("@ss.hasPermission('ai:connector:");
        }
    }

    @Test
    void permissionsMatchMigrationSeeds() throws Exception {
        assertThat(permissionOf("create", AiConnectorSaveReqVO.class)).isEqualTo("ai:connector:create");
        assertThat(permissionOf("update", AiConnectorSaveReqVO.class)).isEqualTo("ai:connector:update");
        assertThat(permissionOf("rotateCredential", Long.class, Integer.class, String.class))
                .isEqualTo("ai:connector:update");
        assertThat(permissionOf("updateStatus", Long.class, Integer.class, Boolean.class))
                .isEqualTo("ai:connector:update");
        assertThat(permissionOf("delete", Long.class, Integer.class)).isEqualTo("ai:connector:delete");
        assertThat(permissionOf("probe", Long.class)).isEqualTo("ai:connector:probe");
        assertThat(permissionOf("listProbes", Long.class)).isEqualTo("ai:connector:query");
        assertThat(permissionOf("get", Long.class)).isEqualTo("ai:connector:query");
        assertThat(permissionOf("page", AiConnectorPageReqVO.class)).isEqualTo("ai:connector:query");
        assertThat(permissionOf(
                        "importOperations",
                        Long.class,
                        com.basicframework.module.ai.controller.admin.connector.vo.AiConnectorImportReqVO.class))
                .isEqualTo("ai:connector:import");
        assertThat(permissionOf("listOperations", Long.class)).isEqualTo("ai:connector:query");
        assertThat(permissionOf(
                        "publishOperation",
                        com.basicframework.module.ai.controller.admin.connector.vo.AiConnectorOperationPublishReqVO
                                .class))
                .isEqualTo("ai:connector:operation");
        assertThat(permissionOf(
                        "executeOperation",
                        com.basicframework.module.ai.controller.admin.connector.vo.AiConnectorExecuteReqVO.class))
                .isEqualTo("ai:connector:query");
    }

    @Test
    void responsesNeverCarrySecretOrCiphertext() {
        List<String> fields = Arrays.stream(AiConnectorRespVO.class.getDeclaredFields())
                .map(Field::getName)
                .toList();

        assertThat(fields)
                .as("连接器响应不得回显秘密、密文或连接串")
                .doesNotContain("credential", "credentialCiphertext", "jdbcUrl", "password")
                .contains("credentialConfigured");

        when(connectorService.getConnector(71L)).thenReturn(connector());
        AiConnectorRespVO respVO = controller.get(71L).getData();
        assertThat(respVO.getCredentialConfigured()).as("只回是否已配置").isTrue();
        assertThat(respVO.getConfigJson()).doesNotContain("password");
    }

    @Test
    void importPublishAndExecuteEndpointsFollowTheContract() {
        when(operationService.importOperations(71L, "{\"openapi\":\"3.0.3\"}"))
                .thenReturn(new com.basicframework.module.ai.service.connector.importer.dto.AiOpenApiImportResultDTO()
                        .setOperations(java.util.List.of(
                                new com.basicframework.module.ai.service.connector.importer.dto
                                                .AiConnectorOperationDraftDTO()
                                        .setOperationKey("getOrder")))
                        .setSkipped(java.util.List.of("PUT /orders：只支持 GET/POST")));
        var imported = controller
                .importOperations(
                        71L,
                        new com.basicframework.module.ai.controller.admin.connector.vo.AiConnectorImportReqVO()
                                .setDocumentJson("{\"openapi\":\"3.0.3\"}"))
                .getData();
        assertThat(imported.getOperationKeys()).containsExactly("getOrder");
        assertThat(imported.getSkipped())
                .anySatisfy(reason -> assertThat(reason).contains("只支持 GET/POST"));

        controller.publishOperation(
                new com.basicframework.module.ai.controller.admin.connector.vo.AiConnectorOperationPublishReqVO()
                        .setId(81L)
                        .setVersion(0));
        verify(operationService).publish(81L, 0);

        when(operationService.listOperations(71L))
                .thenReturn(java.util.List.of(
                        new com.basicframework.module.ai.dal.dataobject.connector.AiConnectorOperationDO()
                                .setId(81L)
                                .setConnectorId(71L)
                                .setOperationKey("getOrder")
                                .setHttpMethod("GET")
                                .setPathTemplate("/orders/{id}")
                                .setStatus("PUBLISHED")
                                .setVersion(1)));
        assertThat(controller.listOperations(71L).getData()).hasSize(1);

        when(connectorExecutor.execute(any()))
                .thenReturn(new com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO()
                        .setStatus("PARTIAL")
                        .setPages(2)
                        .setItemCount(3)
                        .setStoppedReason("page-limit")
                        .setItems(java.util.List.of("{}")));
        var executed = controller
                .executeOperation(
                        new com.basicframework.module.ai.controller.admin.connector.vo.AiConnectorExecuteReqVO()
                                .setConnectorId(71L)
                                .setOperationKey("getOrder")
                                .setArguments(java.util.Map.of("id", "A-1")))
                .getData();
        assertThat(executed.getStatus()).isEqualTo("PARTIAL");
        assertThat(executed.getStoppedReason()).isEqualTo("page-limit");

        // 执行请求体没有 URL 与请求头字段：调用方（含模型）无法替换
        assertThat(java.util.Arrays.stream(
                                com.basicframework.module.ai.controller.admin.connector.vo.AiConnectorExecuteReqVO.class
                                        .getDeclaredFields())
                        .map(Field::getName)
                        .toList())
                .doesNotContain("url", "headers", "header", "path", "method");
    }

    @Test
    void mutationsAndQueriesDelegate() {
        when(connectorService.create(any())).thenReturn(71L);
        assertThat(controller
                        .create(new AiConnectorSaveReqVO()
                                .setCode("crm-http")
                                .setName("CRM 接口")
                                .setConnectorType("HTTP")
                                .setConfigJson("{\"baseUrl\":\"https://crm.example.com\",\"method\":\"GET\"}")
                                .setCredential("sk-connector-secret"))
                        .getData())
                .isEqualTo(71L);
        verify(connectorService)
                .create(new AiConnectorSaveDTO()
                        .setCode("crm-http")
                        .setName("CRM 接口")
                        .setConnectorType("HTTP")
                        .setConfigJson("{\"baseUrl\":\"https://crm.example.com\",\"method\":\"GET\"}")
                        .setCredential("sk-connector-secret"));

        controller.update(new AiConnectorSaveReqVO()
                .setId(71L)
                .setCode("crm-http")
                .setName("CRM 接口")
                .setConnectorType("HTTP")
                .setConfigJson("{}")
                .setVersion(0));
        verify(connectorService).update(any(AiConnectorSaveDTO.class));

        controller.rotateCredential(71L, 0, "sk-rotated");
        verify(connectorService).rotateCredential(71L, 0, "sk-rotated");
        controller.updateStatus(71L, 0, false);
        verify(connectorService).updateStatus(71L, 0, false);
        controller.delete(71L, 0);
        verify(connectorService).delete(71L, 0);

        when(connectorService.probe(71L))
                .thenReturn(new com.basicframework.module.ai.service.connector.dto.AiConnectorProbeResultDTO()
                        .setConnectorId(71L)
                        .setProbeKind(AiConnectorProbeDO.KIND_HTTP_CONNECTIVITY)
                        .setStatus(AiConnectorProbeDO.STATUS_FAILED)
                        .setDetailCode("TARGET_NOT_ALLOWED")
                        .setLatencyMs(5));
        AiConnectorProbeRespVO probe = controller.probe(71L).getData();
        assertThat(probe.getDetailCode()).isEqualTo("TARGET_NOT_ALLOWED");

        when(connectorService.listProbes(71L))
                .thenReturn(List.of(new AiConnectorProbeDO()
                        .setConnectorId(71L)
                        .setProbeKind(AiConnectorProbeDO.KIND_HTTP_CONNECTIVITY)
                        .setStatus(AiConnectorProbeDO.STATUS_SUPPORTED)
                        .setLatencyMs(3)));
        assertThat(controller.listProbes(71L).getData()).hasSize(1);

        when(connectorService.getPage(any(), any(), any())).thenReturn(new PageResult<>(List.of(connector()), 1L));
        assertThat(controller.page(new AiConnectorPageReqVO()).getData().getList())
                .hasSize(1);
    }
}
