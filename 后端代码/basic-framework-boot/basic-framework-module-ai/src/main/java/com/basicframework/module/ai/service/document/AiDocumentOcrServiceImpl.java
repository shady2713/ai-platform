package com.basicframework.module.ai.service.document;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_ACCESS_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID;

import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.document.dto.AiDocumentOcrRequestDTO;
import com.basicframework.module.ai.service.document.dto.AiOcrDocumentReindexRequestDTO;
import com.basicframework.module.ai.service.document.dto.AiOcrDocumentReindexResultDTO;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.vision.AiVisionLimits;
import com.basicframework.module.ai.service.vision.AiVisionService;
import com.basicframework.module.ai.service.vision.dto.AiVisionImageRefDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionOcrRequestDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionOcrResultDTO;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 扫描件 OCR 重索引实现（X02）。
 *
 * <p>顺序即安全语义：主体解析 → 文档/知识库解析 → **对知识库的 READ 授权** → 逐页 OCR
 * （每页都经 {@code AiVisionService} 的能力准入、业务文件 ACL 与图片内容核验）→ 重索引。
 * 因此未授权的主体既不会触发模型调用，也不会写入任何版本。
 *
 * <p>授权动作说明：A03 的动作白名单只有 READ/EXECUTE/EXPORT，没有"写"动作；本路径要求 READ 与 A07
 * 上传知识文档文件的既有判定一致——真正的写入仍然要过 A07 的业务 ACL（上传派生文件时必须绑定该知识库）。
 */
@Service
@RequiredArgsConstructor
public class AiDocumentOcrServiceImpl implements AiDocumentOcrService {

    private final AiConversationSubjectResolver subjectResolver;

    private final AiAuthorizationService authorizationService;

    private final AiKnowledgeDocumentService documentService;

    private final AiKnowledgeBaseService baseService;

    private final AiVisionService visionService;

    private final AiOcrDocumentReindexService reindexService;

    @Override
    public AiOcrDocumentReindexResultDTO recognizeAndReindex(AiDocumentOcrRequestDTO request) {
        if (request == null || request.getDocumentId() == null || request.getEndpointId() == null) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        List<AiVisionImageRefDTO> pageImages = requirePageImages(request.getPageImages());
        AiConversationSubject subject = subjectResolver.resolveCurrent().orElseThrow(() -> exception(AI_ACCESS_DENIED));
        AiKnowledgeDocumentDO document = documentService.getDocument(request.getDocumentId());
        AiKnowledgeBaseDO knowledgeBase = baseService.requireEnabled(document.getKnowledgeBaseId());
        requireReadAuthorization(subject, knowledgeBase);
        List<AiOcrPage> pages = new ArrayList<>(pageImages.size());
        for (int index = 0; index < pageImages.size(); index++) {
            AiVisionOcrResultDTO result = visionService.recognizeText(new AiVisionOcrRequestDTO()
                    .setEndpointId(request.getEndpointId())
                    .setImage(pageImages.get(index))
                    .setLanguageHint(request.getLanguageHint())
                    .setTimeoutMillis(request.getTimeoutMillis()));
            // 页码按调用方给出的页序（从 1 开始），不是按返回顺序猜测
            pages.add(new AiOcrPage(index + 1, result.getText(), result.getConfidenceSource()));
        }
        return reindexService.reindex(new AiOcrDocumentReindexRequestDTO()
                .setDocumentId(document.getId())
                // 追溯指向第一页图片文件：识别稿的来源位置只记受控标识，不记路径
                .setSourceFileId(pageImages.get(0).getFileId())
                .setPages(pages));
    }

    /** 页数上限在调用模型之前判定：超限直接拒绝，不做"只处理前 N 页"的静默截断。 */
    private static List<AiVisionImageRefDTO> requirePageImages(List<AiVisionImageRefDTO> pageImages) {
        if (pageImages == null || pageImages.isEmpty() || pageImages.size() > AiVisionLimits.MAX_DOCUMENT_PAGES) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        for (AiVisionImageRefDTO image : pageImages) {
            if (image == null) {
                throw exception(AI_MEDIA_REQUEST_INVALID);
            }
        }
        return List.copyOf(pageImages);
    }

    private void requireReadAuthorization(AiConversationSubject subject, AiKnowledgeBaseDO knowledgeBase) {
        boolean allowed = authorizationService
                .authorize(
                        subject.applicationId(),
                        subject.subjectTypeName(),
                        subject.externalUserId(),
                        AiResourceType.KNOWLEDGE_BASE,
                        knowledgeBase.getCode(),
                        AiAction.READ,
                        List.of(knowledgeBase.getCode()))
                .isAllowed();
        if (!allowed) {
            throw exception(AI_ACCESS_DENIED);
        }
    }
}
