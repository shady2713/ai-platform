package com.basicframework.module.ai.controller.admin.serviceconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceCapabilityRespVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServicePageReqVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceResourceRespVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceResourceSaveReqVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceRespVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceSaveReqVO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import com.basicframework.module.ai.service.serviceconfig.AiServiceService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceCapabilityDTO;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/** S01 服务控制面契约：权限码与 V56 种子一致、能力/绑定结果映射正确。 */
class AiServiceControllerTest {

    private final AiServiceService serviceService = mock(AiServiceService.class);

    private final AiServiceController controller = new AiServiceController(serviceService);

    private static AiServiceDO service() {
        return new AiServiceDO()
                .setId(9L)
                .setAppId(5L)
                .setCode("order-qa")
                .setName("订单问答")
                .setStatus(AiServiceDO.STATUS_DRAFT)
                .setModelEndpointId(1L)
                .setPromptTemplate("你是订单助手")
                .setInputSchema("{\"type\":\"object\"}")
                .setRequiredCapabilities("STRUCTURED_OUTPUT,TEXT")
                .setRunSubjectType("USER")
                .setDraftRevision(2)
                .setVersion(3);
    }

    @Test
    void createUpdateDeleteAndMarkReadyDelegate() {
        when(serviceService.createDraft(any())).thenReturn(9L);
        AiServiceSaveReqVO reqVO = new AiServiceSaveReqVO()
                .setAppId(5L)
                .setCode("order-qa")
                .setName("订单问答")
                .setModelEndpointId(1L)
                .setPromptTemplate("你是订单助手")
                .setInputSchema("{\"type\":\"object\"}")
                .setRequiredCapabilities(List.of("TEXT"))
                .setRunSubjectType("USER");

        assertThat(controller.createService(reqVO).getData()).isEqualTo(9L);
        verify(serviceService).createDraft(any());

        controller.updateService(reqVO.setId(9L).setVersion(3));
        verify(serviceService).updateDraft(any());

        controller.markReady(9L, 3);
        verify(serviceService).markReady(9L, 3);

        controller.deleteService(9L, 3);
        verify(serviceService).deleteDraft(9L, 3);
    }

    @Test
    void capabilityCheckSurfacesMissingCapabilities() {
        when(serviceService.checkCapabilities(9L))
                .thenReturn(new AiServiceCapabilityDTO()
                        .setRequired(List.of("TEXT", "STRUCTURED_OUTPUT"))
                        .setPublishable(List.of("TEXT"))
                        .setMissing(List.of("STRUCTURED_OUTPUT"))
                        .setSatisfied(false));

        AiServiceCapabilityRespVO respVO = controller.checkCapabilities(9L).getData();

        assertThat(respVO.getMissing()).containsExactly("STRUCTURED_OUTPUT");
        assertThat(respVO.isSatisfied()).isFalse();
        assertThat(respVO.getPublishable()).containsExactly("TEXT");
    }

    @Test
    void resourceEndpointsMapBindingsAndDelegate() {
        when(serviceService.bindResource(any())).thenReturn(11L);
        assertThat(controller
                        .bindResource(new AiServiceResourceSaveReqVO()
                                .setServiceId(9L)
                                .setResourceType("REPORT")
                                .setResourceKey("report-1")
                                .setActions(List.of("READ")))
                        .getData())
                .isEqualTo(11L);

        controller.unbindResource(11L, 2);
        verify(serviceService).unbindResource(11L, 2);

        when(serviceService.listDraftBindings(9L))
                .thenReturn(List.of(new AiServiceResourceDO()
                        .setId(11L)
                        .setServiceId(9L)
                        .setResourceType("REPORT")
                        .setResourceKey("report-1")
                        .setActions("READ,EXECUTE")
                        .setStatus(AiServiceResourceDO.STATUS_ACTIVE)
                        .setVersion(2)));
        List<AiServiceResourceRespVO> resources = controller.listResources(9L).getData();
        assertThat(resources).singleElement().satisfies(resource -> {
            assertThat(resource.getActions()).containsExactly("READ", "EXECUTE");
            assertThat(resource.getResourceKey()).isEqualTo("report-1");
        });
    }

    @Test
    void detailAndPageSplitCapabilitiesForDisplay() {
        when(serviceService.getService(9L)).thenReturn(service());
        when(serviceService.getServicePage(any(), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(service()), 1L));

        AiServiceRespVO detail = controller.getService(9L).getData();
        assertThat(detail.getRequiredCapabilities()).containsExactly("STRUCTURED_OUTPUT", "TEXT");
        assertThat(detail.getDraftRevision()).isEqualTo(2);

        AiServicePageReqVO pageReqVO = new AiServicePageReqVO();
        pageReqVO.setAppId(5L);
        assertThat(controller.getServicePage(pageReqVO).getData().getList()).hasSize(1);
    }

    @Test
    void permissionsMatchMigrationSeeds() throws Exception {
        assertThat(permissionOf("createService", AiServiceSaveReqVO.class)).isEqualTo("ai:service:create");
        assertThat(permissionOf("updateService", AiServiceSaveReqVO.class)).isEqualTo("ai:service:update");
        assertThat(permissionOf("deleteService", Long.class, Integer.class)).isEqualTo("ai:service:delete");
        assertThat(permissionOf("markReady", Long.class, Integer.class)).isEqualTo("ai:service:publish");
        assertThat(permissionOf("checkCapabilities", Long.class)).isEqualTo("ai:service:query");
        assertThat(permissionOf("bindResource", AiServiceResourceSaveReqVO.class))
                .isEqualTo("ai:service:bind");
        assertThat(permissionOf("unbindResource", Long.class, Integer.class)).isEqualTo("ai:service:bind");
        assertThat(permissionOf("listResources", Long.class)).isEqualTo("ai:service:query");
        assertThat(permissionOf("getService", Long.class)).isEqualTo("ai:service:query");
        assertThat(permissionOf("getServicePage", AiServicePageReqVO.class)).isEqualTo("ai:service:query");
    }

    private static String permissionOf(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = AiServiceController.class.getMethod(methodName, parameterTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("%s 必须声明服务端权限表达式", methodName).isNotNull();
        return annotation
                .value()
                .replace("@ss.hasPermission(", "")
                .replace(")", "")
                .replace("'", "");
    }
}
