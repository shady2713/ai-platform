package com.basicframework.module.ai.service.workflow;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_CODE_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_DRAFT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_NODE_REFERENCE_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_NODE_TYPE_MISMATCH;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_VERSION_IMMUTABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_VERSION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_VERSION_STATE_INVALID;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetVersionDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowVersionDO;
import com.basicframework.module.ai.dal.mysql.workflow.AiWorkflowMapper;
import com.basicframework.module.ai.dal.mysql.workflow.AiWorkflowVersionMapper;
import com.basicframework.module.ai.domain.workflow.AiWorkflowGraph;
import com.basicframework.module.ai.domain.workflow.AiWorkflowGraphValidator;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.tool.AiToolService;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowDraftSaveDTO;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowSaveDTO;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 流程定义与版本实现（X08）。
 *
 * <p>写路径全部收口在这里：草稿编辑只打 DRAFT 行（乐观锁），发布先过结构校验与引用核对
 * 再 CAS 落 PUBLISHED——已发布版本从此不可变，运行受理固定到版本编号，草稿怎么改都
 * 影响不到在途与后续按旧版本受理的运行。
 */
@Service
@RequiredArgsConstructor
public class AiWorkflowServiceImpl implements AiWorkflowService {

    /** 流程标识：字母开头，字母数字与连字符/下划线，3..64。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{2,63}$");

    private static final int MAX_NAME_LENGTH = 128;

    private static final int MAX_DESCRIPTION_LENGTH = 512;

    private final AiWorkflowMapper workflowMapper;

    private final AiWorkflowVersionMapper versionMapper;

    private final AiApplicationService applicationService;

    private final AiModelEndpointService endpointService;

    private final AiDatasetService datasetService;

    private final AiToolService toolService;

    /** 图结构校验器（纯结构，无数据库访问；实例方法便于未来扩展）。 */
    private final AiWorkflowGraphValidator graphValidator = new AiWorkflowGraphValidator();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createWorkflow(AiWorkflowSaveDTO saveDTO) {
        requireSaveFields(saveDTO, true);
        applicationService.getApplication(saveDTO.getApplicationId());
        if (workflowMapper.selectByCode(saveDTO.getApplicationId(), saveDTO.getCode()) != null) {
            throw exception(AI_WORKFLOW_CODE_DUPLICATE, saveDTO.getCode());
        }
        AiWorkflowDO workflow = new AiWorkflowDO()
                .setApplicationId(saveDTO.getApplicationId())
                .setCode(saveDTO.getCode())
                .setName(saveDTO.getName())
                .setDescription(saveDTO.getDescription() == null ? "" : saveDTO.getDescription())
                .setStatus(AiWorkflowDO.STATUS_ENABLED)
                .setLatestVersionNo(0)
                .setVersion(0);
        workflowMapper.insert(workflow);
        return workflow.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateWorkflow(AiWorkflowSaveDTO saveDTO) {
        requireSaveFields(saveDTO, false);
        AiWorkflowDO existing = requireWorkflow(saveDTO.getId());
        if (workflowMapper.updateWithVersion(
                        new AiWorkflowDO()
                                .setId(existing.getId())
                                .setName(saveDTO.getName())
                                .setDescription(saveDTO.getDescription() == null ? "" : saveDTO.getDescription())
                                .setVersion(existing.getVersion() + 1),
                        saveDTO.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer version, Boolean enabled) {
        if (version == null || enabled == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiWorkflowDO existing = requireWorkflow(id);
        if (workflowMapper.updateWithVersion(
                        new AiWorkflowDO()
                                .setId(id)
                                .setStatus(enabled ? AiWorkflowDO.STATUS_ENABLED : AiWorkflowDO.STATUS_DISABLED)
                                .setVersion(existing.getVersion() + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteWorkflow(Long id, Integer version) {
        if (version == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiWorkflowDO existing = requireWorkflow(id);
        if (workflowMapper.updateWithVersion(
                        new AiWorkflowDO().setId(id).setVersion(existing.getVersion() + 1), version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        workflowMapper.deleteById(id);
    }

    @Override
    public AiWorkflowDO getWorkflow(Long id) {
        return requireWorkflow(id);
    }

    @Override
    public PageResult<AiWorkflowDO> getWorkflowPage(
            PageParam pageParam, Long applicationId, String code, String status) {
        requirePageParam(pageParam);
        return workflowMapper.selectPage(pageParam, applicationId, code, status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDraft(Long workflowId, String graphJson) {
        if (workflowId == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiWorkflowDO workflow = requireWorkflow(workflowId);
        // 草稿在创建时就要求是可解析的受控契约（完整发布校验在发布期）
        AiWorkflowGraph graph = AiWorkflowGraph.parse(graphJson);
        if (versionMapper.selectOpenDraft(workflowId) != null) {
            throw exception(AI_WORKFLOW_DRAFT_EXISTS);
        }
        int versionNo = workflow.getLatestVersionNo() + 1;
        AiWorkflowVersionDO version = new AiWorkflowVersionDO()
                .setWorkflowId(workflowId)
                .setVersionNo(versionNo)
                .setStatus(AiWorkflowVersionDO.STATUS_DRAFT)
                .setGraphJson(graphJson.trim())
                .setGraphHash(AiWorkflowGraph.graphHash(graphJson.trim()))
                .setNodeCount(graph.nodeCount())
                .setEdgeCount(graph.edgeCount())
                .setVersion(0);
        try {
            versionMapper.insert(version);
        } catch (DuplicateKeyException concurrentDraft) {
            // 并发创建草稿：函数唯一键只允许一个赢家
            throw exception(AI_WORKFLOW_DRAFT_EXISTS);
        }
        if (workflowMapper.updateWithVersion(
                        new AiWorkflowDO()
                                .setId(workflowId)
                                .setLatestVersionNo(versionNo)
                                .setVersion(workflow.getVersion() + 1),
                        workflow.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        return version.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDraft(AiWorkflowDraftSaveDTO saveDTO) {
        if (saveDTO == null
                || saveDTO.getWorkflowId() == null
                || saveDTO.getVersionId() == null
                || !StringUtils.hasText(saveDTO.getGraphJson())
                || saveDTO.getVersion() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiWorkflowVersionDO existing = requireVersion(saveDTO.getVersionId());
        if (!existing.getWorkflowId().equals(saveDTO.getWorkflowId())) {
            throw exception(AI_WORKFLOW_VERSION_NOT_FOUND);
        }
        requireEditableDraft(existing);
        AiWorkflowGraph graph = AiWorkflowGraph.parse(saveDTO.getGraphJson());
        if (versionMapper.updateWithVersion(
                        new AiWorkflowVersionDO()
                                .setId(existing.getId())
                                .setGraphJson(saveDTO.getGraphJson().trim())
                                .setGraphHash(AiWorkflowGraph.graphHash(
                                        saveDTO.getGraphJson().trim()))
                                .setNodeCount(graph.nodeCount())
                                .setEdgeCount(graph.edgeCount())
                                .setVersion(existing.getVersion() + 1),
                        saveDTO.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void discardDraft(Long versionId, Integer version) {
        if (versionId == null || version == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiWorkflowVersionDO existing = requireVersion(versionId);
        requireEditableDraft(existing);
        if (versionMapper.updateWithVersion(
                        new AiWorkflowVersionDO()
                                .setId(versionId)
                                .setStatus(AiWorkflowVersionDO.STATUS_DISCARDED)
                                .setVersion(existing.getVersion() + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long publishVersion(Long versionId, Integer version) {
        if (versionId == null || version == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiWorkflowVersionDO existing = requireVersion(versionId);
        requireEditableDraft(existing);
        // 1) 结构校验：环、无出口、类型/端口不匹配、节点配置形状——这里全部拒绝
        graphValidator.validate(existing.getGraphJson());
        // 2) 引用核对：节点引用的工具/数据集/端点必须存在且当前可用
        checkReferences(existing.getGraphJson());
        // 3) CAS 发布：并发发布/废弃只有一个赢家；发布后版本不可变
        if (versionMapper.updateWithVersion(
                        new AiWorkflowVersionDO()
                                .setId(versionId)
                                .setStatus(AiWorkflowVersionDO.STATUS_PUBLISHED)
                                .setPublishedAt(LocalDateTime.now())
                                .setVersion(existing.getVersion() + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        return versionId;
    }

    @Override
    public AiWorkflowVersionDO getVersion(Long versionId) {
        return requireVersion(versionId);
    }

    @Override
    public AiWorkflowVersionDO getOpenDraft(Long workflowId) {
        return workflowId == null ? null : versionMapper.selectOpenDraft(workflowId);
    }

    @Override
    public AiWorkflowVersionDO getLatestPublished(Long workflowId) {
        return workflowId == null
                ? null
                : versionMapper.selectLatestPublished(workflowId).orElse(null);
    }

    @Override
    public PageResult<AiWorkflowVersionDO> getVersionPage(PageParam pageParam, Long workflowId, String status) {
        requirePageParam(pageParam);
        if (workflowId != null) {
            requireWorkflow(workflowId);
        }
        return versionMapper.selectPage(pageParam, workflowId, status);
    }

    // ---------- 引用核对（发布闸门的数据库侧） ----------

    private void checkReferences(String graphJson) {
        AiWorkflowGraph graph = AiWorkflowGraph.parse(graphJson);
        for (AiWorkflowGraph.Node node : graph.nodes()) {
            switch (node.type()) {
                case MODEL -> requireEndpoint(node);
                case DATA_QUERY -> requireDataset(node);
                case TOOL -> requireTool(node);
                default -> {
                    // 开始/结束/条件/知识检索：无数据库引用（条件引用已由结构校验判定）
                }
            }
        }
    }

    private void requireEndpoint(AiWorkflowGraph.Node node) {
        Long endpointId = positiveLong(node.config().get("endpointId"));
        if (endpointId == null) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        if (endpointService.getRevisions(endpointId).isEmpty()) {
            // 端点存在但没有可用配置版本：发布即不可执行，拒绝发布
            throw exception(AI_WORKFLOW_NODE_REFERENCE_INVALID);
        }
    }

    private void requireDataset(AiWorkflowGraph.Node node) {
        Long datasetId = positiveLong(node.config().get("datasetId"));
        Long datasetVersionId = positiveLong(node.config().get("datasetVersionId"));
        if (datasetId == null || datasetVersionId == null) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        try {
            AiDatasetDO dataset = datasetService.getDataset(datasetId);
            AiDatasetVersionDO version = datasetService.getVersion(datasetVersionId);
            if (!dataset.getId().equals(version.getDatasetId())
                    || !AiDatasetDO.STATUS_ENABLED.equals(dataset.getStatus())
                    || !AiDatasetVersionDO.STATUS_PUBLISHED.equals(version.getStatus())
                    || AiDatasetVersionDO.VERIFICATION_DRIFTED.equals(version.getVerificationStatus())) {
                throw exception(AI_WORKFLOW_NODE_REFERENCE_INVALID);
            }
        } catch (ServiceException referenceFailure) {
            if (AI_WORKFLOW_NODE_REFERENCE_INVALID.getCode() == referenceFailure.getCode()) {
                throw referenceFailure;
            }
            // 引用了不存在的数据集/版本：发布期统一拒绝（运行期 R05 还会按同一语义复核）
            throw exception(AI_WORKFLOW_NODE_REFERENCE_INVALID);
        }
    }

    private void requireTool(AiWorkflowGraph.Node node) {
        String toolCode =
                node.config().get("toolCode") instanceof String text && StringUtils.hasText(text) ? text : null;
        if (toolCode == null) {
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        try {
            toolService.requirePublishedVersion(toolCode);
        } catch (ServiceException referenceFailure) {
            // 工具不存在或没有已发布版本：发布期统一拒绝
            throw exception(AI_WORKFLOW_NODE_REFERENCE_INVALID);
        }
    }

    // ---------- 通用 ----------

    private static void requireSaveFields(AiWorkflowSaveDTO saveDTO, boolean creating) {
        if (saveDTO == null
                || !StringUtils.hasText(saveDTO.getName())
                || saveDTO.getName().length() > MAX_NAME_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (saveDTO.getDescription() != null && saveDTO.getDescription().length() > MAX_DESCRIPTION_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (creating) {
            if (!StringUtils.hasText(saveDTO.getCode())
                    || !CODE_PATTERN.matcher(saveDTO.getCode()).matches()
                    || saveDTO.getApplicationId() == null) {
                throw exception(AI_REQUEST_INVALID);
            }
            return;
        }
        if (saveDTO.getId() == null || saveDTO.getVersion() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private static void requirePageParam(PageParam pageParam) {
        if (pageParam == null) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    /** 只有 DRAFT 可编辑/发布；PUBLISHED 报不可变，DISCARDED 报状态不允许。 */
    private static void requireEditableDraft(AiWorkflowVersionDO existing) {
        if (AiWorkflowVersionDO.STATUS_PUBLISHED.equals(existing.getStatus())) {
            throw exception(AI_WORKFLOW_VERSION_IMMUTABLE);
        }
        if (!AiWorkflowVersionDO.STATUS_DRAFT.equals(existing.getStatus())) {
            throw exception(AI_WORKFLOW_VERSION_STATE_INVALID);
        }
    }

    private AiWorkflowDO requireWorkflow(Long id) {
        AiWorkflowDO workflow = id == null ? null : workflowMapper.selectById(id);
        if (workflow == null) {
            throw exception(AI_WORKFLOW_NOT_FOUND);
        }
        return workflow;
    }

    private AiWorkflowVersionDO requireVersion(Long versionId) {
        AiWorkflowVersionDO version = versionId == null ? null : versionMapper.selectById(versionId);
        if (version == null) {
            throw exception(AI_WORKFLOW_VERSION_NOT_FOUND);
        }
        return version;
    }

    private static Long positiveLong(Object value) {
        if (value instanceof Integer integer) {
            return integer > 0 ? integer.longValue() : null;
        }
        if (value instanceof Long longValue) {
            return longValue > 0 ? longValue : null;
        }
        return null;
    }
}
