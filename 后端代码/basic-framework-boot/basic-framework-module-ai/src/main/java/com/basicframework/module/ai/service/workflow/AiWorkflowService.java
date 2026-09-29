package com.basicframework.module.ai.service.workflow;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowVersionDO;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowDraftSaveDTO;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowSaveDTO;

/**
 * 流程定义与版本（X08）：草稿/发布隔离的唯一写入口。
 *
 * <p>三条不变式：
 * <ol>
 *   <li><b>版本不可变</b>：只有 DRAFT 可编辑；发布用 CAS 落 PUBLISHED 并冻结图摘要，
 *       之后任何修改都必须新建草稿版本；</li>
 *   <li><b>单开草稿</b>：同一流程同时最多一个草稿版本（唯一键兜底并发创建）；</li>
 *   <li><b>发布闸门</b>：环、无出口、类型/端口不匹配、节点引用不存在的资源（工具/数据集/端点）
 *       一律拒绝发布——发布出去的图保证"从开始到结束的有界 DAG + 可用引用"。</li>
 * </ol>
 */
public interface AiWorkflowService {

    /** 创建流程定义（应用内标识唯一；返回编号）。 */
    Long createWorkflow(AiWorkflowSaveDTO saveDTO);

    /** 更新名称/说明（乐观锁）。 */
    void updateWorkflow(AiWorkflowSaveDTO saveDTO);

    /** 启用/停用（停用后不受理新运行；乐观锁）。 */
    void updateStatus(Long id, Integer version, Boolean enabled);

    /** 删除流程定义（软删除；乐观锁）。 */
    void deleteWorkflow(Long id, Integer version);

    /** 按编号取流程定义。 */
    AiWorkflowDO getWorkflow(Long id);

    /** 分页查询（标识/名称模糊、状态精确）。 */
    PageResult<AiWorkflowDO> getWorkflowPage(PageParam pageParam, Long applicationId, String code, String status);

    /** 新建草稿版本（图必须是合法的受控契约；同一流程已有打开草稿时拒绝）。返回版本编号。 */
    Long createDraft(Long workflowId, String graphJson);

    /** 编辑草稿图（只有 DRAFT 可编辑；乐观锁）。 */
    void updateDraft(AiWorkflowDraftSaveDTO saveDTO);

    /** 废弃草稿（终态，释放"单开草稿"；乐观锁）。 */
    void discardDraft(Long versionId, Integer version);

    /**
     * 发布版本：结构校验（环/无出口/类型不匹配）+ 引用核对（工具/数据集/端点存在且可用）
     * 全部通过才允许 CAS 发布。返回版本编号。
     */
    Long publishVersion(Long versionId, Integer version);

    /** 按编号取版本。 */
    AiWorkflowVersionDO getVersion(Long versionId);

    /** 当前打开的草稿（无则返回 null）。 */
    AiWorkflowVersionDO getOpenDraft(Long workflowId);

    /** 最新已发布版本（无则返回 null；运行受理固定到它）。 */
    AiWorkflowVersionDO getLatestPublished(Long workflowId);

    /** 版本分页（按流程过滤，状态精确；按版本序号倒序）。 */
    PageResult<AiWorkflowVersionDO> getVersionPage(PageParam pageParam, Long workflowId, String status);
}
