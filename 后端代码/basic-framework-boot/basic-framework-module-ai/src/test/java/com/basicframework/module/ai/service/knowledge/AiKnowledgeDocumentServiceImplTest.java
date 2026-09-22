package com.basicframework.module.ai.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeChunkMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeDocumentMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeDocumentVersionMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeIndexGenerationMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentSaveDTO;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentUpsertResultDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** K02 文档与版本服务：sourceKey 幂等、私有文件绑定、active 版本切换、发布后不可变。 */
class AiKnowledgeDocumentServiceImplTest {

    private static final Long BASE_ID = 61L;

    private static final Long DOCUMENT_ID = 71L;

    private static final Long VERSION_ID = 81L;

    private static final String HASH_V1 = "a".repeat(64);

    private static final String HASH_V2 = "b".repeat(64);

    private final AiKnowledgeBaseService knowledgeBaseService = mock(AiKnowledgeBaseService.class);

    private final AiKnowledgeDocumentMapper documentMapper = mock(AiKnowledgeDocumentMapper.class);

    private final AiKnowledgeDocumentVersionMapper versionMapper = mock(AiKnowledgeDocumentVersionMapper.class);

    private final AiKnowledgeChunkMapper chunkMapper = mock(AiKnowledgeChunkMapper.class);

    private final AiKnowledgeIndexGenerationMapper generationMapper = mock(AiKnowledgeIndexGenerationMapper.class);

    private final AiKnowledgeDocumentServiceImpl service = new AiKnowledgeDocumentServiceImpl(
            knowledgeBaseService, documentMapper, versionMapper, chunkMapper, generationMapper);

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static AiKnowledgeDocumentSaveDTO save(String hash) {
        return new AiKnowledgeDocumentSaveDTO()
                .setKnowledgeBaseId(BASE_ID)
                .setSourceKey("handbook/v2.pdf")
                .setTitle("员工手册")
                .setSourceType(AiKnowledgeDocumentDO.SOURCE_API_SYNC)
                .setSourceRef("drive:handbook/v2.pdf")
                .setFileId(501L)
                .setContentHash(hash);
    }

    private static AiKnowledgeDocumentDO document(int latestVersionNo, int activeVersionNo, String status) {
        return new AiKnowledgeDocumentDO()
                .setId(DOCUMENT_ID)
                .setKnowledgeBaseId(BASE_ID)
                .setSourceKey("handbook/v2.pdf")
                .setTitle("员工手册")
                .setStatus(status)
                .setLatestVersionNo(latestVersionNo)
                .setActiveVersionNo(activeVersionNo)
                .setVersion(4);
    }

    private static AiKnowledgeDocumentVersionDO version(
            Long id, int versionNo, String hash, String status, int rowVersion) {
        return new AiKnowledgeDocumentVersionDO()
                .setId(id)
                .setDocumentId(DOCUMENT_ID)
                .setKnowledgeBaseId(BASE_ID)
                .setVersionNo(versionNo)
                .setFileId(501L)
                .setContentHash(hash)
                .setStatus(status)
                .setChunkCount(0)
                .setVersion(rowVersion);
    }

    private void stubBaseEnabled() {
        when(knowledgeBaseService.requireEnabled(BASE_ID))
                .thenReturn(new AiKnowledgeBaseDO()
                        .setId(BASE_ID)
                        .setCode("handbook")
                        .setStatus("ENABLED"));
    }

    @Test
    void firstIngestCreatesDocumentAndVersionOne() {
        stubBaseEnabled();
        doAnswer(invocation -> {
                    AiKnowledgeDocumentDO inserted = invocation.getArgument(0);
                    inserted.setId(DOCUMENT_ID);
                    return 1;
                })
                .when(documentMapper)
                .insert(any(AiKnowledgeDocumentDO.class));
        doAnswer(invocation -> {
                    AiKnowledgeDocumentVersionDO inserted = invocation.getArgument(0);
                    inserted.setId(VERSION_ID);
                    return 1;
                })
                .when(versionMapper)
                .insert(any(AiKnowledgeDocumentVersionDO.class));

        AiKnowledgeDocumentUpsertResultDTO result = service.upsert(save(HASH_V1));

        assertThat(result.getDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(result.getVersionId()).isEqualTo(VERSION_ID);
        assertThat(result.getVersionNo()).isEqualTo(1);
        assertThat(result.isReused()).isFalse();
        assertThat(result.isCreatedVersion()).isTrue();
        ArgumentCaptor<AiKnowledgeDocumentVersionDO> version =
                ArgumentCaptor.forClass(AiKnowledgeDocumentVersionDO.class);
        verify(versionMapper).insert(version.capture());
        assertThat(version.getValue().getFileId()).as("版本必须绑定私有文件").isEqualTo(501L);
        assertThat(version.getValue().getStatus()).isEqualTo(AiKnowledgeDocumentVersionDO.STATUS_INDEXING);
    }

    @Test
    void sameSourceKeyAndSameHashReusesExistingVersion() {
        stubBaseEnabled();
        when(documentMapper.selectBySourceKey(BASE_ID, "handbook/v2.pdf"))
                .thenReturn(document(1, 1, AiKnowledgeDocumentDO.STATUS_READY));
        when(versionMapper.selectByVersionNo(DOCUMENT_ID, 1))
                .thenReturn(version(VERSION_ID, 1, HASH_V1, AiKnowledgeDocumentVersionDO.STATUS_READY, 3));

        AiKnowledgeDocumentUpsertResultDTO result = service.upsert(save(HASH_V1));

        assertThat(result.isReused()).isTrue();
        assertThat(result.isCreatedVersion()).as("内容没变不产生新版本").isFalse();
        assertThat(result.getVersionNo()).isEqualTo(1);
        verify(versionMapper, never()).insert(any(AiKnowledgeDocumentVersionDO.class));
        verify(documentMapper, never()).updateWithVersion(any(AiKnowledgeDocumentDO.class), any());
    }

    @Test
    void changedHashCreatesNextVersionAndKeepsActivePointer() {
        stubBaseEnabled();
        when(documentMapper.selectBySourceKey(BASE_ID, "handbook/v2.pdf"))
                .thenReturn(document(1, 1, AiKnowledgeDocumentDO.STATUS_READY));
        when(versionMapper.selectByVersionNo(DOCUMENT_ID, 1))
                .thenReturn(version(VERSION_ID, 1, HASH_V1, AiKnowledgeDocumentVersionDO.STATUS_READY, 3));
        doAnswer(invocation -> {
                    AiKnowledgeDocumentVersionDO inserted = invocation.getArgument(0);
                    inserted.setId(82L);
                    return 1;
                })
                .when(versionMapper)
                .insert(any(AiKnowledgeDocumentVersionDO.class));
        when(documentMapper.updateWithVersion(any(AiKnowledgeDocumentDO.class), eq(4)))
                .thenReturn(1);

        AiKnowledgeDocumentUpsertResultDTO result = service.upsert(save(HASH_V2));

        assertThat(result.isReused()).isTrue();
        assertThat(result.isCreatedVersion()).isTrue();
        assertThat(result.getVersionNo()).isEqualTo(2);
        ArgumentCaptor<AiKnowledgeDocumentDO> update = ArgumentCaptor.forClass(AiKnowledgeDocumentDO.class);
        verify(documentMapper).updateWithVersion(update.capture(), eq(4));
        assertThat(update.getValue().getLatestVersionNo()).isEqualTo(2);
        assertThat(update.getValue().getStatus()).isEqualTo(AiKnowledgeDocumentDO.STATUS_PENDING);
        assertThat(update.getValue().getActiveVersionNo())
                .as("active 指针只在索引成功后切换，入库阶段不动")
                .isNull();
    }

    @Test
    void failedLatestVersionIsRetriedInPlaceWithoutNewVersion() {
        stubBaseEnabled();
        when(documentMapper.selectBySourceKey(BASE_ID, "handbook/v2.pdf"))
                .thenReturn(document(1, 0, AiKnowledgeDocumentDO.STATUS_FAILED));
        when(versionMapper.selectByVersionNo(DOCUMENT_ID, 1))
                .thenReturn(version(VERSION_ID, 1, HASH_V1, AiKnowledgeDocumentVersionDO.STATUS_FAILED, 3));
        when(versionMapper.updateWithVersion(any(AiKnowledgeDocumentVersionDO.class), eq(3)))
                .thenReturn(1);
        when(documentMapper.updateWithVersion(any(AiKnowledgeDocumentDO.class), eq(4)))
                .thenReturn(1);

        AiKnowledgeDocumentUpsertResultDTO result = service.upsert(save(HASH_V1));

        assertThat(result.isCreatedVersion()).as("重试同一内容不产生新版本").isFalse();
        ArgumentCaptor<AiKnowledgeDocumentVersionDO> update =
                ArgumentCaptor.forClass(AiKnowledgeDocumentVersionDO.class);
        verify(versionMapper).updateWithVersion(update.capture(), eq(3));
        assertThat(update.getValue().getStatus()).isEqualTo(AiKnowledgeDocumentVersionDO.STATUS_INDEXING);
    }

    @Test
    void ingestRequiresPrivateFileAndValidSourceKey() {
        stubBaseEnabled();

        assertThatThrownBy(() -> service.upsert(save(HASH_V1).setFileId(null)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_FILE_REQUIRED));
        assertThatThrownBy(() -> service.upsert(save(HASH_V1).setSourceKey("bad key")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_SOURCE_KEY_INVALID));
        assertThatThrownBy(() -> service.upsert(save("not-a-hash")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_SOURCE_KEY_INVALID));
        assertThatThrownBy(() -> service.upsert(save(HASH_V1).setKnowledgeBaseId(null)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_SOURCE_KEY_INVALID));
    }

    @Test
    void markReadySwitchesActiveVersionAndSupersedesPrevious() {
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document(2, 1, AiKnowledgeDocumentDO.STATUS_INDEXING));
        when(versionMapper.selectById(82L))
                .thenReturn(version(82L, 2, HASH_V2, AiKnowledgeDocumentVersionDO.STATUS_INDEXING, 0));
        when(generationMapper.selectByGenerationNo(BASE_ID, 3))
                .thenReturn(new AiKnowledgeIndexGenerationDO().setGenerationNo(3));
        when(chunkMapper.countByVersion(82L)).thenReturn(7L);
        when(versionMapper.selectByVersionNo(DOCUMENT_ID, 1))
                .thenReturn(version(VERSION_ID, 1, HASH_V1, AiKnowledgeDocumentVersionDO.STATUS_READY, 5));
        when(versionMapper.updateWithVersion(any(AiKnowledgeDocumentVersionDO.class), any()))
                .thenReturn(1);
        when(documentMapper.updateWithVersion(any(AiKnowledgeDocumentDO.class), eq(4)))
                .thenReturn(1);

        service.markVersionReady(DOCUMENT_ID, 82L, 3);

        ArgumentCaptor<AiKnowledgeDocumentVersionDO> versionUpdate =
                ArgumentCaptor.forClass(AiKnowledgeDocumentVersionDO.class);
        verify(versionMapper, org.mockito.Mockito.times(2)).updateWithVersion(versionUpdate.capture(), any());
        assertThat(versionUpdate.getAllValues().get(0).getStatus())
                .as("新版本置 READY 并记录切片数与索引代")
                .isEqualTo(AiKnowledgeDocumentVersionDO.STATUS_READY);
        assertThat(versionUpdate.getAllValues().get(0).getChunkCount()).isEqualTo(7);
        assertThat(versionUpdate.getAllValues().get(0).getIndexGeneration()).isEqualTo(3);
        assertThat(versionUpdate.getAllValues().get(1).getStatus())
                .as("旧 active 版本置 SUPERSEDED 但保留可追溯")
                .isEqualTo(AiKnowledgeDocumentVersionDO.STATUS_SUPERSEDED);
        ArgumentCaptor<AiKnowledgeDocumentDO> documentUpdate = ArgumentCaptor.forClass(AiKnowledgeDocumentDO.class);
        verify(documentMapper).updateWithVersion(documentUpdate.capture(), eq(4));
        assertThat(documentUpdate.getValue().getActiveVersionNo()).isEqualTo(2);
        assertThat(documentUpdate.getValue().getStatus()).isEqualTo(AiKnowledgeDocumentDO.STATUS_READY);
    }

    @Test
    void markReadyRejectsImmutableVersionAndUnknownGeneration() {
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document(1, 1, AiKnowledgeDocumentDO.STATUS_READY));
        when(versionMapper.selectById(VERSION_ID))
                .thenReturn(version(VERSION_ID, 1, HASH_V1, AiKnowledgeDocumentVersionDO.STATUS_READY, 3));

        assertThatThrownBy(() -> service.markVersionReady(DOCUMENT_ID, VERSION_ID, 1))
                .as("已可用版本不可再次发布（不可变）")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_IMMUTABLE));

        when(versionMapper.selectById(VERSION_ID))
                .thenReturn(version(VERSION_ID, 1, HASH_V1, AiKnowledgeDocumentVersionDO.STATUS_INDEXING, 3));
        assertThatThrownBy(() -> service.markVersionReady(DOCUMENT_ID, VERSION_ID, null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_GENERATION_CONFLICT));
        assertThatThrownBy(() -> service.markVersionReady(DOCUMENT_ID, VERSION_ID, 9))
                .as("切片必须属于已登记的索引代")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_GENERATION_CONFLICT));
    }

    @Test
    void markFailedKeepsOldActiveVersionUsable() {
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document(2, 1, AiKnowledgeDocumentDO.STATUS_INDEXING));
        when(versionMapper.selectById(82L))
                .thenReturn(version(82L, 2, HASH_V2, AiKnowledgeDocumentVersionDO.STATUS_INDEXING, 0));
        when(versionMapper.updateWithVersion(any(AiKnowledgeDocumentVersionDO.class), eq(0)))
                .thenReturn(1);
        when(documentMapper.updateWithVersion(any(AiKnowledgeDocumentDO.class), eq(4)))
                .thenReturn(1);

        service.markVersionFailed(DOCUMENT_ID, 82L, "parse-failed\n第二行");

        ArgumentCaptor<AiKnowledgeDocumentVersionDO> versionUpdate =
                ArgumentCaptor.forClass(AiKnowledgeDocumentVersionDO.class);
        verify(versionMapper).updateWithVersion(versionUpdate.capture(), eq(0));
        assertThat(versionUpdate.getValue().getStatus()).isEqualTo(AiKnowledgeDocumentVersionDO.STATUS_FAILED);
        assertThat(versionUpdate.getValue().getFailureReason()).as("失败原因单行且截断").isEqualTo("parse-failed 第二行");
        ArgumentCaptor<AiKnowledgeDocumentDO> documentUpdate = ArgumentCaptor.forClass(AiKnowledgeDocumentDO.class);
        verify(documentMapper).updateWithVersion(documentUpdate.capture(), eq(4));
        assertThat(documentUpdate.getValue().getStatus()).isEqualTo(AiKnowledgeDocumentDO.STATUS_FAILED);
        assertThat(documentUpdate.getValue().getActiveVersionNo())
                .as("失败不改 active 指针：旧版本仍可用（AT-024）")
                .isNull();
    }

    @Test
    void lateFailureOfOlderVersionDoesNotFlipDocumentStatus() {
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document(3, 2, AiKnowledgeDocumentDO.STATUS_READY));
        when(versionMapper.selectById(VERSION_ID))
                .thenReturn(version(VERSION_ID, 1, HASH_V1, AiKnowledgeDocumentVersionDO.STATUS_INDEXING, 0));
        when(versionMapper.updateWithVersion(any(AiKnowledgeDocumentVersionDO.class), eq(0)))
                .thenReturn(1);

        service.markVersionFailed(DOCUMENT_ID, VERSION_ID, "index-failed");

        verify(documentMapper, never()).updateWithVersion(any(AiKnowledgeDocumentDO.class), any());
    }

    @Test
    void deleteMarksDeletingAndRejectsIllegalTransition() {
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document(1, 1, AiKnowledgeDocumentDO.STATUS_READY));
        when(documentMapper.updateWithVersion(any(AiKnowledgeDocumentDO.class), eq(4)))
                .thenReturn(1);

        service.deleteDocument(DOCUMENT_ID, 4);
        ArgumentCaptor<AiKnowledgeDocumentDO> update = ArgumentCaptor.forClass(AiKnowledgeDocumentDO.class);
        verify(documentMapper).updateWithVersion(update.capture(), eq(4));
        assertThat(update.getValue().getStatus()).isEqualTo(AiKnowledgeDocumentDO.STATUS_DELETING);

        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document(1, 1, AiKnowledgeDocumentDO.STATUS_DELETING));
        assertThatThrownBy(() -> service.deleteDocument(DOCUMENT_ID, 4))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_STATE_INVALID));
    }
}
