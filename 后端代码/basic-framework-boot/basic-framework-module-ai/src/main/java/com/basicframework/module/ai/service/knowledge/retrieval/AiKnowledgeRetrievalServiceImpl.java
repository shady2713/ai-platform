package com.basicframework.module.ai.service.knowledge.retrieval;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_ACCESS_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.module.ai.adapter.document.DocumentParseException;
import com.basicframework.module.ai.adapter.document.DocumentParser;
import com.basicframework.module.ai.adapter.document.ParsedDocument;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeFilter;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeIndexPort;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeSourceException;
import com.basicframework.module.ai.dal.dataobject.grant.AiResourceGrantDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeIndexGenerationService;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeEmbeddingClient;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeEmbeddingException;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeSourceReader;
import com.basicframework.module.ai.service.knowledge.retrieval.dto.AiKnowledgeCitationDTO;
import com.basicframework.module.ai.service.knowledge.retrieval.dto.AiKnowledgeRetrievalResultDTO;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 授权检索与引用读取实现（K06）。
 *
 * <p>检索顺序即安全语义：**先确定授权范围，再构造过滤条件，最后才检索**。
 * 授权范围来自 A03 的授权目录（当前主体的 READ 授权 ∩ 启用中的知识库），
 * 向量检索的过滤条件只由它推导；问题文本只用于向量化，永远不进入过滤条件。
 *
 * <p>候选复核是第二道防线：索引里可能残留已删除文档或旧版本的向量（AT-028），
 * 复核要求"知识库启用 + 版本 READY + 是文档当前 active 版本"，任一不满足即丢弃并计数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiKnowledgeRetrievalServiceImpl implements AiKnowledgeRetrievalService {

    /** 单次检索返回的引用上限。 */
    public static final int MAX_TOP_K = 20;

    /** 默认返回条数。 */
    private static final int DEFAULT_TOP_K = 5;

    /** 授权目录分页扫描上限（防止超多授权把一次检索拖成扫描）。 */
    private static final int GRANT_SCAN_PAGES = 5;

    private final AiConversationSubjectResolver subjectResolver;

    private final AiResourceGrantService grantService;

    private final AiAuthorizationService authorizationService;

    private final AiKnowledgeBaseService baseService;

    private final AiKnowledgeDocumentService documentService;

    private final AiKnowledgeIndexGenerationService generationService;

    private final AiKnowledgeEmbeddingClient embeddingClient;

    private final AiKnowledgeSourceReader sourceReader;

    private final DocumentParser documentParser;

    private final ObjectProvider<KnowledgeIndexPort> indexPortProvider;

    @Override
    public AiKnowledgeRetrievalResultDTO search(String query, Integer topK) {
        if (!StringUtils.hasText(query)) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiConversationSubject subject = requireSubject();
        int effectiveTopK = topK == null ? DEFAULT_TOP_K : Math.max(1, Math.min(MAX_TOP_K, topK));
        List<AiKnowledgeBaseDO> allowed = authorizedKnowledgeBases(subject);
        if (allowed.isEmpty()) {
            // 没有授权范围就不检索（不"先查全量再过滤"）
            return new AiKnowledgeRetrievalResultDTO().setCitations(List.of());
        }
        KnowledgeIndexPort indexPort = indexPortProvider.getIfAvailable();
        if (indexPort == null) {
            return new AiKnowledgeRetrievalResultDTO().setCitations(List.of());
        }
        List<AiKnowledgeCitationDTO> citations = new ArrayList<>();
        int candidates = 0;
        int filteredOut = 0;
        int searched = 0;
        // 按嵌入模型分组：同一模型同一维度才能共用一次查询向量
        for (Map.Entry<String, List<AiKnowledgeBaseDO>> group :
                groupByModel(allowed).entrySet()) {
            List<AiKnowledgeBaseDO> groupBases = group.getValue();
            AiKnowledgeBaseDO reference = groupBases.get(0);
            float[] vector;
            try {
                vector = embeddingClient
                        .embed(reference.getEmbeddingModel(), List.of(query))
                        .vectors()
                        .get(0);
            } catch (AiKnowledgeEmbeddingException failure) {
                log.warn("[search][查询向量化失败][reason={}]", failure.reasonCode());
                continue;
            }
            for (AiKnowledgeBaseDO knowledgeBase : groupBases) {
                AiKnowledgeIndexGenerationDO generation = generationService.getActiveGeneration(knowledgeBase.getId());
                if (generation == null) {
                    continue;
                }
                searched++;
                KnowledgeFilter filter =
                        KnowledgeFilter.of("knowledge_base_id", List.of(String.valueOf(knowledgeBase.getId())));
                List<KnowledgeIndexPort.SearchHit> hits;
                try {
                    hits = indexPort.search(generation.getCollectionName(), vector, effectiveTopK * 4, filter);
                } catch (RuntimeException failure) {
                    log.warn("[search][向量检索失败][knowledgeBaseId={}]", knowledgeBase.getId());
                    continue;
                }
                candidates += hits.size();
                for (KnowledgeIndexPort.SearchHit hit : hits) {
                    Optional<AiKnowledgeCitationDTO> citation = verifyCandidate(hit, knowledgeBase, subject);
                    if (citation.isEmpty()) {
                        filteredOut++;
                        continue;
                    }
                    citations.add(citation.get());
                }
            }
        }
        citations.sort(Comparator.comparing(AiKnowledgeCitationDTO::getCitationId));
        List<AiKnowledgeCitationDTO> limited =
                citations.size() > effectiveTopK ? citations.subList(0, effectiveTopK) : citations;
        return new AiKnowledgeRetrievalResultDTO()
                .setCitations(List.copyOf(limited))
                .setCandidateCount(candidates)
                .setFilteredOutCount(filteredOut)
                .setSearchedKnowledgeBaseCount(searched);
    }

    /**
     * 候选复核（第二道防线）：知识库启用 + 版本 READY + 是文档当前 active 版本 + 当前主体仍被授权。
     * 任一条不满足即丢弃（索引残留因此不可见，AT-028）。
     */
    private Optional<AiKnowledgeCitationDTO> verifyCandidate(
            KnowledgeIndexPort.SearchHit hit, AiKnowledgeBaseDO knowledgeBase, AiConversationSubject subject) {
        Map<String, Object> payload = hit.payload();
        if (payload == null) {
            return Optional.empty();
        }
        Long versionId = parseLong(payload.get("document_version_id"));
        Integer chunkIndex = parseInteger(payload.get("chunk_index"));
        if (versionId == null || chunkIndex == null) {
            return Optional.empty();
        }
        AiKnowledgeDocumentVersionDO version = findVersion(versionId);
        if (version == null) {
            return Optional.empty();
        }
        if (!AiKnowledgeDocumentVersionDO.STATUS_READY.equals(version.getStatus())) {
            return Optional.empty();
        }
        AiKnowledgeDocumentDO document = findDocument(version.getDocumentId());
        if (document == null
                || !AiKnowledgeDocumentDO.STATUS_READY.equals(document.getStatus())
                || !version.getVersionNo().equals(document.getActiveVersionNo())) {
            // 已删除文档或已被新版本取代：索引残留不可见
            return Optional.empty();
        }
        if (!authorizationService
                .authorize(
                        subject.applicationId(),
                        subject.subjectType().name(),
                        subject.externalUserId(),
                        AiResourceType.KNOWLEDGE_BASE,
                        knowledgeBase.getCode(),
                        AiAction.READ,
                        List.of())
                .isAllowed()) {
            return Optional.empty();
        }
        String text = payload.get("text") == null ? "" : String.valueOf(payload.get("text"));
        return Optional.of(new AiKnowledgeCitationDTO()
                .setCitationId(citationId(knowledgeBase.getId(), versionId, chunkIndex))
                .setKnowledgeBaseId(knowledgeBase.getId())
                .setDocumentId(document.getId())
                .setTitle(document.getTitle())
                .setVersionNo(version.getVersionNo())
                .setChunkIndex(chunkIndex)
                .setLocationRef(
                        payload.get("location_ref") == null ? null : String.valueOf(payload.get("location_ref")))
                .setSnippet(text));
    }

    @Override
    public String readCitationSnippet(String citationId) {
        CitationKey key = AiKnowledgeRetrievalService.parseCitationId(citationId);
        if (key == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiConversationSubject subject = requireSubject();
        AiKnowledgeDocumentVersionDO version = requireReadableVersion(key.documentVersionId(), subject);
        AiKnowledgeBaseDO knowledgeBase = baseService.getKnowledgeBase(version.getKnowledgeBaseId());
        String body;
        try {
            AiKnowledgeSourceReader.KnowledgeSource source = sourceReader.read(version.getFileId());
            ParsedDocument parsed = documentParser.parse(source.content(), source.fileName());
            body = parsed.segments().stream()
                    .filter(segment -> segment.index() >= key.chunkIndex())
                    .map(segment -> segment.text())
                    .findFirst()
                    .orElse("");
            if (body.isEmpty()) {
                body = parsed.segments().stream()
                        .map(segment -> segment.text())
                        .findFirst()
                        .orElse("");
            }
        } catch (KnowledgeSourceException | DocumentParseException failure) {
            // 无权限与不存在同语义；只落稳定原因码
            log.warn(
                    "[readCitationSnippet][原文读取失败][reason={}]",
                    failure.getClass().getSimpleName());
            throw exception(AI_ACCESS_DENIED);
        }
        log.debug(
                "[readCitationSnippet][knowledgeBase={}, version={}, chunk={}]",
                knowledgeBase.getCode(),
                version.getVersionNo(),
                key.chunkIndex());
        return body;
    }

    @Override
    public byte[] readOriginal(Long documentId) {
        AiConversationSubject subject = requireSubject();
        AiKnowledgeDocumentDO document = findDocument(documentId);
        if (document == null) {
            throw exception(AI_ACCESS_DENIED);
        }
        AiKnowledgeDocumentVersionDO active = documentService.getActiveVersion(documentId);
        if (active == null) {
            throw exception(AI_ACCESS_DENIED);
        }
        requireReadableVersion(active.getId(), subject);
        try {
            return sourceReader.read(active.getFileId()).content();
        } catch (KnowledgeSourceException failure) {
            throw exception(AI_ACCESS_DENIED);
        }
    }

    /** 读取前的再鉴权：A03 判定 + 版本仍为该文档的 active 版本。 */
    private AiKnowledgeDocumentVersionDO requireReadableVersion(Long documentVersionId, AiConversationSubject subject) {
        AiKnowledgeDocumentVersionDO version = findVersion(documentVersionId);
        if (version == null) {
            throw exception(AI_ACCESS_DENIED);
        }
        AiKnowledgeBaseDO knowledgeBase = findBase(version.getKnowledgeBaseId());
        if (knowledgeBase == null || !AiKnowledgeBaseDO.STATUS_ENABLED.equals(knowledgeBase.getStatus())) {
            throw exception(AI_ACCESS_DENIED);
        }
        if (!authorizationService
                .authorize(
                        subject.applicationId(),
                        subject.subjectType().name(),
                        subject.externalUserId(),
                        AiResourceType.KNOWLEDGE_BASE,
                        knowledgeBase.getCode(),
                        AiAction.READ,
                        List.of())
                .isAllowed()) {
            throw exception(AI_ACCESS_DENIED);
        }
        AiKnowledgeDocumentDO document = findDocument(version.getDocumentId());
        if (document == null
                || !version.getVersionNo().equals(document.getActiveVersionNo())
                || !AiKnowledgeDocumentVersionDO.STATUS_READY.equals(version.getStatus())) {
            throw exception(AI_ACCESS_DENIED);
        }
        return version;
    }

    /** 授权范围内的知识库：当前主体的 READ 授权 ∩ 启用中的知识库。 */
    private List<AiKnowledgeBaseDO> authorizedKnowledgeBases(AiConversationSubject subject) {
        Set<String> keys = new LinkedHashSet<>();
        for (int page = 1; page <= GRANT_SCAN_PAGES; page++) {
            var result = grantService.getGrantPage(
                    new PageParam().setPageNo(page).setPageSize(100),
                    subject.applicationId(),
                    subject.subjectType().name(),
                    subject.externalUserId(),
                    AiResourceType.KNOWLEDGE_BASE.name());
            for (AiResourceGrantDO grant : result.getList()) {
                if (!AiResourceGrantDO.STATUS_ACTIVE.equals(grant.getStatus())) {
                    continue;
                }
                if (!hasReadAction(grant.getActions())) {
                    continue;
                }
                keys.add(grant.getResourceKey());
            }
            if (result.getList().size() < 100) {
                break;
            }
        }
        List<AiKnowledgeBaseDO> bases = new ArrayList<>();
        for (String key : keys) {
            AiKnowledgeBaseDO knowledgeBase = findBaseByCode(key);
            if (knowledgeBase != null && AiKnowledgeBaseDO.STATUS_ENABLED.equals(knowledgeBase.getStatus())) {
                bases.add(knowledgeBase);
            }
        }
        return bases;
    }

    private static boolean hasReadAction(String actions) {
        if (!StringUtils.hasText(actions)) {
            return false;
        }
        for (String action : actions.split(",")) {
            if (AiAction.READ.name().equalsIgnoreCase(action.trim())) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, List<AiKnowledgeBaseDO>> groupByModel(List<AiKnowledgeBaseDO> bases) {
        Map<String, List<AiKnowledgeBaseDO>> grouped = new LinkedHashMap<>();
        for (AiKnowledgeBaseDO knowledgeBase : bases) {
            grouped.computeIfAbsent(knowledgeBase.getEmbeddingModel(), key -> new ArrayList<>())
                    .add(knowledgeBase);
        }
        return grouped;
    }

    private AiConversationSubject requireSubject() {
        return subjectResolver.resolveCurrent().orElseThrow(() -> exception(AI_ACCESS_DENIED));
    }

    private AiKnowledgeDocumentVersionDO findVersion(Long versionId) {
        try {
            return documentService.getVersion(versionId);
        } catch (RuntimeException notFound) {
            return null;
        }
    }

    private AiKnowledgeDocumentDO findDocument(Long documentId) {
        try {
            return documentService.getDocument(documentId);
        } catch (RuntimeException notFound) {
            return null;
        }
    }

    private AiKnowledgeBaseDO findBase(Long knowledgeBaseId) {
        try {
            return baseService.getKnowledgeBase(knowledgeBaseId);
        } catch (RuntimeException notFound) {
            return null;
        }
    }

    private AiKnowledgeBaseDO findBaseByCode(String code) {
        try {
            return baseService.getByCode(code);
        } catch (RuntimeException notFound) {
            return null;
        }
    }

    static String citationId(Long knowledgeBaseId, Long documentVersionId, Integer chunkIndex) {
        return knowledgeBaseId + ":" + documentVersionId + ":" + chunkIndex;
    }

    private static Long parseLong(Object value) {
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static Integer parseInteger(Object value) {
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
