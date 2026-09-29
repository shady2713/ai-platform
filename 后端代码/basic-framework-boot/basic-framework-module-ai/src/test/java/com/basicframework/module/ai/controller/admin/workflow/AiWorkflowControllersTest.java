package com.basicframework.module.ai.controller.admin.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowDraftCreateReqVO;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowDraftUpdateReqVO;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowRunAcceptReqVO;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowSaveReqVO;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowStatusReqVO;
import com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowVersionActionReqVO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowRunDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowRunNodeDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowVersionDO;
import com.basicframework.module.ai.service.workflow.AiWorkflowRunService;
import com.basicframework.module.ai.service.workflow.AiWorkflowService;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowRunAcceptDTO;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowRunResultDTO;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * 流程编排控制面契约（X08）：权限码与 V89 菜单种子一致、VO→DTO→VO 映射不丢字段。
 */
class AiWorkflowControllersTest {

    private static final String SIMPLE_GRAPH =
            "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\"},{\"key\":\"end\",\"type\":\"END\"}],"
                    + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}";

    private final AiWorkflowService workflowService = mock(AiWorkflowService.class);

    private final AiWorkflowRunService runService = mock(AiWorkflowRunService.class);

    private final AiWorkflowController workflowController = new AiWorkflowController(workflowService);

    private final AiWorkflowVersionController versionController = new AiWorkflowVersionController(workflowService);

    private final AiWorkflowRunController runController = new AiWorkflowRunController(runService);

    @Test
    void everyEndpointDeclaresExactlyOnePermissionFromTheV89Seed() throws Exception {
        for (Class<?> controller :
                List.of(AiWorkflowController.class, AiWorkflowVersionController.class, AiWorkflowRunController.class)) {
            for (Method method : controller.getDeclaredMethods()) {
                if (method.getAnnotation(PostMapping.class) == null
                        && method.getAnnotation(PutMapping.class) == null
                        && method.getAnnotation(DeleteMapping.class) == null
                        && method.getAnnotation(GetMapping.class) == null) {
                    continue;
                }
                PreAuthorize policy = method.getAnnotation(PreAuthorize.class);
                assertThat(policy)
                        .as("%s.%s 必须声明权限", controller.getSimpleName(), method.getName())
                        .isNotNull();
                assertThat(policy.value())
                        .as("%s.%s 的权限码必须在 V89 种子里", controller.getSimpleName(), method.getName())
                        .isIn(
                                "@ss.hasPermission('ai:workflow:query')",
                                "@ss.hasPermission('ai:workflow:manage')",
                                "@ss.hasPermission('ai:workflow:run')",
                                "@ss.hasPermission('ai:workflow:delete')");
            }
        }
    }

    @Test
    void workflowSaveVoMapsToServiceDtoWithoutEchoingExtras() {
        when(workflowService.createWorkflow(any())).thenReturn(5L);
        CommonResult<Long> created = workflowController.createWorkflow(new AiWorkflowSaveReqVO()
                .setApplicationId(7L)
                .setCode("order-flow")
                .setName("订单流程")
                .setDescription("说明"));
        assertThat(created.getData()).isEqualTo(5L);

        workflowController.updateWorkflow(
                new AiWorkflowSaveReqVO().setId(5L).setName("新名").setVersion(2));
        ArgumentCaptor<com.basicframework.module.ai.service.workflow.dto.AiWorkflowSaveDTO> dto =
                ArgumentCaptor.forClass(com.basicframework.module.ai.service.workflow.dto.AiWorkflowSaveDTO.class);
        verify(workflowService).updateWorkflow(dto.capture());
        assertThat(dto.getValue().getId()).isEqualTo(5L);
        assertThat(dto.getValue().getName()).isEqualTo("新名");
        assertThat(dto.getValue().getVersion()).isEqualTo(2);

        workflowController.updateWorkflowStatus(
                new AiWorkflowStatusReqVO().setId(5L).setEnabled(false).setVersion(2));
        verify(workflowService).updateStatus(5L, 2, false);
        workflowController.deleteWorkflow(5L, 2);
        verify(workflowService).deleteWorkflow(5L, 2);
    }

    @Test
    void pageEndpointsMapServerSideFiltersAndTotals() {
        when(workflowService.getWorkflowPage(any(), any(), any(), any()))
                .thenReturn(new com.basicframework.framework.common.pojo.PageResult<>(
                        java.util.List.of(new com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowDO()
                                .setId(1L)
                                .setCode("order-flow")
                                .setName("订单流程")
                                .setStatus("ENABLED")
                                .setLatestVersionNo(2)),
                        1L));
        com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowPageReqVO pageReqVO =
                new com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowPageReqVO();
        pageReqVO.setApplicationId(7L);
        pageReqVO.setCode("order-flow");
        pageReqVO.setStatus("ENABLED");

        var page = workflowController.getWorkflowPage(pageReqVO).getData();

        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getList()).hasSize(1);
        assertThat(page.getList().get(0).getCode()).isEqualTo("order-flow");
        verify(workflowService).getWorkflowPage(any(), eq(7L), eq("order-flow"), eq("ENABLED"));

        when(workflowService.getVersionPage(any(), anyLong(), any()))
                .thenReturn(new com.basicframework.framework.common.pojo.PageResult<>(
                        java.util.List.of(new com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowVersionDO()
                                .setId(2L)
                                .setWorkflowId(1L)
                                .setVersionNo(2)
                                .setStatus("DRAFT")),
                        1L));
        com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowVersionPageReqVO versionReqVO =
                new com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowVersionPageReqVO();
        versionReqVO.setWorkflowId(1L);
        versionReqVO.setStatus("DRAFT");

        var versionPage = versionController.getVersionPage(versionReqVO).getData();

        assertThat(versionPage.getTotal()).isEqualTo(1L);
        assertThat(versionPage.getList().get(0).getVersionNo()).isEqualTo(2);
        verify(workflowService).getVersionPage(any(), eq(1L), eq("DRAFT"));
    }

    @Test
    void workflowRespVoExposesStatusAndVersion() {
        when(workflowService.getWorkflow(5L))
                .thenReturn(new AiWorkflowDO()
                        .setId(5L)
                        .setApplicationId(7L)
                        .setCode("order-flow")
                        .setName("订单流程")
                        .setStatus(AiWorkflowDO.STATUS_ENABLED)
                        .setLatestVersionNo(3)
                        .setVersion(2));
        AiWorkflowController.class.getName();
        var result = workflowController.getWorkflow(5L).getData();
        assertThat(result.getStatus()).isEqualTo("ENABLED");
        assertThat(result.getLatestVersionNo()).isEqualTo(3);
        assertThat(result.getVersion()).isEqualTo(2);
    }

    @Test
    void versionEndpointsCarryGraphAndOptimisticLock() {
        when(workflowService.createDraft(eq(5L), any())).thenReturn(21L);
        assertThat(versionController
                        .createDraft(new AiWorkflowDraftCreateReqVO()
                                .setWorkflowId(5L)
                                .setGraphJson(SIMPLE_GRAPH))
                        .getData())
                .isEqualTo(21L);

        versionController.updateDraft(new AiWorkflowDraftUpdateReqVO()
                .setWorkflowId(5L)
                .setVersionId(21L)
                .setGraphJson(SIMPLE_GRAPH)
                .setVersion(1));
        ArgumentCaptor<com.basicframework.module.ai.service.workflow.dto.AiWorkflowDraftSaveDTO> draft =
                ArgumentCaptor.forClass(com.basicframework.module.ai.service.workflow.dto.AiWorkflowDraftSaveDTO.class);
        verify(workflowService).updateDraft(draft.capture());
        assertThat(draft.getValue().getVersionId()).isEqualTo(21L);
        assertThat(draft.getValue().getGraphJson()).isEqualTo(SIMPLE_GRAPH);

        when(workflowService.publishVersion(21L, 1)).thenReturn(21L);
        assertThat(versionController
                        .publishVersion(
                                new AiWorkflowVersionActionReqVO().setId(21L).setVersion(1))
                        .getData())
                .isEqualTo(21L);
        verify(workflowService).publishVersion(21L, 1);
        versionController.discardDraft(
                new AiWorkflowVersionActionReqVO().setId(21L).setVersion(1));
        verify(workflowService).discardDraft(21L, 1);

        when(workflowService.getOpenDraft(5L))
                .thenReturn(new AiWorkflowVersionDO()
                        .setId(21L)
                        .setWorkflowId(5L)
                        .setVersionNo(1)
                        .setStatus(AiWorkflowVersionDO.STATUS_DRAFT)
                        .setGraphJson(SIMPLE_GRAPH)
                        .setVersion(1));
        assertThat(versionController.getOpenDraft(5L).getData().getGraphJson()).isEqualTo(SIMPLE_GRAPH);
        when(workflowService.getVersion(21L))
                .thenReturn(new AiWorkflowVersionDO()
                        .setId(21L)
                        .setWorkflowId(5L)
                        .setVersionNo(1)
                        .setStatus(AiWorkflowVersionDO.STATUS_DRAFT)
                        .setGraphJson(SIMPLE_GRAPH)
                        .setVersion(1));
        assertThat(versionController.getVersion(21L).getData().getVersionNo()).isEqualTo(1);
    }

    @Test
    void runAcceptEchoesTheRecordedFactsIncludingNodeTimeline() {
        when(runService.accept(any()))
                .thenReturn(new AiWorkflowRunResultDTO()
                        .setRunId(31L)
                        .setWorkflowId(5L)
                        .setWorkflowVersionId(21L)
                        .setVersionNo(1)
                        .setStatus("SUCCEEDED")
                        .setOutputText("结论")
                        .setNodeExecuted(2)
                        .setNodeTotal(2)
                        .setDurationMs(12L)
                        .setNodes(List.of(new AiWorkflowRunResultDTO.Node()
                                .setNodeKey("start")
                                .setNodeType("START")
                                .setStatus("SUCCEEDED"))));
        var result = runController
                .acceptRun(new AiWorkflowRunAcceptReqVO()
                        .setWorkflowId(5L)
                        .setIdempotencyKey("accept-key-0000000001")
                        .setDataLevel("L2_INTERNAL")
                        .setInputText("输入"))
                .getData();
        assertThat(result.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.getNodes()).hasSize(1);
        ArgumentCaptor<AiWorkflowRunAcceptDTO> accept = ArgumentCaptor.forClass(AiWorkflowRunAcceptDTO.class);
        verify(runService).accept(accept.capture());
        assertThat(accept.getValue().getWorkflowId()).isEqualTo(5L);
        assertThat(accept.getValue().getDataLevel()).isEqualTo("L2_INTERNAL");

        when(runService.getRun(31L))
                .thenReturn(new AiWorkflowRunDO()
                        .setId(31L)
                        .setWorkflowId(5L)
                        .setWorkflowVersionId(21L)
                        .setIdempotencyKey("accept-key-0000000001")
                        .setStatus(AiWorkflowRunDO.STATUS_FAILED)
                        .setDataLevel("L2_INTERNAL")
                        .setErrorCode("1003006004")
                        .setNodeExecuted(1)
                        .setNodeTotal(2)
                        .setStartedTime(LocalDateTime.now()));
        assertThat(runController.getRun(31L).getData().getErrorCode()).isEqualTo("1003006004");
        when(runService.getRunPage(any(), eq(5L), eq("FAILED")))
                .thenReturn(new PageResult<>(List.of(new AiWorkflowRunDO().setId(31L)), 1L));
        com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowRunPageReqVO runPage =
                new com.basicframework.module.ai.controller.admin.workflow.vo.AiWorkflowRunPageReqVO();
        runPage.setWorkflowId(5L);
        runPage.setStatus("FAILED");
        assertThat(runController.getRunPage(runPage).getData().getList()).hasSize(1);
        when(runService.getRunNodes(31L))
                .thenReturn(List.of(new AiWorkflowRunNodeDO()
                        .setRunId(31L)
                        .setNodeKey("start")
                        .setNodeType("START")
                        .setStatus(AiWorkflowRunNodeDO.STATUS_SUCCEEDED)
                        .setDurationMs(1L)));
        assertThat(runController.getNodeList(31L).getData()).hasSize(1);
    }
}
