package com.basicframework.module.ai.service.workflow.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 流程草稿保存参数（服务层 DTO，X08）。
 *
 * <p>图 JSON 是受控契约（nodes/edges，见 {@code AiWorkflowGraph}）；只有 DRAFT 版本可编辑，
 * 已发布版本传到这里直接拒绝（版本隔离的写侧入口）。
 */
@Data
@Accessors(chain = true)
public class AiWorkflowDraftSaveDTO {

    /** 流程编号（必填） */
    private Long workflowId;

    /** 草稿版本编号（必填） */
    private Long versionId;

    /** 流程图 JSON（必填，受控契约） */
    private String graphJson;

    /** 乐观锁版本（必填） */
    private Integer version;
}
