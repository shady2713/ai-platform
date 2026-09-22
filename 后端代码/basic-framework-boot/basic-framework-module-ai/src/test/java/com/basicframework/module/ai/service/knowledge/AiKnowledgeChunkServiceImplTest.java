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
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeChunkDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeChunkMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeDocumentVersionMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeIndexGenerationMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeChunkDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** K02 切片服务：整版本替换、只允许 INDEXING、序号/哈希/向量标识校验。 */
class AiKnowledgeChunkServiceImplTest {

    private static final Long VERSION_ID = 81L;

    private static final Long BASE_ID = 61L;

    private final AiKnowledgeChunkMapper chunkMapper = mock(AiKnowledgeChunkMapper.class);

    private final AiKnowledgeDocumentVersionMapper versionMapper = mock(AiKnowledgeDocumentVersionMapper.class);

    private final AiKnowledgeIndexGenerationMapper generationMapper = mock(AiKnowledgeIndexGenerationMapper.class);

    private final AiKnowledgeChunkServiceImpl service =
            new AiKnowledgeChunkServiceImpl(chunkMapper, versionMapper, generationMapper);

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static AiKnowledgeChunkDTO chunk(int index) {
        return new AiKnowledgeChunkDTO()
                .setChunkIndex(index)
                .setContentHash("c".repeat(64))
                .setTextLength(120)
                .setTokenCount(40)
                .setVectorId("kb-handbook-g1-c" + index)
                .setLocationRef("p." + (index + 1));
    }

    private void stubVersion(String status) {
        when(versionMapper.selectById(VERSION_ID))
                .thenReturn(new AiKnowledgeDocumentVersionDO()
                        .setId(VERSION_ID)
                        .setDocumentId(71L)
                        .setKnowledgeBaseId(BASE_ID)
                        .setVersionNo(1)
                        .setStatus(status)
                        .setVersion(0));
    }

    @Test
    void replaceWritesChunksForIndexingVersionAndDeletesPreviousOnes() {
        stubVersion(AiKnowledgeDocumentVersionDO.STATUS_INDEXING);
        when(generationMapper.selectByGenerationNo(BASE_ID, 1))
                .thenReturn(new AiKnowledgeIndexGenerationDO().setGenerationNo(1));
        doAnswer(invocation -> 1).when(chunkMapper).insert(any(AiKnowledgeChunkDO.class));

        int written = service.replaceVersionChunks(VERSION_ID, 1, List.of(chunk(0), chunk(1)));

        assertThat(written).isEqualTo(2);
        verify(chunkMapper).deleteByVersion(VERSION_ID);
        ArgumentCaptor<AiKnowledgeChunkDO> inserted = ArgumentCaptor.forClass(AiKnowledgeChunkDO.class);
        verify(chunkMapper, org.mockito.Mockito.times(2)).insert(inserted.capture());
        assertThat(inserted.getAllValues().get(0).getKnowledgeBaseId()).isEqualTo(BASE_ID);
        assertThat(inserted.getAllValues().get(0).getIndexGeneration()).isEqualTo(1);
        assertThat(inserted.getAllValues().get(1).getChunkIndex()).isEqualTo(1);
    }

    @Test
    void replaceRefusesImmutableVersion() {
        stubVersion(AiKnowledgeDocumentVersionDO.STATUS_READY);

        assertThatThrownBy(() -> service.replaceVersionChunks(VERSION_ID, 1, List.of(chunk(0))))
                .as("READY 版本的切片是引用依据，不能改写")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_IMMUTABLE));
        verify(chunkMapper, never()).deleteByVersion(any());

        stubVersion(AiKnowledgeDocumentVersionDO.STATUS_FAILED);
        assertThatThrownBy(() -> service.replaceVersionChunks(VERSION_ID, 1, List.of(chunk(0))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_STATE_INVALID));
    }

    @Test
    void replaceValidatesChunkShapeAndGeneration() {
        stubVersion(AiKnowledgeDocumentVersionDO.STATUS_INDEXING);

        assertThatThrownBy(() -> service.replaceVersionChunks(VERSION_ID, null, List.of(chunk(0))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_GENERATION_CONFLICT));
        when(generationMapper.selectByGenerationNo(BASE_ID, 1)).thenReturn(null);
        assertThatThrownBy(() -> service.replaceVersionChunks(VERSION_ID, 1, List.of(chunk(0))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_GENERATION_CONFLICT));

        when(generationMapper.selectByGenerationNo(BASE_ID, 1))
                .thenReturn(new AiKnowledgeIndexGenerationDO().setGenerationNo(1));
        assertThatThrownBy(() -> service.replaceVersionChunks(VERSION_ID, 1, List.of()))
                .as("空切片说明正文不可用：应由流水线标记失败")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_CHUNK_INVALID));
        assertThatThrownBy(() -> service.replaceVersionChunks(VERSION_ID, 1, List.of(chunk(0), chunk(0))))
                .as("序号重复必须拒绝")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_CHUNK_INVALID));
        assertThatThrownBy(() -> service.replaceVersionChunks(VERSION_ID, 1, List.of(chunk(0).setVectorId(" "))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_SOURCE_KEY_INVALID));
        assertThatThrownBy(() -> service.replaceVersionChunks(VERSION_ID, 1, List.of(chunk(0).setContentHash("xyz"))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_SOURCE_KEY_INVALID));
        assertThatThrownBy(() -> service.replaceVersionChunks(VERSION_ID, 1, List.of(chunk(0).setTokenCount(null))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_CHUNK_INVALID));
        assertThatThrownBy(() -> service.replaceVersionChunks(VERSION_ID, 1, List.of(chunk(-1))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_CHUNK_INVALID));
    }

    @Test
    void deleteAndCountRequireKnownVersion() {
        stubVersion(AiKnowledgeDocumentVersionDO.STATUS_READY);
        when(chunkMapper.deleteByVersion(VERSION_ID)).thenReturn(3);
        when(chunkMapper.countByGeneration(BASE_ID, 1)).thenReturn(9L);

        assertThat(service.deleteVersionChunks(VERSION_ID)).isEqualTo(3);
        assertThat(service.countByGeneration(BASE_ID, 1)).isEqualTo(9);
        assertThat(service.deleteGenerationChunks(BASE_ID, 1)).isZero();

        when(versionMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.listVersionChunks(404L))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_NOT_FOUND));
        assertThatThrownBy(() -> service.countByGeneration(BASE_ID, null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_CHUNK_INVALID));
        verify(chunkMapper).deleteByGeneration(eq(BASE_ID), eq(1));
    }
}
