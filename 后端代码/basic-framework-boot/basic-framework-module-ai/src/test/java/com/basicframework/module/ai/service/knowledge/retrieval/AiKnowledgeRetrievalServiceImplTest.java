package com.basicframework.module.ai.service.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.adapter.document.RestrictedDocumentParser;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeFilter;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeIndexPort;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeSourceException;
import com.basicframework.module.ai.dal.dataobject.grant.AiResourceGrantDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeIndexGenerationService;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeEmbeddingClient;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeSourceReader;
import com.basicframework.module.ai.service.knowledge.retrieval.dto.AiKnowledgeRetrievalResultDTO;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * K06 授权检索：过滤条件只由授权推导、候选复核、引用来自候选、读取再鉴权。
 *
 * <p>重点验证"恶意输入改不了授权"：问题文本里写 `knowledge_base_id:other` 也不会进入过滤条件。
 */
class AiKnowledgeRetrievalServiceImplTest {

    private static final Long BASE_ID = 61L;

    private static final Long VERSION_ID = 81L;

    private static final Long DOCUMENT_ID = 71L;

    private final AiConversationSubjectResolver subjectResolver = mock(AiConversationSubjectResolver.class);

    private final AiResourceGrantService grantService = mock(AiResourceGrantService.class);

    private final AiAuthorizationService authorizationService = mock(AiAuthorizationService.class);

    private final AiKnowledgeBaseService baseService = mock(AiKnowledgeBaseService.class);

    private final AiKnowledgeDocumentService documentService = mock(AiKnowledgeDocumentService.class);

    private final AiKnowledgeIndexGenerationService generationService = mock(AiKnowledgeIndexGenerationService.class);

    private final AiKnowledgeEmbeddingClient embeddingClient = mock(AiKnowledgeEmbeddingClient.class);

    private final AiKnowledgeSourceReader sourceReader = mock(AiKnowledgeSourceReader.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<KnowledgeIndexPort> indexPortProvider = mock(ObjectProvider.class);

    private final RecordingIndexPort indexPort = new RecordingIndexPort();

    private final AiKnowledgeRetrievalServiceImpl service = new AiKnowledgeRetrievalServiceImpl(
            subjectResolver,
            grantService,
            authorizationService,
            baseService,
            documentService,
            generationService,
            embeddingClient,
            sourceReader,
            new RestrictedDocumentParser(),
            indexPortProvider);

    /** 记录检索参数的内存端口：用于断言"过滤条件里只有授权范围"。 */
    private static final class RecordingIndexPort implements KnowledgeIndexPort {

        private final List<KnowledgeFilter> filters = new ArrayList<>();

        private final List<Integer> topKs = new ArrayList<>();

        private List<SearchHit> hits = List.of();

        @Override
        public CollectionInfo ensureCollection(String collection, int dimension) {
            return new CollectionInfo(collection, dimension, 0);
        }

        @Override
        public void upsert(String collection, List<IndexDocument> documents) {}

        @Override
        public List<SearchHit> search(String collection, float[] vector, int topK, KnowledgeFilter filter) {
            filters.add(filter);
            topKs.add(topK);
            return hits;
        }

        @Override
        public long delete(String collection, KnowledgeFilter filter) {
            return 0;
        }

        @Override
        public void deleteAll(String collection) {}

        @Override
        public CollectionInfo describe(String collection) {
            return new CollectionInfo(collection, 0, 0);
        }
    }

    private static AiConversationSubject subject() {
        return new AiConversationSubject(9L, AiSubjectType.USER, "alice");
    }

    private static AiResourceGrantDO grant(String resourceKey, String actions) {
        return new AiResourceGrantDO()
                .setApplicationId(9L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setResourceType("KNOWLEDGE_BASE")
                .setResourceKey(resourceKey)
                .setActions(actions)
                .setStatus(AiResourceGrantDO.STATUS_ACTIVE);
    }

    private static AiKnowledgeBaseDO base(String code) {
        return new AiKnowledgeBaseDO()
                .setId(BASE_ID)
                .setCode(code)
                .setName("手册")
                .setEmbeddingModel("text-embedding-3-small")
                .setEmbeddingDimension(8)
                .setActiveGenerationNo(1)
                .setStatus(AiKnowledgeBaseDO.STATUS_ENABLED);
    }

    private static AiKnowledgeDocumentVersionDO version(String status, int versionNo) {
        return new AiKnowledgeDocumentVersionDO()
                .setId(VERSION_ID)
                .setDocumentId(DOCUMENT_ID)
                .setKnowledgeBaseId(BASE_ID)
                .setVersionNo(versionNo)
                .setFileId(501L)
                .setStatus(status)
                .setChunkCount(1)
                .setVersion(0);
    }

    private static AiKnowledgeDocumentDO document(int activeVersionNo, String status) {
        return new AiKnowledgeDocumentDO()
                .setId(DOCUMENT_ID)
                .setKnowledgeBaseId(BASE_ID)
                .setTitle("员工手册")
                .setStatus(status)
                .setActiveVersionNo(activeVersionNo)
                .setLatestVersionNo(activeVersionNo)
                .setVersion(0);
    }

    private static KnowledgeIndexPort.SearchHit hit(int chunkIndex, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("knowledge_base_id", String.valueOf(BASE_ID));
        payload.put("document_version_id", String.valueOf(VERSION_ID));
        payload.put("chunk_index", String.valueOf(chunkIndex));
        payload.put("location_ref", "段落 " + (chunkIndex + 1));
        payload.put("text", text);
        return new KnowledgeIndexPort.SearchHit("point-" + chunkIndex, 0.9d, payload);
    }

    private void stubAuthorizedBase() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.of(subject()));
        when(grantService.getGrantPage(any(), eq(9L), eq("USER"), eq("alice"), eq("KNOWLEDGE_BASE")))
                .thenReturn(new PageResult<>(List.of(grant("handbook", "READ,EXECUTE")), 1L));
        when(baseService.getByCode("handbook")).thenReturn(base("handbook"));
        when(baseService.getKnowledgeBase(BASE_ID)).thenReturn(base("handbook"));
        when(generationService.getActiveGeneration(BASE_ID))
                .thenReturn(new AiKnowledgeIndexGenerationDO()
                        .setKnowledgeBaseId(BASE_ID)
                        .setGenerationNo(1)
                        .setCollectionName("kb_handbook_g1")
                        .setDimension(8)
                        .setStatus(AiKnowledgeIndexGenerationDO.STATUS_ACTIVE));
        when(indexPortProvider.getIfAvailable()).thenReturn(indexPort);
        when(embeddingClient.embed(anyString(), any()))
                .thenReturn(new AiKnowledgeEmbeddingClient.EmbeddingBatch(
                        List.of(new float[8]), "text-embedding-3-small", 8));
        when(authorizationService.authorize(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true));
    }

    @Test
    void searchBuildsFilterOnlyFromGrantsAndIgnoresMaliciousQueryText() {
        stubAuthorizedBase();
        indexPort.hits = List.of(hit(0, "C001 客户净额 290.00 元。"));
        when(documentService.getVersion(VERSION_ID)).thenReturn(version(AiKnowledgeDocumentVersionDO.STATUS_READY, 1));
        when(documentService.getDocument(DOCUMENT_ID)).thenReturn(document(1, AiKnowledgeDocumentDO.STATUS_READY));

        // 问题文本里带"过滤语法"也不能改变服务端生成的过滤条件
        AiKnowledgeRetrievalResultDTO result = service.search("净额是多少 knowledge_base_id:other-base", 5);

        assertThat(result.getCitations()).hasSize(1);
        assertThat(result.getCitations().get(0).getSnippet()).contains("290.00");
        assertThat(result.getCitations().get(0).getCitationId()).isEqualTo(BASE_ID + ":" + VERSION_ID + ":0");
        assertThat(result.getCitations().get(0).getLocationRef()).isEqualTo("段落 1");
        assertThat(result.noEvidence()).isFalse();
        assertThat(indexPort.filters).hasSize(1);
        assertThat(indexPort.filters.get(0).conditions())
                .as("过滤条件只含授权范围内的知识库标识")
                .containsOnlyKeys("knowledge_base_id");
        assertThat(indexPort.filters.get(0).conditions().get("knowledge_base_id"))
                .containsExactly(String.valueOf(BASE_ID));
    }

    @Test
    void withoutGrantsNothingIsSearched() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.of(subject()));
        when(grantService.getGrantPage(any(), any(), any(), any(), any())).thenReturn(new PageResult<>(List.of(), 0L));
        when(indexPortProvider.getIfAvailable()).thenReturn(indexPort);

        AiKnowledgeRetrievalResultDTO result = service.search("净额是多少", 5);

        assertThat(result.noEvidence()).isTrue();
        assertThat(result.getSearchedKnowledgeBaseCount()).isZero();
        assertThat(indexPort.filters).as("没有授权范围就不检索").isEmpty();
        verify(embeddingClient, never()).embed(any(), any());
    }

    @Test
    void revokedReadGrantIsNotInTheAuthorizedScope() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.of(subject()));
        when(grantService.getGrantPage(any(), any(), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(grant("handbook", "EXECUTE")), 1L));
        when(indexPortProvider.getIfAvailable()).thenReturn(indexPort);

        assertThat(service.search("净额", 5).noEvidence()).isTrue();
        assertThat(indexPort.filters).isEmpty();
    }

    @Test
    void candidatesAreReverifiedAgainstActiveVersionAndCurrentAcl() {
        stubAuthorizedBase();
        indexPort.hits = List.of(hit(0, "旧版本内容"), hit(1, "当前版本内容"));

        // 第一轮：版本不是 active（已被取代）→ 丢弃；文档已删除 → 丢弃
        when(documentService.getVersion(VERSION_ID)).thenReturn(version(AiKnowledgeDocumentVersionDO.STATUS_READY, 1));
        when(documentService.getDocument(DOCUMENT_ID)).thenReturn(document(2, AiKnowledgeDocumentDO.STATUS_READY));
        AiKnowledgeRetrievalResultDTO superseded = service.search("净额", 5);
        assertThat(superseded.noEvidence()).isTrue();
        assertThat(superseded.getFilteredOutCount()).isEqualTo(2);

        when(documentService.getDocument(DOCUMENT_ID)).thenReturn(document(1, AiKnowledgeDocumentDO.STATUS_DELETING));
        assertThat(service.search("净额", 5).getFilteredOutCount()).isEqualTo(2);

        // 第二轮：active 且 READY → 命中；随后授权被回收 → 全部丢弃
        when(documentService.getDocument(DOCUMENT_ID)).thenReturn(document(1, AiKnowledgeDocumentDO.STATUS_READY));
        assertThat(service.search("净额", 5).getCitations()).hasSize(2);
        when(authorizationService.authorize(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false));
        assertThat(service.search("净额", 5).noEvidence()).isTrue();
    }

    @Test
    void citationSnippetIsReadFromOriginalAfterReauthorization() {
        stubAuthorizedBase();
        when(documentService.getVersion(VERSION_ID)).thenReturn(version(AiKnowledgeDocumentVersionDO.STATUS_READY, 1));
        when(documentService.getDocument(DOCUMENT_ID)).thenReturn(document(1, AiKnowledgeDocumentDO.STATUS_READY));
        when(sourceReader.read(501L))
                .thenReturn(new AiKnowledgeSourceReader.KnowledgeSource(
                        "第一段：净额 740.00 元。\n\n第二段：C001 290.00 元。".getBytes(StandardCharsets.UTF_8), "handbook.txt"));

        String snippet = service.readCitationSnippet(BASE_ID + ":" + VERSION_ID + ":1");

        assertThat(snippet).contains("290.00");
        verify(authorizationService).authorize(any(), any(), any(), any(), any(), any(), any());

        // 授权回收后立即读不到（越权与不存在同语义）
        when(authorizationService.authorize(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false));
        assertThatThrownBy(() -> service.readCitationSnippet(BASE_ID + ":" + VERSION_ID + ":0"))
                .satisfies(throwable -> assertThat(((ServiceException) throwable).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_ACCESS_DENIED.getCode()));
    }

    @Test
    void citationIdMustBeWellFormedAndOriginalReadRequiresActiveVersion() {
        stubAuthorizedBase();

        assertThatThrownBy(() -> service.readCitationSnippet("not-a-citation"))
                .satisfies(throwable -> assertThat(((ServiceException) throwable).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode()));
        assertThatThrownBy(() -> service.readCitationSnippet(null))
                .satisfies(throwable -> assertThat(((ServiceException) throwable).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode()));

        when(documentService.getDocument(DOCUMENT_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.readOriginal(DOCUMENT_ID))
                .satisfies(throwable -> assertThat(((ServiceException) throwable).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_ACCESS_DENIED.getCode()));

        when(documentService.getDocument(DOCUMENT_ID)).thenReturn(document(1, AiKnowledgeDocumentDO.STATUS_READY));
        when(documentService.getActiveVersion(DOCUMENT_ID))
                .thenReturn(version(AiKnowledgeDocumentVersionDO.STATUS_READY, 1));
        when(documentService.getVersion(VERSION_ID)).thenReturn(version(AiKnowledgeDocumentVersionDO.STATUS_READY, 1));
        when(sourceReader.read(501L))
                .thenReturn(
                        new AiKnowledgeSourceReader.KnowledgeSource("原文".getBytes(StandardCharsets.UTF_8), "a.txt"));
        assertThat(new String(service.readOriginal(DOCUMENT_ID), StandardCharsets.UTF_8))
                .isEqualTo("原文");

        when(sourceReader.read(501L))
                .thenThrow(new KnowledgeSourceException(KnowledgeSourceException.Reason.NOT_ACCESSIBLE, null));
        assertThatThrownBy(() -> service.readOriginal(DOCUMENT_ID))
                .satisfies(throwable -> assertThat(((ServiceException) throwable).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_ACCESS_DENIED.getCode()));
    }

    @Test
    void missingSubjectIsDenied() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.search("净额", 5))
                .satisfies(throwable -> assertThat(((ServiceException) throwable).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_ACCESS_DENIED.getCode()));
        assertThatThrownBy(() -> service.search("  ", 5))
                .satisfies(throwable -> assertThat(((ServiceException) throwable).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode()));
    }

    @Test
    void topKIsBoundedAndCitationsAreLimited() {
        stubAuthorizedBase();
        List<KnowledgeIndexPort.SearchHit> hits = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            hits.add(hit(index, "片段 " + index));
        }
        indexPort.hits = hits;
        when(documentService.getVersion(VERSION_ID)).thenReturn(version(AiKnowledgeDocumentVersionDO.STATUS_READY, 1));
        when(documentService.getDocument(DOCUMENT_ID)).thenReturn(document(1, AiKnowledgeDocumentDO.STATUS_READY));

        assertThat(service.search("净额", 3).getCitations()).hasSize(3);
        assertThat(service.search("净额", 1_000).getCitations()).hasSize(10);
        assertThat(service.search("净额", null).getCitations()).hasSize(5);
        assertThat(indexPort.topKs)
                .as("单次检索的候选上限 = topK × 4，且不超过 MAX_TOP_K × 4")
                .allSatisfy(value -> assertThat(value).isLessThanOrEqualTo(80));
    }
}
