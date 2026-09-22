package com.basicframework.module.ai.service.knowledge;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_DOCUMENT_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_FILE_REQUIRED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_GENERATION_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_SOURCE_KEY_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_IMMUTABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_STATE_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeChunkMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeDocumentMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeDocumentVersionMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeIndexGenerationMapper;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentSaveDTO;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentUpsertResultDTO;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 知识文档与版本实现（K02）。
 *
 * <p>幂等键与指纹的关系：sourceKey 回答"这是同一个逻辑文档吗"，指纹回答"内容变了吗"。
 * 两者分开才能既避免重复文档（同 key 复用），又不丢更新（指纹变了就换版本）。
 *
 * <p>失败原因只写**稳定原因码**（调用方传入前已脱敏），并且截断到列宽；
 * 版本失败不回写 active 版本指针——旧内容仍可用（AT-024）。
 */
@Service
@RequiredArgsConstructor
public class AiKnowledgeDocumentServiceImpl implements AiKnowledgeDocumentService {

    /** 失败原因列宽（与迁移一致）。 */
    private static final int MAX_REASON_LENGTH = 128;

    /** 标题列宽（与迁移一致）。 */
    private static final int MAX_TITLE_LENGTH = 256;

    private final AiKnowledgeBaseService knowledgeBaseService;

    private final AiKnowledgeDocumentMapper documentMapper;

    private final AiKnowledgeDocumentVersionMapper versionMapper;

    private final AiKnowledgeChunkMapper chunkMapper;

    private final AiKnowledgeIndexGenerationMapper generationMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiKnowledgeDocumentUpsertResultDTO upsert(AiKnowledgeDocumentSaveDTO saveDTO) {
        if (saveDTO == null || saveDTO.getKnowledgeBaseId() == null) {
            throw exception(AI_KNOWLEDGE_SOURCE_KEY_INVALID);
        }
        AiKnowledgeBaseDO knowledgeBase = knowledgeBaseService.requireEnabled(saveDTO.getKnowledgeBaseId());
        String sourceKey = AiKnowledgeStates.requireSourceKey(saveDTO.getSourceKey());
        String title = requireTitle(saveDTO.getTitle());
        String sourceType = AiKnowledgeStates.requireSourceType(saveDTO.getSourceType());
        if (saveDTO.getFileId() == null) {
            // 版本必须绑定私有文件：没有文件就没有可解析的正文
            throw exception(AI_KNOWLEDGE_FILE_REQUIRED);
        }
        String contentHash = AiKnowledgeStates.requireContentHash(saveDTO.getContentHash());

        AiKnowledgeDocumentDO existing = documentMapper.selectBySourceKey(knowledgeBase.getId(), sourceKey);
        if (existing == null) {
            AiKnowledgeDocumentDO document = new AiKnowledgeDocumentDO()
                    .setKnowledgeBaseId(knowledgeBase.getId())
                    .setSourceKey(sourceKey)
                    .setTitle(title)
                    .setSourceType(sourceType)
                    .setSourceRef(saveDTO.getSourceRef())
                    .setStatus(AiKnowledgeDocumentDO.STATUS_PENDING)
                    .setActiveVersionNo(0)
                    .setLatestVersionNo(1)
                    .setVersion(0);
            documentMapper.insert(document);
            AiKnowledgeDocumentVersionDO version = insertVersion(document, 1, saveDTO, contentHash);
            return result(document, version, false, true);
        }
        if (AiKnowledgeDocumentDO.STATUS_DELETING.equals(existing.getStatus())) {
            // 删除中的文档不接受新入库：先等 K07 回收完成
            throw exception(AI_KNOWLEDGE_VERSION_STATE_INVALID);
        }
        AiKnowledgeDocumentVersionDO latest =
                existing.getLatestVersionNo() == null || existing.getLatestVersionNo() == 0
                        ? null
                        : versionMapper.selectByVersionNo(existing.getId(), existing.getLatestVersionNo());
        if (latest != null && contentHash.equals(latest.getContentHash())) {
            return reuseOrRetry(existing, latest, saveDTO);
        }
        // 指纹变化（或尚无版本）：生成新版本，旧 active 版本继续可用
        int nextVersionNo = (existing.getLatestVersionNo() == null ? 0 : existing.getLatestVersionNo()) + 1;
        AiKnowledgeDocumentVersionDO version = insertVersion(existing, nextVersionNo, saveDTO, contentHash);
        if (documentMapper.updateWithVersion(
                        new AiKnowledgeDocumentDO()
                                .setId(existing.getId())
                                .setTitle(title)
                                .setSourceType(sourceType)
                                .setSourceRef(saveDTO.getSourceRef())
                                .setStatus(AiKnowledgeDocumentDO.STATUS_PENDING)
                                .setLatestVersionNo(nextVersionNo)
                                .setFailureReason(null)
                                .setVersion(existing.getVersion() + 1),
                        existing.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        return result(existing, version, true, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markParsing(Long documentId, Integer version) {
        advanceDocument(documentId, version, AiKnowledgeDocumentDO.STATUS_PARSING);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markIndexing(Long documentId, Integer version) {
        advanceDocument(documentId, version, AiKnowledgeDocumentDO.STATUS_INDEXING);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markVersionReady(Long documentId, Long versionId, Integer generationNo) {
        if (generationNo == null) {
            throw exception(AI_KNOWLEDGE_GENERATION_CONFLICT);
        }
        AiKnowledgeDocumentDO document = requireDocument(documentId);
        AiKnowledgeDocumentVersionDO version = requireVersion(versionId);
        if (!document.getId().equals(version.getDocumentId())) {
            throw exception(AI_KNOWLEDGE_VERSION_NOT_FOUND);
        }
        requireMutableIndexingVersion(version);
        if (generationMapper.selectByGenerationNo(version.getKnowledgeBaseId(), generationNo) == null) {
            // 切片必须属于已登记的索引代：否则引用无法追溯
            throw exception(AI_KNOWLEDGE_GENERATION_CONFLICT);
        }
        long chunkCount = chunkMapper.countByVersion(version.getId());
        if (versionMapper.updateWithVersion(
                        new AiKnowledgeDocumentVersionDO()
                                .setId(version.getId())
                                .setStatus(AiKnowledgeDocumentVersionDO.STATUS_READY)
                                .setIndexGeneration(generationNo)
                                .setChunkCount((int) chunkCount)
                                .setFailureReason(null)
                                .setReadyAt(LocalDateTime.now())
                                .setVersion(version.getVersion() + 1),
                        version.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        supersedePreviousActive(document, version);
        if (documentMapper.updateWithVersion(
                        new AiKnowledgeDocumentDO()
                                .setId(document.getId())
                                .setStatus(AiKnowledgeDocumentDO.STATUS_READY)
                                .setActiveVersionNo(version.getVersionNo())
                                .setFailureReason(null)
                                .setVersion(document.getVersion() + 1),
                        document.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markVersionFailed(Long documentId, Long versionId, String reason) {
        AiKnowledgeDocumentDO document = requireDocument(documentId);
        AiKnowledgeDocumentVersionDO version = requireVersion(versionId);
        if (!document.getId().equals(version.getDocumentId())) {
            throw exception(AI_KNOWLEDGE_VERSION_NOT_FOUND);
        }
        requireMutableIndexingVersion(version);
        String sanitized = sanitizeReason(reason);
        if (versionMapper.updateWithVersion(
                        new AiKnowledgeDocumentVersionDO()
                                .setId(version.getId())
                                .setStatus(AiKnowledgeDocumentVersionDO.STATUS_FAILED)
                                .setFailureReason(sanitized)
                                .setVersion(version.getVersion() + 1),
                        version.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        if (version.getVersionNo().equals(document.getLatestVersionNo())) {
            // 只有"最新版本的失败"才把文档标失败；旧版本的迟到失败不改文档状态
            documentMapper.updateWithVersion(
                    new AiKnowledgeDocumentDO()
                            .setId(document.getId())
                            .setStatus(AiKnowledgeDocumentDO.STATUS_FAILED)
                            .setFailureReason(sanitized)
                            .setVersion(document.getVersion() + 1),
                    document.getVersion());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long id, Integer version) {
        if (id == null || version == null) {
            throw exception(AI_KNOWLEDGE_SOURCE_KEY_INVALID);
        }
        AiKnowledgeDocumentDO existing = requireDocument(id);
        if (!AiKnowledgeStates.documentTransitionAllowed(existing.getStatus(), AiKnowledgeDocumentDO.STATUS_DELETING)) {
            throw exception(AI_KNOWLEDGE_VERSION_STATE_INVALID);
        }
        if (documentMapper.updateWithVersion(
                        new AiKnowledgeDocumentDO()
                                .setId(existing.getId())
                                .setStatus(AiKnowledgeDocumentDO.STATUS_DELETING)
                                .setVersion(existing.getVersion() + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    public AiKnowledgeDocumentDO getDocument(Long id) {
        return requireDocument(id);
    }

    @Override
    public PageResult<AiKnowledgeDocumentDO> getDocumentPage(PageParam pageParam, Long knowledgeBaseId, String status) {
        return documentMapper.selectPage(
                pageParam, knowledgeBaseId, status == null ? null : AiKnowledgeStates.requireDocumentStatus(status));
    }

    @Override
    public AiKnowledgeDocumentVersionDO getVersion(Long versionId) {
        return requireVersion(versionId);
    }

    @Override
    public List<AiKnowledgeDocumentVersionDO> listVersions(Long documentId) {
        requireDocument(documentId);
        return versionMapper.selectByDocument(documentId);
    }

    @Override
    public PageResult<AiKnowledgeDocumentVersionDO> getVersionPage(PageParam pageParam, Long documentId) {
        return versionMapper.selectPage(pageParam, documentId);
    }

    @Override
    public AiKnowledgeDocumentVersionDO getActiveVersion(Long documentId) {
        AiKnowledgeDocumentDO document = requireDocument(documentId);
        Integer activeVersionNo = document.getActiveVersionNo();
        if (activeVersionNo == null || activeVersionNo == 0) {
            return null;
        }
        return versionMapper.selectByVersionNo(document.getId(), activeVersionNo);
    }

    /** 同指纹：可用/索引中直接复用；失败版本重新排队（重试同一版本，不产生新版本）。 */
    private AiKnowledgeDocumentUpsertResultDTO reuseOrRetry(
            AiKnowledgeDocumentDO document, AiKnowledgeDocumentVersionDO latest, AiKnowledgeDocumentSaveDTO saveDTO) {
        if (AiKnowledgeDocumentVersionDO.STATUS_FAILED.equals(latest.getStatus())) {
            if (versionMapper.updateWithVersion(
                            new AiKnowledgeDocumentVersionDO()
                                    .setId(latest.getId())
                                    .setStatus(AiKnowledgeDocumentVersionDO.STATUS_INDEXING)
                                    .setFailureReason(null)
                                    .setVersion(latest.getVersion() + 1),
                            latest.getVersion())
                    == 0) {
                throw exception(AI_STATE_CONFLICT);
            }
            documentMapper.updateWithVersion(
                    new AiKnowledgeDocumentDO()
                            .setId(document.getId())
                            .setTitle(requireTitle(saveDTO.getTitle()))
                            .setStatus(AiKnowledgeDocumentDO.STATUS_PENDING)
                            .setFailureReason(null)
                            .setVersion(document.getVersion() + 1),
                    document.getVersion());
            return result(document, latest, true, false);
        }
        if (AiKnowledgeDocumentVersionDO.STATUS_INDEXING.equals(latest.getStatus())
                || AiKnowledgeDocumentVersionDO.STATUS_READY.equals(latest.getStatus())) {
            // 内容没变：复用既有版本，不产生第二次副作用
            return result(document, latest, true, false);
        }
        throw exception(AI_KNOWLEDGE_VERSION_STATE_INVALID);
    }

    private AiKnowledgeDocumentVersionDO insertVersion(
            AiKnowledgeDocumentDO document, int versionNo, AiKnowledgeDocumentSaveDTO saveDTO, String contentHash) {
        AiKnowledgeDocumentVersionDO version = new AiKnowledgeDocumentVersionDO()
                .setDocumentId(document.getId())
                .setKnowledgeBaseId(document.getKnowledgeBaseId())
                .setVersionNo(versionNo)
                .setFileId(saveDTO.getFileId())
                .setContentHash(contentHash)
                .setSourceRef(saveDTO.getSourceRef())
                .setStatus(AiKnowledgeDocumentVersionDO.STATUS_INDEXING)
                .setChunkCount(0)
                .setVersion(0);
        versionMapper.insert(version);
        return version;
    }

    /** 新版本可用后，旧 active 版本置 SUPERSEDED（行与切片保留，便于追溯）。 */
    private void supersedePreviousActive(AiKnowledgeDocumentDO document, AiKnowledgeDocumentVersionDO ready) {
        Integer activeVersionNo = document.getActiveVersionNo();
        if (activeVersionNo == null || activeVersionNo == 0 || activeVersionNo.equals(ready.getVersionNo())) {
            return;
        }
        AiKnowledgeDocumentVersionDO previous = versionMapper.selectByVersionNo(document.getId(), activeVersionNo);
        if (previous == null || !AiKnowledgeDocumentVersionDO.STATUS_READY.equals(previous.getStatus())) {
            return;
        }
        versionMapper.updateWithVersion(
                new AiKnowledgeDocumentVersionDO()
                        .setId(previous.getId())
                        .setStatus(AiKnowledgeDocumentVersionDO.STATUS_SUPERSEDED)
                        .setVersion(previous.getVersion() + 1),
                previous.getVersion());
    }

    private void advanceDocument(Long documentId, Integer version, String targetStatus) {
        if (version == null) {
            throw exception(AI_KNOWLEDGE_SOURCE_KEY_INVALID);
        }
        AiKnowledgeDocumentDO document = requireDocument(documentId);
        if (!AiKnowledgeStates.documentTransitionAllowed(document.getStatus(), targetStatus)) {
            throw exception(AI_KNOWLEDGE_VERSION_STATE_INVALID);
        }
        if (documentMapper.updateWithVersion(
                        new AiKnowledgeDocumentDO()
                                .setId(document.getId())
                                .setStatus(targetStatus)
                                .setVersion(document.getVersion() + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    /** 只有 INDEXING 版本可改：READY/SUPERSEDED 一律不可变（K02 的核心不变量）。 */
    private static void requireMutableIndexingVersion(AiKnowledgeDocumentVersionDO version) {
        if (version.immutable()) {
            throw exception(AI_KNOWLEDGE_VERSION_IMMUTABLE);
        }
        if (!AiKnowledgeDocumentVersionDO.STATUS_INDEXING.equals(version.getStatus())) {
            throw exception(AI_KNOWLEDGE_VERSION_STATE_INVALID);
        }
    }

    private static String sanitizeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "index-failed";
        }
        String single = reason.replace('\n', ' ').replace('\r', ' ').trim();
        return single.length() <= MAX_REASON_LENGTH ? single : single.substring(0, MAX_REASON_LENGTH);
    }

    private AiKnowledgeDocumentDO requireDocument(Long id) {
        AiKnowledgeDocumentDO document = id == null ? null : documentMapper.selectById(id);
        if (document == null) {
            throw exception(AI_KNOWLEDGE_DOCUMENT_NOT_FOUND);
        }
        return document;
    }

    private AiKnowledgeDocumentVersionDO requireVersion(Long versionId) {
        AiKnowledgeDocumentVersionDO version = versionId == null ? null : versionMapper.selectById(versionId);
        if (version == null) {
            throw exception(AI_KNOWLEDGE_VERSION_NOT_FOUND);
        }
        return version;
    }

    private static String requireTitle(String title) {
        if (!StringUtils.hasText(title) || title.trim().length() > MAX_TITLE_LENGTH) {
            throw exception(AI_KNOWLEDGE_SOURCE_KEY_INVALID);
        }
        return title.trim();
    }

    private static AiKnowledgeDocumentUpsertResultDTO result(
            AiKnowledgeDocumentDO document, AiKnowledgeDocumentVersionDO version, boolean reused, boolean created) {
        return new AiKnowledgeDocumentUpsertResultDTO()
                .setDocumentId(document.getId())
                .setVersionId(version.getId())
                .setVersionNo(version.getVersionNo())
                .setReused(reused)
                .setCreatedVersion(created)
                .setDocumentStatus(document.getStatus());
    }
}
