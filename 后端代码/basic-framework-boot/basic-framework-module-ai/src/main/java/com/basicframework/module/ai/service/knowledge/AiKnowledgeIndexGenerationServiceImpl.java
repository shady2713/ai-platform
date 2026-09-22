package com.basicframework.module.ai.service.knowledge;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_BASE_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_GENERATION_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_STATE_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeChunkMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeIndexGenerationMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 知识索引代实现（K02）。
 *
 * <p>集合名由"库标识 + 代序号"派生（{@code kb_<code>_g<no>}）：名字可读、可复现，
 * 且换模型换代时物理隔离——K01 已证明"同向量不同集合/不同租户不互相命中"。
 */
@Service
@RequiredArgsConstructor
public class AiKnowledgeIndexGenerationServiceImpl implements AiKnowledgeIndexGenerationService {

    /** 失败原因列宽（与迁移一致）。 */
    private static final int MAX_REASON_LENGTH = 128;

    private final AiKnowledgeIndexGenerationMapper generationMapper;

    private final AiKnowledgeBaseMapper baseMapper;

    private final AiKnowledgeChunkMapper chunkMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer startGeneration(Long knowledgeBaseId) {
        AiKnowledgeBaseDO knowledgeBase = requireBase(knowledgeBaseId);
        if (!AiKnowledgeBaseDO.STATUS_ENABLED.equals(knowledgeBase.getStatus())) {
            throw exception(AI_KNOWLEDGE_VERSION_STATE_INVALID);
        }
        if (generationMapper.selectBuilding(knowledgeBaseId) != null) {
            // 同时只允许一个构建中的索引代：并发换代会让两批切片互相覆盖
            throw exception(AI_KNOWLEDGE_GENERATION_CONFLICT);
        }
        int nextNo = generationMapper.selectByKnowledgeBase(knowledgeBaseId).stream()
                        .map(AiKnowledgeIndexGenerationDO::getGenerationNo)
                        .filter(java.util.Objects::nonNull)
                        .max(Integer::compareTo)
                        .orElse(0)
                + 1;
        AiKnowledgeIndexGenerationDO generation = new AiKnowledgeIndexGenerationDO()
                .setKnowledgeBaseId(knowledgeBaseId)
                .setGenerationNo(nextNo)
                .setEmbeddingModel(knowledgeBase.getEmbeddingModel())
                .setDimension(knowledgeBase.getEmbeddingDimension())
                .setCollectionName(collectionName(knowledgeBase.getCode(), nextNo))
                .setStatus(AiKnowledgeIndexGenerationDO.STATUS_BUILDING)
                .setChunkCount(0)
                .setDocumentCount(0)
                .setVersion(0);
        generationMapper.insert(generation);
        return nextNo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void activate(Long knowledgeBaseId, Integer generationNo) {
        AiKnowledgeBaseDO knowledgeBase = requireBase(knowledgeBaseId);
        AiKnowledgeIndexGenerationDO generation = requireGeneration(knowledgeBaseId, generationNo);
        if (!AiKnowledgeStates.generationTransitionAllowed(
                generation.getStatus(), AiKnowledgeIndexGenerationDO.STATUS_ACTIVE)) {
            throw exception(AI_KNOWLEDGE_VERSION_STATE_INVALID);
        }
        if (!java.util.Objects.equals(generation.getDimension(), knowledgeBase.getEmbeddingDimension())
                || !java.util.Objects.equals(generation.getEmbeddingModel(), knowledgeBase.getEmbeddingModel())) {
            // 维度/模型必须与知识库声明一致：不一致说明配置被改过，拒绝激活
            throw exception(AI_KNOWLEDGE_GENERATION_CONFLICT);
        }
        long chunkCount = chunkMapper.countByGeneration(knowledgeBaseId, generationNo);
        long documentCount = chunkMapper.countDistinctVersions(knowledgeBaseId, generationNo);
        AiKnowledgeIndexGenerationDO previous = generationMapper.selectActive(knowledgeBaseId);
        if (previous != null && !previous.getId().equals(generation.getId())) {
            generationMapper.updateWithVersion(
                    new AiKnowledgeIndexGenerationDO()
                            .setId(previous.getId())
                            .setStatus(AiKnowledgeIndexGenerationDO.STATUS_RETIRED)
                            .setRetiredAt(LocalDateTime.now())
                            .setVersion(previous.getVersion() + 1),
                    previous.getVersion());
        }
        if (generationMapper.updateWithVersion(
                        new AiKnowledgeIndexGenerationDO()
                                .setId(generation.getId())
                                .setStatus(AiKnowledgeIndexGenerationDO.STATUS_ACTIVE)
                                .setChunkCount((int) chunkCount)
                                .setDocumentCount((int) documentCount)
                                .setFailureReason(null)
                                .setActivatedAt(LocalDateTime.now())
                                .setVersion(generation.getVersion() + 1),
                        generation.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        if (baseMapper.updateWithVersion(
                        new AiKnowledgeBaseDO()
                                .setId(knowledgeBase.getId())
                                .setActiveGenerationNo(generationNo)
                                .setVersion(knowledgeBase.getVersion() + 1),
                        knowledgeBase.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void fail(Long knowledgeBaseId, Integer generationNo, String reason) {
        AiKnowledgeIndexGenerationDO generation = requireGeneration(knowledgeBaseId, generationNo);
        if (!AiKnowledgeStates.generationTransitionAllowed(
                generation.getStatus(), AiKnowledgeIndexGenerationDO.STATUS_FAILED)) {
            throw exception(AI_KNOWLEDGE_VERSION_STATE_INVALID);
        }
        generationMapper.updateWithVersion(
                new AiKnowledgeIndexGenerationDO()
                        .setId(generation.getId())
                        .setStatus(AiKnowledgeIndexGenerationDO.STATUS_FAILED)
                        .setFailureReason(sanitizeReason(reason))
                        .setVersion(generation.getVersion() + 1),
                generation.getVersion());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retire(Long knowledgeBaseId, Integer generationNo) {
        AiKnowledgeBaseDO knowledgeBase = requireBase(knowledgeBaseId);
        AiKnowledgeIndexGenerationDO generation = requireGeneration(knowledgeBaseId, generationNo);
        if (!AiKnowledgeStates.generationTransitionAllowed(
                generation.getStatus(), AiKnowledgeIndexGenerationDO.STATUS_RETIRED)) {
            throw exception(AI_KNOWLEDGE_VERSION_STATE_INVALID);
        }
        generationMapper.updateWithVersion(
                new AiKnowledgeIndexGenerationDO()
                        .setId(generation.getId())
                        .setStatus(AiKnowledgeIndexGenerationDO.STATUS_RETIRED)
                        .setRetiredAt(LocalDateTime.now())
                        .setVersion(generation.getVersion() + 1),
                generation.getVersion());
        if (generationNo.equals(knowledgeBase.getActiveGenerationNo())) {
            // 退役当前生效的一代：清空指针（检索将找不到可用索引，而不是继续用退役内容）
            baseMapper.updateWithVersion(
                    new AiKnowledgeBaseDO()
                            .setId(knowledgeBase.getId())
                            .setActiveGenerationNo(0)
                            .setVersion(knowledgeBase.getVersion() + 1),
                    knowledgeBase.getVersion());
        }
    }

    @Override
    public AiKnowledgeIndexGenerationDO getGeneration(Long knowledgeBaseId, Integer generationNo) {
        return requireGeneration(knowledgeBaseId, generationNo);
    }

    @Override
    public AiKnowledgeIndexGenerationDO getActiveGeneration(Long knowledgeBaseId) {
        return generationMapper.selectActive(knowledgeBaseId);
    }

    @Override
    public List<AiKnowledgeIndexGenerationDO> listGenerations(Long knowledgeBaseId) {
        requireBase(knowledgeBaseId);
        return generationMapper.selectByKnowledgeBase(knowledgeBaseId);
    }

    /** 物理索引名：库标识 + 代序号（可读、可复现、换代即隔离）。 */
    static String collectionName(String code, int generationNo) {
        return "kb_" + code + "_g" + generationNo;
    }

    private AiKnowledgeBaseDO requireBase(Long knowledgeBaseId) {
        AiKnowledgeBaseDO knowledgeBase = knowledgeBaseId == null ? null : baseMapper.selectById(knowledgeBaseId);
        if (knowledgeBase == null) {
            throw exception(AI_KNOWLEDGE_BASE_NOT_FOUND);
        }
        return knowledgeBase;
    }

    private AiKnowledgeIndexGenerationDO requireGeneration(Long knowledgeBaseId, Integer generationNo) {
        AiKnowledgeIndexGenerationDO generation =
                generationNo == null ? null : generationMapper.selectByGenerationNo(knowledgeBaseId, generationNo);
        if (generation == null) {
            throw exception(AI_KNOWLEDGE_VERSION_STATE_INVALID);
        }
        return generation;
    }

    private static String sanitizeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "index-failed";
        }
        String single = reason.replace('\n', ' ').replace('\r', ' ').trim();
        return single.length() <= MAX_REASON_LENGTH ? single : single.substring(0, MAX_REASON_LENGTH);
    }
}
