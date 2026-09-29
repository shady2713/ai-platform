package com.basicframework.module.ai.service.workflow;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_CODE_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_DRAFT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_GRAPH_CYCLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_NODE_REFERENCE_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_VERSION_IMMUTABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_VERSION_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowVersionDO;
import com.basicframework.module.ai.dal.mysql.workflow.AiWorkflowMapper;
import com.basicframework.module.ai.dal.mysql.workflow.AiWorkflowVersionMapper;
import com.basicframework.module.ai.domain.workflow.AiWorkflowGraph;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.tool.AiToolService;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowDraftSaveDTO;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowSaveDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 流程定义与版本（X08 验收 2）：草稿/发布隔离、单开草稿、发布闸门与乐观锁。
 */
@ExtendWith(MockitoExtension.class)
class AiWorkflowServiceImplTest {

    private static final Long WORKFLOW_ID = 11L;

    private static final Long VERSION_ID = 21L;

    private static final Long APPLICATION_ID = 7L;

    private static final String TOOL_CODE = "crm_query_order";

    private static final String PIPELINE_GRAPH = "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\"},"
            + "{\"key\":\"call\",\"type\":\"TOOL\",\"config\":{\"toolCode\":\"" + TOOL_CODE + "\"}},"
            + "{\"key\":\"end\",\"type\":\"END\"}],"
            + "\"edges\":[{\"from\":\"start\",\"to\":\"call\"},{\"from\":\"call\",\"to\":\"end\"}]}";

    @Mock
    private AiWorkflowMapper workflowMapper;

    @Mock
    private AiWorkflowVersionMapper versionMapper;

    @Mock
    private AiApplicationService applicationService;

    @Mock
    private AiModelEndpointService endpointService;

    @Mock
    private AiDatasetService datasetService;

    @Mock
    private AiToolService toolService;

    private AiWorkflowServiceImpl service;

    private AiWorkflowDO workflow;

    @BeforeEach
    void setUp() {
        service = new AiWorkflowServiceImpl(
                workflowMapper, versionMapper, applicationService, endpointService, datasetService, toolService);
        workflow = new AiWorkflowDO()
                .setId(WORKFLOW_ID)
                .setApplicationId(APPLICATION_ID)
                .setCode("order-flow")
                .setName("订单流程")
                .setStatus(AiWorkflowDO.STATUS_ENABLED)
                .setLatestVersionNo(0)
                .setVersion(0);
        org.mockito.Mockito.lenient()
                .when(workflowMapper.selectById(WORKFLOW_ID))
                .thenReturn(workflow);
    }

    @Test
    void createRejectsDuplicateCodeInsideApplication() {
        when(workflowMapper.selectByCode(APPLICATION_ID, "order-flow")).thenReturn(workflow);

        assertThatThrownBy(() -> service.createWorkflow(saveRequest()))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_CODE_DUPLICATE.getCode()));
    }

    @Test
    void createRejectsInvalidCodeShape() {
        AiWorkflowSaveDTO request = saveRequest().setCode("9bad code");

        assertThatThrownBy(() -> service.createWorkflow(request))
                .isInstanceOfSatisfying(
                        ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(AI_REQUEST_INVALID.getCode()));
    }

    @Test
    void updateOptimisticConflictIsSurfaced() {
        when(workflowMapper.updateWithVersion(any(AiWorkflowDO.class), eq(0))).thenReturn(0);

        assertThatThrownBy(() ->
                        service.updateWorkflow(saveRequest().setId(WORKFLOW_ID).setVersion(0)))
                .isInstanceOfSatisfying(
                        ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(AI_STATE_CONFLICT.getCode()));
    }

    @Test
    void createDraftRefusesSecondOpenDraftAndBumpsVersionNo() {
        AiWorkflowDO existing = new AiWorkflowDO()
                .setId(WORKFLOW_ID)
                .setStatus(AiWorkflowDO.STATUS_ENABLED)
                .setLatestVersionNo(3)
                .setVersion(4);
        when(workflowMapper.selectById(WORKFLOW_ID)).thenReturn(existing);
        when(versionMapper.selectOpenDraft(WORKFLOW_ID))
                .thenReturn(new AiWorkflowVersionDO().setId(99L).setStatus(AiWorkflowVersionDO.STATUS_DRAFT));

        assertThatThrownBy(() -> service.createDraft(WORKFLOW_ID, PIPELINE_GRAPH))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_DRAFT_EXISTS.getCode()));

        when(versionMapper.selectOpenDraft(WORKFLOW_ID)).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
                    invocation.<AiWorkflowVersionDO>getArgument(0).setId(99L);
                    return 1;
                })
                .when(versionMapper)
                .insert(org.mockito.ArgumentMatchers.any(AiWorkflowVersionDO.class));
        when(workflowMapper.updateWithVersion(any(AiWorkflowDO.class), eq(4))).thenReturn(1);

        Long draftId = service.createDraft(WORKFLOW_ID, PIPELINE_GRAPH);

        ArgumentCaptor<AiWorkflowVersionDO> inserted = ArgumentCaptor.forClass(AiWorkflowVersionDO.class);
        verify(versionMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getVersionNo()).isEqualTo(4);
        assertThat(inserted.getValue().getStatus()).isEqualTo(AiWorkflowVersionDO.STATUS_DRAFT);
        assertThat(inserted.getValue().getGraphHash()).hasSize(64);
        assertThat(draftId).isEqualTo(99L);
    }

    @Test
    void createDraftRejectsGraphThatCannotParse() {
        // 图解析先于草稿落库：坏图在创建草稿时就被拒绝
        assertThatThrownBy(() -> service.createDraft(WORKFLOW_ID, "{\"nodes\":[]}"))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(
                                com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_GRAPH_INVALID
                                        .getCode()));
    }

    @Test
    void publishedVersionIsImmutable() {
        AiWorkflowVersionDO published = new AiWorkflowVersionDO()
                .setId(VERSION_ID)
                .setWorkflowId(WORKFLOW_ID)
                .setStatus(AiWorkflowVersionDO.STATUS_PUBLISHED)
                .setVersion(0);
        when(versionMapper.selectById(VERSION_ID)).thenReturn(published);

        assertThatThrownBy(() -> service.updateDraft(draftRequest(published.getVersion())))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_VERSION_IMMUTABLE.getCode()));
        assertThatThrownBy(() -> service.publishVersion(VERSION_ID, 0))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_VERSION_IMMUTABLE.getCode()));
    }

    @Test
    void discardMovesDraftToTerminalState() {
        AiWorkflowVersionDO draft = new AiWorkflowVersionDO()
                .setId(VERSION_ID)
                .setWorkflowId(WORKFLOW_ID)
                .setStatus(AiWorkflowVersionDO.STATUS_DRAFT)
                .setVersion(0);
        when(versionMapper.selectById(VERSION_ID)).thenReturn(draft);
        when(versionMapper.updateWithVersion(any(AiWorkflowVersionDO.class), eq(0)))
                .thenReturn(1);

        service.discardDraft(VERSION_ID, 0);

        ArgumentCaptor<AiWorkflowVersionDO> update = ArgumentCaptor.forClass(AiWorkflowVersionDO.class);
        verify(versionMapper).updateWithVersion(update.capture(), eq(0));
        assertThat(update.getValue().getStatus()).isEqualTo(AiWorkflowVersionDO.STATUS_DISCARDED);
    }

    @Test
    void publishRejectsCyclicGraphBeforeTouchingReferences() {
        // 环是与主链分离的孤立分量：形状与端口都合法，只有拓扑判定能抓到
        String cyclicGraph = "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\"},"
                + "{\"key\":\"end\",\"type\":\"END\"},"
                + "{\"key\":\"a\",\"type\":\"TOOL\",\"config\":{\"toolCode\":\"" + TOOL_CODE + "\"}},"
                + "{\"key\":\"b\",\"type\":\"MODEL\",\"config\":{\"endpointId\":1,\"promptTemplate\":\"p\"}}],"
                + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"},{\"from\":\"a\",\"to\":\"b\"},"
                + "{\"from\":\"b\",\"to\":\"a\"}]}";
        AiWorkflowVersionDO draft = draftWith(cyclicGraph);
        when(versionMapper.selectById(VERSION_ID)).thenReturn(draft);

        assertThatThrownBy(() -> service.publishVersion(VERSION_ID, 0))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_GRAPH_CYCLE.getCode()));
        verify(toolService, org.mockito.Mockito.never()).requirePublishedVersion(any());
    }

    @Test
    void publishRejectsUnknownToolReference() {
        AiWorkflowVersionDO draft = draftWith(PIPELINE_GRAPH);
        when(versionMapper.selectById(VERSION_ID)).thenReturn(draft);
        when(toolService.requirePublishedVersion(TOOL_CODE))
                .thenThrow(new ServiceException(
                        com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_NOT_FOUND));

        assertThatThrownBy(() -> service.publishVersion(VERSION_ID, 0))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NODE_REFERENCE_INVALID.getCode()));
    }

    @Test
    void publishFreezesImmutableSnapshotOnHappyPath() {
        AiWorkflowVersionDO draft = draftWith(PIPELINE_GRAPH);
        when(versionMapper.selectById(VERSION_ID)).thenReturn(draft);
        when(toolService.requirePublishedVersion(TOOL_CODE))
                .thenReturn(new com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO());
        when(versionMapper.updateWithVersion(any(AiWorkflowVersionDO.class), eq(0)))
                .thenReturn(1);

        Long published = service.publishVersion(VERSION_ID, 0);

        assertThat(published).isEqualTo(VERSION_ID);
        ArgumentCaptor<AiWorkflowVersionDO> update = ArgumentCaptor.forClass(AiWorkflowVersionDO.class);
        verify(versionMapper).updateWithVersion(update.capture(), eq(0));
        assertThat(update.getValue().getStatus()).isEqualTo(AiWorkflowVersionDO.STATUS_PUBLISHED);
        assertThat(update.getValue().getPublishedAt()).isNotNull();
    }

    @Test
    void deleteRemovesWorkflowRowAfterCas() {
        when(workflowMapper.updateWithVersion(any(AiWorkflowDO.class), eq(0))).thenReturn(1);

        service.deleteWorkflow(WORKFLOW_ID, 0);

        verify(workflowMapper).deleteById(WORKFLOW_ID);
    }

    @Test
    void unknownWorkflowIsNotFound() {
        when(workflowMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.getWorkflow(404L))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NOT_FOUND.getCode()));
    }

    // ---------- 夹具 ----------

    @Test
    void updateStatusGuardsNullInputsAndUsesCas() {
        assertThatThrownBy(() -> service.updateStatus(WORKFLOW_ID, null, true))
                .isInstanceOfSatisfying(
                        ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(AI_REQUEST_INVALID.getCode()));
        assertThatThrownBy(() -> service.updateStatus(WORKFLOW_ID, 0, null))
                .isInstanceOfSatisfying(
                        ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(AI_REQUEST_INVALID.getCode()));

        when(workflowMapper.updateWithVersion(any(AiWorkflowDO.class), anyInt()))
                .thenReturn(1);
        service.updateStatus(WORKFLOW_ID, 0, false);

        when(workflowMapper.updateWithVersion(any(AiWorkflowDO.class), anyInt()))
                .thenReturn(0);
        assertThatThrownBy(() -> service.updateStatus(WORKFLOW_ID, 0, true))
                .as("啟停同样带乐观锁：0 行按状态冲突拒绝")
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void deleteRequiresVersion() {
        assertThatThrownBy(() -> service.deleteWorkflow(WORKFLOW_ID, null))
                .isInstanceOfSatisfying(
                        ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(AI_REQUEST_INVALID.getCode()));
    }

    @Test
    void updateRejectsBlankNameAndMissingVersion() {
        AiWorkflowSaveDTO blankName =
                saveRequest().setId(WORKFLOW_ID).setVersion(0).setName("  ");
        assertThatThrownBy(() -> service.updateWorkflow(blankName))
                .isInstanceOfSatisfying(
                        ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(AI_REQUEST_INVALID.getCode()));

        AiWorkflowSaveDTO noVersion = saveRequest().setId(WORKFLOW_ID).setVersion(null);
        assertThatThrownBy(() -> service.updateWorkflow(noVersion))
                .isInstanceOfSatisfying(
                        ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(AI_REQUEST_INVALID.getCode()));
    }

    @Test
    void versionReadsReturnNullOrNotFoundInsteadOfThrowingOnEmptyInput() {
        assertThat(service.getOpenDraft(null)).isNull();
        assertThat(service.getLatestPublished(null)).isNull();
        assertThatThrownBy(() -> service.getVersion(null))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_VERSION_NOT_FOUND.getCode()));
        assertThatThrownBy(() -> service.getVersion(VERSION_ID))
                .as("不存在的版本与越权同语义")
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_VERSION_NOT_FOUND.getCode()));

        when(versionMapper.selectOpenDraft(WORKFLOW_ID)).thenReturn(draftWith(PIPELINE_GRAPH));
        assertThat(service.getOpenDraft(WORKFLOW_ID)).isNotNull();
    }

    @Test
    void pagingRequiresPageParamAndValidatesWorkflowScope() {
        assertThatThrownBy(() -> service.getVersionPage(null, WORKFLOW_ID, null))
                .isInstanceOfSatisfying(
                        ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(AI_REQUEST_INVALID.getCode()));
        assertThatThrownBy(() -> service.getWorkflowPage(null, null, null, null))
                .isInstanceOfSatisfying(
                        ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(AI_REQUEST_INVALID.getCode()));

        when(workflowMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.getVersionPage(new PageParam(), 999L, null))
                .as("按流程过滤时流程必须存在（越权与不存在同语义）")
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void publishCasConflictIsSurfaced() {
        when(versionMapper.selectById(VERSION_ID)).thenReturn(draftWith(PIPELINE_GRAPH));
        when(toolService.requirePublishedVersion(TOOL_CODE)).thenReturn(null);
        when(versionMapper.updateWithVersion(any(AiWorkflowVersionDO.class), anyInt()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.publishVersion(VERSION_ID, 0))
                .as("并发发布/废弃只有一个赢家")
                .isInstanceOfSatisfying(
                        ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(AI_STATE_CONFLICT.getCode()));
    }

    @Test
    void pageQueriesDelegateWithServerSideFilters() {
        when(workflowMapper.selectPage(
                        any(PageParam.class),
                        org.mockito.ArgumentMatchers.nullable(Long.class),
                        org.mockito.ArgumentMatchers.nullable(String.class),
                        org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(new PageResult<>(java.util.List.of(workflow), 1L));
        assertThat(service.getWorkflowPage(new PageParam(), APPLICATION_ID, "order-flow", "ENABLED")
                        .getTotal())
                .isEqualTo(1L);

        AiWorkflowVersionDO draft = draftWith(PIPELINE_GRAPH);
        when(versionMapper.selectPage(
                        any(PageParam.class), anyLong(), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(new PageResult<>(java.util.List.of(draft), 1L));
        assertThat(service.getVersionPage(new PageParam(), WORKFLOW_ID, "DRAFT").getTotal())
                .isEqualTo(1L);
    }

    @Test
    void updateDraftRequiresVersionIdAppliesEditableDraftAndSurfacesCasConflict() {
        assertThatThrownBy(() -> service.updateDraft(new AiWorkflowDraftSaveDTO()
                        .setGraphJson(PIPELINE_GRAPH)
                        .setVersion(0)))
                .isInstanceOfSatisfying(
                        ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(AI_REQUEST_INVALID.getCode()));

        // 版本不属于该流程：与不存在同语义
        AiWorkflowVersionDO foreign = draftWith(PIPELINE_GRAPH).setWorkflowId(999L);
        when(versionMapper.selectById(VERSION_ID)).thenReturn(foreign);
        assertThatThrownBy(() ->
                        service.updateDraft(draftRequest(foreign.getVersion()).setWorkflowId(WORKFLOW_ID)))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_VERSION_NOT_FOUND.getCode()));

        // 草稿可改：写回带图摘要与节点/边计数
        AiWorkflowVersionDO draft = draftWith(PIPELINE_GRAPH);
        when(versionMapper.selectById(VERSION_ID)).thenReturn(draft);
        when(versionMapper.updateWithVersion(any(AiWorkflowVersionDO.class), anyInt()))
                .thenReturn(1);
        service.updateDraft(draftRequest(draft.getVersion()));
        org.mockito.ArgumentCaptor<AiWorkflowVersionDO> captor =
                org.mockito.ArgumentCaptor.forClass(AiWorkflowVersionDO.class);
        verify(versionMapper).updateWithVersion(captor.capture(), anyInt());
        assertThat(captor.getValue().getNodeCount()).as("start→call→end 三个节点").isEqualTo(3);
        assertThat(captor.getValue().getGraphHash()).isEqualTo(AiWorkflowGraph.graphHash(PIPELINE_GRAPH));

        // CAS 未命中 → 状态冲突
        when(versionMapper.updateWithVersion(any(AiWorkflowVersionDO.class), anyInt()))
                .thenReturn(0);
        assertThatThrownBy(() -> service.updateDraft(draftRequest(draft.getVersion())))
                .isInstanceOfSatisfying(
                        ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(AI_STATE_CONFLICT.getCode()));
    }

    @Test
    void discardRequiresVersionAndSurfacesCasConflict() {
        assertThatThrownBy(() -> service.discardDraft(VERSION_ID, null))
                .isInstanceOfSatisfying(
                        ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(AI_REQUEST_INVALID.getCode()));

        when(versionMapper.selectById(VERSION_ID)).thenReturn(draftWith(PIPELINE_GRAPH));
        when(versionMapper.updateWithVersion(any(AiWorkflowVersionDO.class), anyInt()))
                .thenReturn(0);
        assertThatThrownBy(() -> service.discardDraft(VERSION_ID, 0))
                .isInstanceOfSatisfying(
                        ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(AI_STATE_CONFLICT.getCode()));
    }

    @Test
    void secondOpenDraftIsRefusedByDuplicateKeyGuard() {
        when(versionMapper.selectOpenDraft(WORKFLOW_ID)).thenReturn(null);
        when(versionMapper.insert(any(AiWorkflowVersionDO.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_workflow_draft"));

        assertThatThrownBy(() -> service.createDraft(WORKFLOW_ID, PIPELINE_GRAPH))
                .as("并发下的唯一键兜底：同一流程只允许一个打开草稿")
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_DRAFT_EXISTS.getCode()));
    }

    @Test
    void latestPublishedReadsReturnNullWhenAbsent() {
        when(versionMapper.selectLatestPublished(WORKFLOW_ID)).thenReturn(java.util.Optional.empty());
        assertThat(service.getLatestPublished(WORKFLOW_ID)).isNull();
        assertThat(service.getOpenDraft(WORKFLOW_ID)).isNull();
    }

    private static AiWorkflowSaveDTO saveRequest() {
        return new AiWorkflowSaveDTO()
                .setApplicationId(APPLICATION_ID)
                .setCode("order-flow")
                .setName("订单流程")
                .setVersion(0);
    }

    private AiWorkflowDraftSaveDTO draftRequest(Integer version) {
        return new AiWorkflowDraftSaveDTO()
                .setWorkflowId(WORKFLOW_ID)
                .setVersionId(VERSION_ID)
                .setGraphJson(PIPELINE_GRAPH)
                .setVersion(version);
    }

    private AiWorkflowVersionDO draftWith(String graphJson) {
        return new AiWorkflowVersionDO()
                .setId(VERSION_ID)
                .setWorkflowId(WORKFLOW_ID)
                .setStatus(AiWorkflowVersionDO.STATUS_DRAFT)
                .setGraphJson(graphJson)
                .setVersion(0);
    }
}
