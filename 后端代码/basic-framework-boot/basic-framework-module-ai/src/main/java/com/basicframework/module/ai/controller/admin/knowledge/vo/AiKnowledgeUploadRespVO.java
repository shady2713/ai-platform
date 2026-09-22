package com.basicframework.module.ai.controller.admin.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 文档上传入库结论（协议层 VO）：文档/版本/任务编号 + 幂等三态。 */
@Schema(description = "管理后台 - AI 知识文档上传入库结论")
@Data
@Accessors(chain = true)
public class AiKnowledgeUploadRespVO {

    @Schema(description = "文档编号")
    private Long documentId;

    @Schema(description = "版本编号")
    private Long versionId;

    @Schema(description = "版本号")
    private Integer versionNo;

    @Schema(description = "入库任务编号")
    private Long taskId;

    @Schema(description = "是否复用既有文档（同 sourceKey）")
    private Boolean reused;

    @Schema(description = "是否产生新版本")
    private Boolean createdVersion;
}
