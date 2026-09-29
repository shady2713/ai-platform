package com.basicframework.module.ai.service.workflow;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_IDEMPOTENCY_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_DISABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_VERSION_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowRunDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowRunNodeDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowVersionDO;
import com.basicframework.module.ai.dal.mysql.workflow.AiWorkflowMapper;
import com.basicframework.module.ai.dal.mysql.workflow.AiWorkflowRunMapper;
import com.basicframework.module.ai.dal.mysql.workflow.AiWorkflowRunNodeMapper;
import com.basicframework.module.ai.dal.mysql.workflow.AiWorkflowVersionMapper;
import com.basicframework.module.ai.domain.workflow.AiWorkflowBudget;
import com.basicframework.module.ai.domain.workflow.AiWorkflowGraph;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowRunAcceptDTO;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowRunResultDTO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 流程受控运行受理（X08 验收 2/3）：受理固定版本、幂等复用、停用与无发布版本拒绝。
 */
@ExtendWith(MockitoExtension.class)
class AiWorkflowRunServiceImplTest {

    private static final Long WORKFLOW_ID = 11L;

    private static final Long VERSION_ID = 21L;

    private static final String KEY = "accept-key-0000000001";

    @Mock
    private AiWorkflowMapper workflowMapper;

    @Mock
    private AiWorkflowVersionMapper versionMapper;

    @Mock
    private AiWorkflowRunMapper runMapper;

    @Mock
    private AiWorkflowRunNodeMapper nodeMapper;

    @Mock
    private AiWorkflowRunExecutor executor;

    private AiWorkflowRunServiceImpl service;

    private AiWorkflowDO workflow;

    private AiWorkflowVersionDO published;

    @BeforeEach
    void setUp() {
        service = new AiWorkflowRunServiceImpl(workflowMapper, versionMapper, runMapper, nodeMapper, executor);
        workflow = new AiWorkflowDO()
                .setId(WORKFLOW_ID)
                .setStatus(AiWorkflowDO.STATUS_ENABLED)
                .setLatestVersionNo(1)
                .setVersion(0);
        published = new AiWorkflowVersionDO()
                .setId(VERSION_ID)
                .setWorkflowId(WORKFLOW_ID)
                .setVersionNo(1)
                .setStatus(AiWorkflowVersionDO.STATUS_PUBLISHED)
                .setNodeCount(3)
                .setEdgeCount(2)
                .setVersion(0);
        // 受理路径大多会被提前拒绝：公共桩一律 lenient，按用例需要才被使用
        org.mockito.Mockito.lenient()
                .when(workflowMapper.selectById(WORKFLOW_ID))
                .thenReturn(workflow);
        org.mockito.Mockito.lenient()
                .when(versionMapper.selectLatestPublished(WORKFLOW_ID))
                .thenReturn(java.util.Optional.of(published));
        org.mockito.Mockito.lenient().when(versionMapper.selectById(VERSION_ID)).thenReturn(published);
    }

    @Test
    void acceptPinsLatestPublishedVersionAndExecutesSynchronously() {
        when(runMapper.selectByIdempotencyKey(WORKFLOW_ID, KEY)).thenReturn(null);
        stubInsert();
        when(executor.execute(any(AiWorkflowRunDO.class), any(AiWorkflowVersionDO.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        stubNodeRows();
        AiWorkflowRunResultDTO result = service.accept(acceptRequest());

        assertThat(result.isReused()).isFalse();
        assertThat(result.getWorkflowVersionId()).isEqualTo(VERSION_ID);
        assertThat(result.getVersionNo()).isEqualTo(1);
        assertThat(result.getNodeTotal()).isEqualTo(3);
        assertThat(result.getNodes()).hasSize(1);
        ArgumentCaptor<AiWorkflowRunDO> inserted = ArgumentCaptor.forClass(AiWorkflowRunDO.class);
        verify(runMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo(AiWorkflowRunDO.STATUS_RUNNING);
        assertThat(inserted.getValue().getMaxSteps()).isEqualTo(AiWorkflowBudget.DEFAULT_MAX_STEPS);
        // 版本快照交给执行器：草稿之后怎么改都影响不到这次运行
        verify(executor).execute(any(AiWorkflowRunDO.class), any(AiWorkflowVersionDO.class));
    }

    @Test
    void sameKeySameDigestReusesFirstRunWithoutExecuting() {
        AiWorkflowRunDO first = new AiWorkflowRunDO()
                .setId(77L)
                .setWorkflowId(WORKFLOW_ID)
                .setWorkflowVersionId(VERSION_ID)
                .setIdempotencyKey(KEY)
                .setRequestDigest(expectedDigest())
                .setStatus(AiWorkflowRunDO.STATUS_SUCCEEDED)
                .setNodeTotal(3)
                .setNodeExecuted(3);
        when(runMapper.selectByIdempotencyKey(WORKFLOW_ID, KEY)).thenReturn(first);
        stubNodeRows();

        AiWorkflowRunResultDTO result = service.accept(acceptRequest());

        assertThat(result.isReused()).isTrue();
        assertThat(result.getRunId()).isEqualTo(77L);
        verify(executor, never()).execute(any(), any());
        verify(runMapper, never()).insert(any(AiWorkflowRunDO.class));
    }

    @Test
    void sameKeyDifferentDigestIsRejected() {
        AiWorkflowRunDO first = new AiWorkflowRunDO()
                .setId(77L)
                .setWorkflowId(WORKFLOW_ID)
                .setIdempotencyKey(KEY)
                .setRequestDigest("different-digest")
                .setStatus(AiWorkflowRunDO.STATUS_FAILED);
        when(runMapper.selectByIdempotencyKey(WORKFLOW_ID, KEY)).thenReturn(first);

        assertThatThrownBy(() -> service.accept(acceptRequest()))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_IDEMPOTENCY_CONFLICT.getCode()));
    }

    @Test
    void concurrentAcceptConvergesOnTheWinnerRow() {
        when(runMapper.selectByIdempotencyKey(WORKFLOW_ID, KEY))
                .thenReturn(null)
                .thenReturn(new AiWorkflowRunDO()
                        .setId(88L)
                        .setWorkflowId(WORKFLOW_ID)
                        .setWorkflowVersionId(VERSION_ID)
                        .setIdempotencyKey(KEY)
                        .setRequestDigest(expectedDigest())
                        .setStatus(AiWorkflowRunDO.STATUS_SUCCEEDED));
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_ai_workflow_run_accept"))
                .when(runMapper)
                .insert(any(AiWorkflowRunDO.class));
        stubNodeRows();

        AiWorkflowRunResultDTO result = service.accept(acceptRequest());

        assertThat(result.isReused()).isTrue();
        assertThat(result.getRunId()).isEqualTo(88L);
    }

    @Test
    void disabledWorkflowIsRefused() {
        workflow.setStatus(AiWorkflowDO.STATUS_DISABLED);

        assertThatThrownBy(() -> service.accept(acceptRequest()))
                .isInstanceOfSatisfying(
                        ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(AI_WORKFLOW_DISABLED.getCode()));
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void workflowWithoutPublishedVersionIsRefused() {
        when(versionMapper.selectLatestPublished(WORKFLOW_ID)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.accept(acceptRequest()))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_VERSION_NOT_FOUND.getCode()));
    }

    @Test
    void unknownWorkflowIsRefused() {
        when(workflowMapper.selectById(WORKFLOW_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.accept(acceptRequest()))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AI_WORKFLOW_NOT_FOUND.getCode()));
    }

    @Test
    void shortIdempotencyKeyIsRejectedBeforeAnythingElse() {
        assertThatThrownBy(() -> service.accept(acceptRequest().setIdempotencyKey("short")))
                .isInstanceOfSatisfying(
                        ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(AI_REQUEST_INVALID.getCode()));
    }

    @Test
    void budgetAbovePlatformCapIsRejected() {
        assertThatThrownBy(() -> service.accept(acceptRequest().setMaxSteps(99)))
                .isInstanceOfSatisfying(
                        ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(AI_REQUEST_INVALID.getCode()));
    }

    // ---------- 夹具 ----------

    private static AiWorkflowRunAcceptDTO acceptRequest() {
        return new AiWorkflowRunAcceptDTO()
                .setWorkflowId(WORKFLOW_ID)
                .setIdempotencyKey(KEY)
                .setDataLevel("L2_INTERNAL")
                .setInputText("总结输入");
    }

    private static String expectedDigest() {
        return AiWorkflowGraph.runDigest(
                WORKFLOW_ID,
                "L2_INTERNAL",
                "总结输入",
                AiWorkflowBudget.DEFAULT_MAX_STEPS,
                AiWorkflowBudget.DEFAULT_MAX_DURATION_MILLIS);
    }

    private void stubInsert() {
        org.mockito.Mockito.doAnswer(invocation -> {
                    invocation.<AiWorkflowRunDO>getArgument(0).setId(77L);
                    return 1;
                })
                .when(runMapper)
                .insert(any(AiWorkflowRunDO.class));
    }

    private void stubNodeRows() {
        when(nodeMapper.selectByRunId(anyLong()))
                .thenReturn(List.of(new AiWorkflowRunNodeDO()
                        .setRunId(77L)
                        .setNodeKey("start")
                        .setNodeType("START")
                        .setStatus(AiWorkflowRunNodeDO.STATUS_SUCCEEDED)));
    }
}
