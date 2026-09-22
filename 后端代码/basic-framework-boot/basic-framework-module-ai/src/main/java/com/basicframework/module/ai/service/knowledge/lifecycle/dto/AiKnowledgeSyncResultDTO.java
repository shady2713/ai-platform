package com.basicframework.module.ai.service.knowledge.lifecycle.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 同步结论（K07）：按 sourceKey 复用/更新后的文档、版本与入库任务。 */
@Data
@Accessors(chain = true)
public class AiKnowledgeSyncResultDTO {

    /** 文档编号 */
    private Long documentId;

    /** 版本编号 */
    private Long versionId;

    /** 版本号 */
    private Integer versionNo;

    /** 入库任务编号 */
    private Long taskId;

    /** 是否复用既有文档（同 sourceKey） */
    private boolean reused;

    /** 是否产生新版本（内容指纹变化） */
    private boolean createdVersion;
}
