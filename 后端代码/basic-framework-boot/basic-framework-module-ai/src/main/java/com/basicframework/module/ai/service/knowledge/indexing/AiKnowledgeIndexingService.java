package com.basicframework.module.ai.service.knowledge.indexing;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_IMMUTABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_STATE_INVALID;

import com.basicframework.module.ai.adapter.document.DocumentParseException;
import com.basicframework.module.ai.adapter.document.DocumentParser;
import com.basicframework.module.ai.adapter.document.ParsedDocument;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeIndexException;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeIndexPort;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeSourceException;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeChunkService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeIndexGenerationService;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeChunkDTO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 索引流水线（K05）：读原文 → 解析 → 切片 → 批量嵌入 → 写向量与切片 → 全部成功才切 active。
 *
 * <p>三条不变量：
 * <ol>
 *   <li><b>幂等</b>：向量点标识由 (版本, chunker 版本, 序号) 确定性派生，切片行按版本整表替换——
 *       中途失败重跑不会产生重复切片或重复向量（AT-024 的前置）；</li>
 *   <li><b>全成功才切 active</b>：只有全部切片写入成功才激活索引代并切换文档 active 版本；
 *       任何一步失败都不激活，旧可用版本继续服务；</li>
 *   <li><b>模型与维度不可混用</b>：嵌入模型来自知识库声明，维度必须与知识库声明一致
 *       （模型中心另有同维度不同 revision 的校验），否则稳定失败而不是"先写进去再说"（AT-029）。</li>
 * </ol>
 *
 * <p>失败只返回稳定原因码（`parse-*` / `embed-*` / `source-*` / `index-*`），不含原文与上游报文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiKnowledgeIndexingService {

    /** 单次嵌入请求的文本条数上限（批量而不超大，失败重试代价可控）。 */
    private static final int EMBEDDING_BATCH_SIZE = 32;

    private final AiKnowledgeBaseService baseService;

    private final AiKnowledgeDocumentService documentService;

    private final AiKnowledgeIndexGenerationService generationService;

    private final AiKnowledgeChunkService chunkService;

    private final AiKnowledgeSourceReader sourceReader;

    private final DocumentParser documentParser;

    private final AiKnowledgeChunker chunker;

    private final AiKnowledgeEmbeddingClient embeddingClient;

    /** 向量索引端口（未配置向量服务时按稳定原因码失败，而不是假装索引成功）。 */
    private final ObjectProvider<KnowledgeIndexPort> indexPortProvider;

    /** 索引一个文档版本；返回结论（成功/失败 + 稳定原因码 + 切片数）。 */
    public IndexingOutcome index(Long documentId, Long documentVersionId) {
        AiKnowledgeDocumentVersionDO version = documentService.getVersion(documentVersionId);
        if (version.immutable()) {
            throw exception(AI_KNOWLEDGE_VERSION_IMMUTABLE);
        }
        if (!AiKnowledgeDocumentVersionDO.STATUS_INDEXING.equals(version.getStatus())) {
            throw exception(AI_KNOWLEDGE_VERSION_STATE_INVALID);
        }
        AiKnowledgeBaseDO knowledgeBase = baseService.requireEnabled(version.getKnowledgeBaseId());
        KnowledgeIndexPort indexPort = indexPortProvider.getIfAvailable();
        if (indexPort == null) {
            return IndexingOutcome.failed("index-service-unavailable");
        }
        ParsedDocument parsed;
        try {
            AiKnowledgeSourceReader.KnowledgeSource source = sourceReader.read(version.getFileId());
            parsed = documentParser.parse(source.content(), source.fileName());
        } catch (KnowledgeSourceException failure) {
            return IndexingOutcome.failed(failure.reasonCode());
        } catch (DocumentParseException failure) {
            return IndexingOutcome.failed(failure.reasonCode());
        }
        if (parsed.requiresOcr()) {
            // 扫描件：不生成占位正文，交回流水线标记失败并提示需要 OCR
            return IndexingOutcome.failed("parse-ocr_required");
        }
        List<AiKnowledgeChunk> chunks = chunker.chunk(documentVersionId, parsed.segments());
        if (chunks.isEmpty()) {
            return IndexingOutcome.failed("parse-empty");
        }
        AiKnowledgeIndexGenerationDO generation = ensureGeneration(knowledgeBase);
        try {
            // 先嵌入再建集合：维度不一致在写任何索引数据之前就被拦下（AT-029）
            List<float[]> vectors = embedAll(knowledgeBase, chunks);
            indexPort.ensureCollection(generation.getCollectionName(), knowledgeBase.getEmbeddingDimension());
            indexPort.upsert(generation.getCollectionName(), toIndexDocuments(version, chunks, vectors));
            chunkService.replaceVersionChunks(documentVersionId, generation.getGenerationNo(), toChunkDTOs(chunks));
        } catch (AiKnowledgeEmbeddingException failure) {
            return IndexingOutcome.failed(failure.reasonCode());
        } catch (KnowledgeIndexException failure) {
            return IndexingOutcome.failed("index-upstream-failed");
        } catch (RuntimeException failure) {
            log.warn(
                    "[index][索引写入失败][documentVersionId={}, reason={}]",
                    documentVersionId,
                    failure.getClass().getSimpleName());
            return IndexingOutcome.failed("index-write-failed");
        }
        // 全部切片写入成功：先激活索引代，再切换文档 active 版本
        generationService.activate(knowledgeBase.getId(), generation.getGenerationNo());
        documentService.markVersionReady(documentId, documentVersionId, generation.getGenerationNo());
        return IndexingOutcome.indexed(chunks.size());
    }

    /** 找当前构建中的索引代；没有就开一代（同库同时只允许一个 BUILDING）。 */
    private AiKnowledgeIndexGenerationDO ensureGeneration(AiKnowledgeBaseDO knowledgeBase) {
        return generationService.listGenerations(knowledgeBase.getId()).stream()
                .filter(candidate -> AiKnowledgeIndexGenerationDO.STATUS_BUILDING.equals(candidate.getStatus()))
                .findFirst()
                .orElseGet(() -> {
                    Integer generationNo = generationService.startGeneration(knowledgeBase.getId());
                    return generationService.getGeneration(knowledgeBase.getId(), generationNo);
                });
    }

    /** 批量嵌入：维度必须与知识库声明一致（不同模型/不同 revision 一律拒绝混用）。 */
    private List<float[]> embedAll(AiKnowledgeBaseDO knowledgeBase, List<AiKnowledgeChunk> chunks) {
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (int start = 0; start < chunks.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, chunks.size());
            List<String> texts = chunks.subList(start, end).stream()
                    .map(AiKnowledgeChunk::text)
                    .toList();
            AiKnowledgeEmbeddingClient.EmbeddingBatch batch =
                    embeddingClient.embed(knowledgeBase.getEmbeddingModel(), texts);
            if (batch.dimension() != knowledgeBase.getEmbeddingDimension()) {
                throw new AiKnowledgeEmbeddingException(
                        AiKnowledgeEmbeddingException.Reason.DIMENSION_MISMATCH, "knowledge-base");
            }
            vectors.addAll(batch.vectors());
        }
        if (vectors.size() != chunks.size()) {
            throw new AiKnowledgeEmbeddingException(AiKnowledgeEmbeddingException.Reason.RESPONSE_INVALID, "count");
        }
        return vectors;
    }

    /** 向量载荷：只放检索与引用所需的最小字段（正文 + 归属 + 位置）。 */
    private List<KnowledgeIndexPort.IndexDocument> toIndexDocuments(
            AiKnowledgeDocumentVersionDO version, List<AiKnowledgeChunk> chunks, List<float[]> vectors) {
        List<KnowledgeIndexPort.IndexDocument> documents = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            AiKnowledgeChunk chunk = chunks.get(index);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("knowledge_base_id", String.valueOf(version.getKnowledgeBaseId()));
            payload.put("document_version_id", String.valueOf(version.getId()));
            payload.put("chunk_index", String.valueOf(chunk.index()));
            payload.put("location_ref", chunk.locationRef());
            payload.put("text", chunk.text());
            documents.add(new KnowledgeIndexPort.IndexDocument(chunk.vectorId(), vectors.get(index), payload));
        }
        return documents;
    }

    private List<AiKnowledgeChunkDTO> toChunkDTOs(List<AiKnowledgeChunk> chunks) {
        List<AiKnowledgeChunkDTO> dtos = new ArrayList<>(chunks.size());
        for (AiKnowledgeChunk chunk : chunks) {
            dtos.add(new AiKnowledgeChunkDTO()
                    .setChunkIndex(chunk.index())
                    .setContentHash(chunk.contentHash())
                    .setTextLength(chunk.characterCount())
                    .setTokenCount(estimateTokens(chunk.characterCount()))
                    .setVectorId(chunk.vectorId())
                    .setLocationRef(chunk.locationRef()));
        }
        return dtos;
    }

    /** token 估算（中文按 1 字符≈1 token 的上界估计；只用于上下文预算，不做精确计费）。 */
    private static int estimateTokens(int characterCount) {
        return Math.max(1, characterCount);
    }

    /** 索引结论：成功（切片数）或失败（稳定原因码）。 */
    public record IndexingOutcome(boolean indexed, int chunkCount, String reasonCode) {

        static IndexingOutcome indexed(int chunkCount) {
            return new IndexingOutcome(true, chunkCount, null);
        }

        static IndexingOutcome failed(String reasonCode) {
            return new IndexingOutcome(false, 0, StringUtils.hasText(reasonCode) ? reasonCode : "index-failed");
        }
    }
}
