package com.basicframework.module.ai.service.knowledge;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_CHUNK_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_GENERATION_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_IMMUTABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_STATE_INVALID;

import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeChunkDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeChunkMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeDocumentVersionMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeIndexGenerationMapper;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeChunkDTO;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识切片实现（K02）。
 *
 * <p>校验在写入前完成（序号唯一、向量标识必填、哈希为 sha256、索引代已登记），
 * 这样"库里出现无法追溯的切片"是不可能的，而不是靠事后清理。
 */
@Service
@RequiredArgsConstructor
public class AiKnowledgeChunkServiceImpl implements AiKnowledgeChunkService {

    private final AiKnowledgeChunkMapper chunkMapper;

    private final AiKnowledgeDocumentVersionMapper versionMapper;

    private final AiKnowledgeIndexGenerationMapper generationMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int replaceVersionChunks(Long documentVersionId, Integer generationNo, List<AiKnowledgeChunkDTO> chunks) {
        AiKnowledgeDocumentVersionDO version = requireVersion(documentVersionId);
        if (version.immutable()) {
            // READY/SUPERSEDED 的切片是引用依据：改它等于篡改历史引用
            throw exception(AI_KNOWLEDGE_VERSION_IMMUTABLE);
        }
        if (!AiKnowledgeDocumentVersionDO.STATUS_INDEXING.equals(version.getStatus())) {
            throw exception(AI_KNOWLEDGE_VERSION_STATE_INVALID);
        }
        if (generationNo == null
                || generationMapper.selectByGenerationNo(version.getKnowledgeBaseId(), generationNo) == null) {
            throw exception(AI_KNOWLEDGE_GENERATION_CONFLICT);
        }
        if (chunks == null || chunks.isEmpty()) {
            // 没有切片说明正文不可用：应当由流水线标记失败，而不是写入"可用但空"的版本
            throw exception(AI_KNOWLEDGE_CHUNK_INVALID);
        }
        Set<Integer> indexes = new HashSet<>();
        for (AiKnowledgeChunkDTO chunk : chunks) {
            if (chunk == null
                    || chunk.getChunkIndex() == null
                    || chunk.getChunkIndex() < 0
                    || !indexes.add(chunk.getChunkIndex())) {
                throw exception(AI_KNOWLEDGE_CHUNK_INVALID);
            }
            AiKnowledgeStates.requireContentHash(chunk.getContentHash());
            AiKnowledgeStates.requireVectorId(chunk.getVectorId());
            if (chunk.getTextLength() == null
                    || chunk.getTextLength() < 0
                    || chunk.getTokenCount() == null
                    || chunk.getTokenCount() < 0) {
                throw exception(AI_KNOWLEDGE_CHUNK_INVALID);
            }
        }
        chunkMapper.deleteByVersion(documentVersionId);
        int written = 0;
        for (AiKnowledgeChunkDTO chunk : chunks) {
            chunkMapper.insert(new AiKnowledgeChunkDO()
                    .setDocumentVersionId(documentVersionId)
                    .setKnowledgeBaseId(version.getKnowledgeBaseId())
                    .setChunkIndex(chunk.getChunkIndex())
                    .setContentHash(chunk.getContentHash().trim().toLowerCase(java.util.Locale.ROOT))
                    .setTextLength(chunk.getTextLength())
                    .setTokenCount(chunk.getTokenCount())
                    .setVectorId(chunk.getVectorId())
                    .setIndexGeneration(generationNo)
                    .setLocationRef(chunk.getLocationRef()));
            written++;
        }
        return written;
    }

    @Override
    public List<AiKnowledgeChunkDO> listVersionChunks(Long documentVersionId) {
        requireVersion(documentVersionId);
        return chunkMapper.selectByVersion(documentVersionId);
    }

    @Override
    public long countByGeneration(Long knowledgeBaseId, Integer generationNo) {
        if (knowledgeBaseId == null || generationNo == null) {
            throw exception(AI_KNOWLEDGE_CHUNK_INVALID);
        }
        return chunkMapper.countByGeneration(knowledgeBaseId, generationNo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteVersionChunks(Long documentVersionId) {
        requireVersion(documentVersionId);
        return chunkMapper.deleteByVersion(documentVersionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteGenerationChunks(Long knowledgeBaseId, Integer generationNo) {
        if (knowledgeBaseId == null || generationNo == null) {
            throw exception(AI_KNOWLEDGE_CHUNK_INVALID);
        }
        return chunkMapper.deleteByGeneration(knowledgeBaseId, generationNo);
    }

    private AiKnowledgeDocumentVersionDO requireVersion(Long documentVersionId) {
        AiKnowledgeDocumentVersionDO version =
                documentVersionId == null ? null : versionMapper.selectById(documentVersionId);
        if (version == null) {
            throw exception(AI_KNOWLEDGE_VERSION_NOT_FOUND);
        }
        return version;
    }
}
