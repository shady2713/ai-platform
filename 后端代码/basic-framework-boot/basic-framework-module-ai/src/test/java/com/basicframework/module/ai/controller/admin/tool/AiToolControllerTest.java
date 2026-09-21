package com.basicframework.module.ai.controller.admin.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.tool.vo.AiToolPageReqVO;
import com.basicframework.module.ai.controller.admin.tool.vo.AiToolRespVO;
import com.basicframework.module.ai.controller.admin.tool.vo.AiToolSaveReqVO;
import com.basicframework.module.ai.controller.admin.tool.vo.AiToolVersionPublishReqVO;
import com.basicframework.module.ai.controller.admin.tool.vo.AiToolVersionRespVO;
import com.basicframework.module.ai.controller.admin.tool.vo.AiToolVersionSaveReqVO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.service.tool.AiToolService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/** D08 工具控制面契约：权限码与 V70 种子一致、响应不含凭据/上游数据。 */
class AiToolControllerTest {

    private final AiToolService toolService = mock(AiToolService.class);

    private final AiToolController controller = new AiToolController(toolService);

    private static AiToolDO tool() {
        return new AiToolDO()
                .setId(91L)
                .setCode("query-orders")
                .setName("查询订单")
                .setConnectorId(71L)
                .setStatus(AiToolDO.STATUS_ENABLED)
                .setLatestVersionNo(1)
                .setVersion(2);
    }

    private static AiToolVersionDO version() {
        return new AiToolVersionDO()
                .setId(101L)
                .setToolId(91L)
                .setVersionNo(1)
                .setStatus(AiToolVersionDO.STATUS_PUBLISHED)
                .setToolType("READ")
                .setPolicy("CONFIRM")
                .setSourceKind(AiToolVersionDO.SOURCE_HTTP_OPERATION)
                .setSourceRef("getOrders")
                .setInputSchemaJson("{\"region\":{\"type\":\"string\",\"required\":true}}")
                .setOutputSchemaJson("{\"columns\":[]}")
                .setSchemaHash("a".repeat(64))
                .setVersion(1);
    }

    private static String permissionOf(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = AiToolController.class.getMethod(methodName, parameterTypes);
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
        for (Method method : AiToolController.class.getDeclaredMethods()) {
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
            assertThat(annotation.value()).contains("@ss.hasPermission('ai:tool:");
        }
        assertThat(endpoints).as("端点数量与 V70 菜单种子一致").isEqualTo(10);
    }

    @Test
    void permissionsMatchMigrationSeeds() throws Exception {
        assertThat(permissionOf("create", AiToolSaveReqVO.class)).isEqualTo("ai:tool:create");
        assertThat(permissionOf("update", AiToolSaveReqVO.class)).isEqualTo("ai:tool:update");
        assertThat(permissionOf("updateStatus", Long.class, Integer.class, Boolean.class))
                .isEqualTo("ai:tool:update");
        assertThat(permissionOf("delete", Long.class, Integer.class)).isEqualTo("ai:tool:delete");
        assertThat(permissionOf("get", Long.class)).isEqualTo("ai:tool:query");
        assertThat(permissionOf("page", AiToolPageReqVO.class)).isEqualTo("ai:tool:query");
        assertThat(permissionOf("createVersion", AiToolVersionSaveReqVO.class)).isEqualTo("ai:tool:version");
        assertThat(permissionOf("publishVersion", AiToolVersionPublishReqVO.class))
                .isEqualTo("ai:tool:version");
        assertThat(permissionOf("getVersion", Long.class)).isEqualTo("ai:tool:query");
        assertThat(permissionOf("versionPage", Long.class, AiToolPageReqVO.class))
                .isEqualTo("ai:tool:query");
    }

    @Test
    void responsesNeverCarryCredentialsOrUpstreamData() {
        for (Class<?> voType : List.of(AiToolRespVO.class, AiToolVersionRespVO.class)) {
            List<String> fields = Arrays.stream(voType.getDeclaredFields())
                    .map(Field::getName)
                    .toList();
            assertThat(fields)
                    .as("%s 不得出现凭据/连接串/行数据字段", voType.getSimpleName())
                    .noneMatch(name -> name.matches("(?i).*(credential|password|secret|token|ciphertext|jdbc|rows).*"));
        }
    }

    @Test
    void delegatesToServiceWithConvertedArguments() {
        when(toolService.getTool(91L)).thenReturn(tool());
        when(toolService.getVersion(101L)).thenReturn(version());
        when(toolService.create(any())).thenReturn(91L);
        when(toolService.createVersion(any())).thenReturn(101L);
        when(toolService.getToolPage(any(PageParam.class), any(), any()))
                .thenReturn(new PageResult<>(List.of(tool()), 1L));
        when(toolService.getVersionPage(anyLong(), any(PageParam.class)))
                .thenReturn(new PageResult<>(List.of(version()), 1L));

        assertThat(controller
                        .create(new AiToolSaveReqVO()
                                .setCode("query-orders")
                                .setName("查询订单")
                                .setConnectorId(71L))
                        .getData())
                .isEqualTo(91L);
        assertThat(controller
                        .update(new AiToolSaveReqVO().setId(91L).setName("查询订单").setVersion(2))
                        .getData())
                .isTrue();
        assertThat(controller.updateStatus(91L, 2, false).getData()).isTrue();
        assertThat(controller.delete(91L, 3).getData()).isTrue();
        assertThat(controller.get(91L).getData().getCode()).isEqualTo("query-orders");
        assertThat(controller.page(new AiToolPageReqVO()).getData().getTotal()).isEqualTo(1L);
        assertThat(controller
                        .createVersion(new AiToolVersionSaveReqVO()
                                .setToolId(91L)
                                .setToolType("READ")
                                .setSourceRef("getOrders")
                                .setInputSchemaJson("{\"region\":{\"type\":\"string\",\"required\":true}}")
                                .setOutputSchemaJson("{\"columns\":[]}"))
                        .getData())
                .isEqualTo(101L);
        assertThat(controller
                        .publishVersion(new AiToolVersionPublishReqVO()
                                .setVersionId(101L)
                                .setVersion(1))
                        .getData())
                .isTrue();
        assertThat(controller.getVersion(101L).getData().getPolicy()).isEqualTo("CONFIRM");
        assertThat(controller.versionPage(91L, new AiToolPageReqVO()).getData().getTotal())
                .isEqualTo(1L);

        verify(toolService).updateStatus(91L, 2, false);
        verify(toolService).delete(91L, 3);
    }
}
