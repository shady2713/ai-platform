package com.basicframework.module.ai.service.knowledge;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentSaveDTO;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentUpsertResultDTO;
import java.util.List;

/**
 * 知识文档与版本（K02）：sourceKey 幂等入库 + 私有文件绑定 + active 版本切换。
 *
 * <p>不变量（K02 的验收项）：
 * <ol>
 *   <li><b>重复 sourceKey 复用/更新</b>：同库同 sourceKey 在存活行中唯一；指纹相同即复用（不产生新版本），
 *       指纹变化才生成新版本（{@link AiKnowledgeDocumentUpsertResultDTO} 区分三种结论）；</li>
 *   <li><b>版本发布后不可修改</b>：READY/SUPERSEDED 版本禁止再改（含标记失败、重写切片），
 *       只能新建版本——否则历史引用会指向被改写的内容；</li>
 *   <li><b>active 版本只在索引成功后切换</b>：失败保留旧可用版本（AT-024），
 *       旧版本在切换时置 SUPERSEDED 但行与切片保留以便追溯；</li>
 *   <li><b>私有文件绑定</b>：版本必须绑定 A07 业务文件（业务类型 ai_knowledge_document），
 *       文件读取仍按当前归属判定（授权回收后立即读不到）。</li>
 * </ol>
 */
public interface AiKnowledgeDocumentService {

    /** 按 sourceKey 幂等入库（复用 / 更新 / 新建三种结论）。 */
    AiKnowledgeDocumentUpsertResultDTO upsert(AiKnowledgeDocumentSaveDTO saveDTO);

    /** 流水线推进：排队 → 解析中。 */
    void markParsing(Long documentId, Integer version);

    /** 流水线推进：解析中/排队 → 切分与向量化中。 */
    void markIndexing(Long documentId, Integer version);

    /** 索引成功：版本置 READY（切片数按库内切片统计），并切换文档 active 版本。 */
    void markVersionReady(Long documentId, Long versionId, Integer generationNo);

    /** 索引失败：版本置 FAILED（旧 active 版本不受影响）。 */
    void markVersionFailed(Long documentId, Long versionId, String reason);

    /** 标记删除中（索引与切片由 K07 回收）。 */
    void deleteDocument(Long id, Integer version);

    /** 查询文档（不存在抛 404）。 */
    AiKnowledgeDocumentDO getDocument(Long id);

    /** 分页查询文档。 */
    PageResult<AiKnowledgeDocumentDO> getDocumentPage(PageParam pageParam, Long knowledgeBaseId, String status);

    /** 查询版本（不存在抛 404）。 */
    AiKnowledgeDocumentVersionDO getVersion(Long versionId);

    /** 某文档的版本（版本号倒序）。 */
    List<AiKnowledgeDocumentVersionDO> listVersions(Long documentId);

    /** 分页查询版本。 */
    PageResult<AiKnowledgeDocumentVersionDO> getVersionPage(PageParam pageParam, Long documentId);

    /** 当前可用版本（没有可用版本返回 null）。 */
    AiKnowledgeDocumentVersionDO getActiveVersion(Long documentId);
}
