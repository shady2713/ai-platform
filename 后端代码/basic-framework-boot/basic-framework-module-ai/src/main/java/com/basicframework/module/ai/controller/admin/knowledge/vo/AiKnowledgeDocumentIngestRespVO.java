package com.basicframework.module.ai.controller.admin.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 文档入库结论（协议层 VO）：复用/更新/新建三种结果可区分。 */
@Schema(description = "管理后台 - AI 知识文档入库结论")
@Data
@Accessors(chain = true)
public class AiKnowledgeDocumentIngestRespVO {

    @Schema(description = "文档编号")
    private Long documentId;

    @Schema(description = "本次涉及的版本编号")
    private Long versionId;

    @Schema(description = "本次涉及的版本号")
    private Integer versionNo;

    @Schema(description = "是否复用既有文档（同 sourceKey）")
    private Boolean reused;

    @Schema(description = "是否产生了新版本（指纹变化或首次入库）")
    private Boolean createdVersion;

    @Schema(description = "文档状态")
    private String documentStatus;
}
