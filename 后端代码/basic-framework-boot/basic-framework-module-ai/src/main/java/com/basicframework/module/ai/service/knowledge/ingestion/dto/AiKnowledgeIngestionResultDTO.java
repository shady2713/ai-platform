package com.basicframework.module.ai.service.knowledge.ingestion.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 入库结论（服务层 DTO）：文档/版本/任务编号 + 幂等三态。 */
@Data
@Accessors(chain = true)
public class AiKnowledgeIngestionResultDTO {

    /** 文档编号 */
    private Long documentId;

    /** 版本编号 */
    private Long versionId;

    /** 版本号 */
    private Integer versionNo;

    /** 任务编号（复用既有版本且已有任务时返回该任务） */
    private Long taskId;

    /** 是否复用既有文档（同 sourceKey） */
    private boolean reused;

    /** 是否产生新版本 */
    private boolean createdVersion;
}
