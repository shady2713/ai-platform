package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.workflow.AiWorkflowRunService;
import com.basicframework.module.ai.service.workflow.AiWorkflowService;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowRunAcceptDTO;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowRunResultDTO;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowSaveDTO;
import jakarta.annotation.Resource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 可视化流程编排验收（X08）：真实 MySQL 上走完"建流程 → 存草稿 → 发布 → 受理同步执行"。
 *
 * <p>覆盖点（卡片 §5 必须产出：受限节点流程垂直切片、版本持久化、执行一致性与图验证）：
 * <ul>
 *   <li>版本持久化：草稿（单开）→ 发布（不可变图快照），同一流程出 v2 后已发布版本数为 2；</li>
 *   <li>执行一致性：受理固定最新已发布版本 + 同步有界执行 + 节点留痕（append-retention）落库；</li>
 *   <li>图验证与幂等：带环图发布被拒；停用流程拒绝发起运行；同幂等键重复受理返回同一次运行。</li>
 * </ul>
 * 图取最简 START→END：不发生任何模型/工具外发，专注持久化与编排语义。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiWorkflowAcceptanceIT extends AbstractPersistenceIntegrationTest {

    private static final String GRAPH = "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\",\"name\":\"开始\"},"
            + "{\"key\":\"end\",\"type\":\"END\",\"name\":\"结束\"}],"
            + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}";

    @Resource
    private AiWorkflowService workflowService;

    @Resource
    private AiWorkflowRunService runService;

    @Resource
    private AiApplicationService applicationService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    private Long applicationId;

    @BeforeEach
    void prepare() {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode("it-workflow-" + System.nanoTime() % 1_000_000)
                .setName("IT 流程编排应用")
                .setOrigins(List.of("https://crm.example.com")));
        applicationId = issue.getApplication().getId();
        applicationService.updateStatus(applicationId, 0, true);
    }

    /** 建流程 → 存合法草稿 → 发布 v1，返回流程编号。 */
    private Long createPublishedWorkflow(String code) {
        Long workflowId = workflowService.createWorkflow(new AiWorkflowSaveDTO()
                .setApplicationId(applicationId)
                .setCode(code)
                .setName("验收流程 " + code)
                .setDescription("X08 验收"));
        Long versionId = workflowService.createDraft(workflowId, GRAPH);
        workflowService.publishVersion(versionId, 0);
        return workflowId;
    }

    @Test
    void workflowLifecyclePersistsVersionsRunsAndNodeTrail() {
        Long workflowId = createPublishedWorkflow("it-flow-ok");

        AiWorkflowRunResultDTO result = runService.accept(new AiWorkflowRunAcceptDTO()
                .setWorkflowId(workflowId)
                .setIdempotencyKey("it-workflow-run-0001")
                .setDataLevel("L2_INTERNAL")
                .setInputText("验收输入"));
        assertThat(result.isReused()).as("首次受理不是复用").isFalse();
        assertThat(result.getVersionNo()).as("受理固定最新已发布版本").isEqualTo(1);
        assertThat(result.getRunId()).isNotNull();

        assertThat(runService.getRun(result.getRunId()).getStatus())
                .as("最简图同步执行成功")
                .isEqualTo("SUCCEEDED");
        assertThat(runService.getRunNodes(result.getRunId()))
                .as("节点留痕逐节点落库（START + END）")
                .hasSize(2);

        // 版本持久化：草稿出 v2 并发布，两个已发布版本并存（v1 不被改写）
        Long draftV2 = workflowService.createDraft(workflowId, GRAPH);
        workflowService.publishVersion(draftV2, 0);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_workflow_version WHERE workflow_id = ? AND status = 'PUBLISHED'",
                        Integer.class,
                        workflowId))
                .isEqualTo(2);
    }

    @Test
    void maintenancePathsPersistThroughAllMapperReadsAndWrites() {
        Long workflowId = createPublishedWorkflow("it-flow-maint");

        // 定义维护：改名（乐观锁）+ 停用 + 分页读取
        Integer workflowVersion = workflowService.getWorkflow(workflowId).getVersion();
        workflowService.updateWorkflow(new AiWorkflowSaveDTO()
                .setId(workflowId)
                .setApplicationId(applicationId)
                .setCode("it-flow-maint")
                .setName("改名后的流程")
                .setVersion(workflowVersion));
        assertThat(workflowService.getWorkflow(workflowId).getName()).isEqualTo("改名后的流程");

        workflowService.updateStatus(
                workflowId, workflowService.getWorkflow(workflowId).getVersion(), false);
        assertThat(workflowService.getWorkflow(workflowId).getStatus()).isEqualTo("DISABLED");
        workflowService.updateStatus(
                workflowId, workflowService.getWorkflow(workflowId).getVersion(), true);

        assertThat(workflowService
                        .getWorkflowPage(
                                new com.basicframework.framework.common.pojo.PageParam(), applicationId, null, null)
                        .getList())
                .extracting(com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowDO::getCode)
                .contains("it-flow-maint");

        // 版本：打开草稿 → 丢弃 → 再开一个 → 分页
        Long draft = workflowService.createDraft(workflowId, GRAPH);
        assertThat(workflowService.getOpenDraft(workflowId)).isNotNull();
        assertThat(workflowService.getLatestPublished(workflowId).getVersionNo())
                .isEqualTo(1);
        workflowService.discardDraft(draft, workflowService.getVersion(draft).getVersion());
        assertThat(workflowService.getOpenDraft(workflowId)).isNull();
        Long draft2 = workflowService.createDraft(workflowId, GRAPH);
        assertThat(workflowService
                        .getVersionPage(new com.basicframework.framework.common.pojo.PageParam(), workflowId, "DRAFT")
                        .getList())
                .extracting(com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowVersionDO::getId)
                .contains(draft2);

        // 运行分页 + 单条读取
        AiWorkflowRunResultDTO run = runService.accept(new AiWorkflowRunAcceptDTO()
                .setWorkflowId(workflowId)
                .setIdempotencyKey("it-workflow-run-maint1")
                .setDataLevel("L2_INTERNAL")
                .setInputText("维护路径"));
        assertThat(runService
                        .getRunPage(new com.basicframework.framework.common.pojo.PageParam(), workflowId, null)
                        .getList())
                .extracting(com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowRunDO::getId)
                .contains(run.getRunId());
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameRunWithoutSecondExecution() {
        Long workflowId = createPublishedWorkflow("it-flow-idem");

        AiWorkflowRunResultDTO first = runService.accept(new AiWorkflowRunAcceptDTO()
                .setWorkflowId(workflowId)
                .setIdempotencyKey("it-workflow-run-dup01")
                .setDataLevel("L2_INTERNAL")
                .setInputText("同一输入"));
        AiWorkflowRunResultDTO second = runService.accept(new AiWorkflowRunAcceptDTO()
                .setWorkflowId(workflowId)
                .setIdempotencyKey("it-workflow-run-dup01")
                .setDataLevel("L2_INTERNAL")
                .setInputText("同一输入"));

        assertThat(second.isReused()).as("同幂等键复用同一次运行").isTrue();
        assertThat(second.getRunId()).isEqualTo(first.getRunId());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_workflow_run WHERE id = ?", Integer.class, first.getRunId()))
                .as("该运行行只存在一条")
                .isEqualTo(1);
    }

    @Test
    void cyclicGraphIsRejectedAtPublishAndDisabledWorkflowRejectsRuns() {
        // 带环图：草稿能存，发布被结构校验拒绝
        Long cyclicWorkflow = workflowService.createWorkflow(new AiWorkflowSaveDTO()
                .setApplicationId(applicationId)
                .setCode("it-flow-cycle")
                .setName("带环流程"));
        String cyclic = "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\"},"
                + "{\"key\":\"a\",\"type\":\"MODEL\",\"config\":{\"endpointId\":1,\"promptTemplate\":\"p\"}},"
                + "{\"key\":\"end\",\"type\":\"END\"}],"
                + "\"edges\":[{\"from\":\"start\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"start\"}]}";
        Long draftId = workflowService.createDraft(cyclicWorkflow, cyclic);
        assertThatThrownBy(() -> workflowService.publishVersion(draftId, 0))
                .as("带环图不能发布")
                .isInstanceOf(ServiceException.class);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_workflow_version WHERE workflow_id = ? AND status = 'PUBLISHED'",
                        Integer.class,
                        cyclicWorkflow))
                .as("发布被拒后不留已发布版本")
                .isZero();

        // 停用已发布流程：受理被拒（版本已发布才谈得上停用运行）
        Long disabledWorkflow = createPublishedWorkflow("it-flow-disabled");
        workflowService.updateStatus(
                disabledWorkflow, workflowService.getWorkflow(disabledWorkflow).getVersion(), false);
        assertThatThrownBy(() -> runService.accept(new AiWorkflowRunAcceptDTO()
                        .setWorkflowId(disabledWorkflow)
                        .setIdempotencyKey("it-workflow-run-dis01")
                        .setDataLevel("L2_INTERNAL")
                        .setInputText("停用后不应执行")))
                .as("停用流程拒绝发起运行")
                .isInstanceOf(ServiceException.class);
    }
}
