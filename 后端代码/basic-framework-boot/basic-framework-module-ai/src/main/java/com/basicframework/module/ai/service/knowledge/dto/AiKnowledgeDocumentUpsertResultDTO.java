package com.basicframework.module.ai.service.knowledge.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 文档入库结论（服务层 DTO）：幂等的三种结果必须可区分。
 *
 * <ul>
 *   <li>{@code reused=true, createdVersion=false}：同 sourceKey 且同指纹——**复用**既有版本，不产生新版本；</li>
 *   <li>{@code reused=true, createdVersion=true}：同 sourceKey 但指纹变了——**更新**：生成新版本（待索引）；</li>
 *   <li>{@code reused=false, createdVersion=true}：新 sourceKey——新建文档与首个版本。</li>
 * </ul>
 * 调用方（K03/K07 的同步链路）据此区分"无变化/有更新/新文档"，重放同一请求不会产生第二次副作用。
 */
@Data
@Accessors(chain = true)
public class AiKnowledgeDocumentUpsertResultDTO {

    /** 文档编号 */
    private Long documentId;

    /** 本次涉及的版本编号 */
    private Long versionId;

    /** 本次涉及的版本号 */
    private Integer versionNo;

    /** 是否复用既有文档（同 sourceKey） */
    private boolean reused;

    /** 是否产生了新版本（指纹变化或首次入库） */
    private boolean createdVersion;

    /** 文档状态（入库后的状态） */
    private String documentStatus;
}
