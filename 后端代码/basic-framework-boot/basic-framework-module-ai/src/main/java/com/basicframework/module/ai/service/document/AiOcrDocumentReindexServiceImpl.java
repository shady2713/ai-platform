package com.basicframework.module.ai.service.document;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID;

import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.service.document.dto.AiOcrDocumentReindexRequestDTO;
import com.basicframework.module.ai.service.document.dto.AiOcrDocumentReindexResultDTO;
import com.basicframework.module.ai.service.file.AiFileBusinessType;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionService;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionRequestDTO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionResultDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * OCR 识别稿重索引实现（X02）。
 *
 * <p>顺序：文档（404）→ 知识库启用（拒绝则不动）→ 生成识别稿（无正文即拒绝）→
 * 上传派生私有文件（A07 业务 ACL：必须是该知识库的知识文档）→ 既有入库（版本 + 任务同事务）。
 * 任何一步失败都不会留下"已上传但没人引用"的文件：入库失败由 K03 补偿解除引用，
 * 上传失败则根本没有文件。
 */
@Service
@RequiredArgsConstructor
public class AiOcrDocumentReindexServiceImpl implements AiOcrDocumentReindexService {

    /** 派生识别稿的 MIME（与 `.txt` 扩展名一致，K04 按扩展名走文本解析）。 */
    private static final String DERIVED_CONTENT_TYPE = "text/plain";

    private final AiKnowledgeDocumentService documentService;

    private final AiKnowledgeBaseService baseService;

    private final AiFileService fileService;

    private final AiKnowledgeIngestionService ingestionService;

    private final AiOcrDocumentSource sourceBuilder;

    @Override
    public AiOcrDocumentReindexResultDTO reindex(AiOcrDocumentReindexRequestDTO request) {
        if (request == null
                || request.getDocumentId() == null
                || request.getSourceFileId() == null
                || request.getSourceFileId() <= 0) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        AiKnowledgeDocumentDO document = documentService.getDocument(request.getDocumentId());
        AiKnowledgeBaseDO knowledgeBase = baseService.requireEnabled(document.getKnowledgeBaseId());
        AiOcrDocumentSource.Source source = sourceBuilder.build(document.getTitle(), request.getPages());
        String sourceRef = "ocr:sourceFileId=" + request.getSourceFileId();
        Long derivedFileId = fileService
                .upload(
                        AiFileBusinessType.KNOWLEDGE_DOCUMENT.code(),
                        knowledgeBase.getCode(),
                        source.fileName(),
                        DERIVED_CONTENT_TYPE,
                        source.content())
                .getFileId();
        AiKnowledgeIngestionResultDTO ingestion = ingestionService.ingest(new AiKnowledgeIngestionRequestDTO()
                .setKnowledgeBaseId(knowledgeBase.getId())
                .setSourceKey(document.getSourceKey())
                .setTitle(document.getTitle())
                .setSourceType(document.getSourceType())
                .setSourceRef(sourceRef)
                .setFileId(derivedFileId)
                .setContentHash(source.sha256()));
        return new AiOcrDocumentReindexResultDTO()
                .setDocumentId(ingestion.getDocumentId())
                .setVersionId(ingestion.getVersionId())
                .setVersionNo(ingestion.getVersionNo())
                .setTaskId(ingestion.getTaskId())
                .setCreatedVersion(ingestion.isCreatedVersion())
                .setReused(ingestion.isReused())
                .setPageCount(source.pageCount())
                .setCharacterCount(source.characterCount())
                .setConfidenceSource(source.documentConfidenceSource())
                .setReviewRequired(true)
                .setSourceFileId(request.getSourceFileId())
                .setDerivedFileId(derivedFileId)
                .setSourceRef(sourceRef);
    }
}
