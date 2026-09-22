package com.basicframework.module.ai.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeChunkMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeIndexGenerationMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** K02 索引代服务：单一构建、维度/模型一致、激活即换代、退役清指针。 */
class AiKnowledgeIndexGenerationServiceImplTest {

    private static final Long BASE_ID = 61L;

    private final AiKnowledgeIndexGenerationMapper generationMapper = mock(AiKnowledgeIndexGenerationMapper.class);

    private final AiKnowledgeBaseMapper baseMapper = mock(AiKnowledgeBaseMapper.class);

    private final AiKnowledgeChunkMapper chunkMapper = mock(AiKnowledgeChunkMapper.class);

    private final AiKnowledgeIndexGenerationServiceImpl service =
            new AiKnowledgeIndexGenerationServiceImpl(generationMapper, baseMapper, chunkMapper);

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static AiKnowledgeBaseDO base(String status, int activeGenerationNo, int version) {
        return new AiKnowledgeBaseDO()
                .setId(BASE_ID)
                .setCode("handbook")
                .setEmbeddingModel("text-embedding-3-small")
                .setEmbeddingDimension(1536)
                .setActiveGenerationNo(activeGenerationNo)
                .setStatus(status)
                .setVersion(version);
    }

    private static AiKnowledgeIndexGenerationDO generation(int no, String status, int rowVersion) {
        return new AiKnowledgeIndexGenerationDO()
                .setId(100L + no)
                .setKnowledgeBaseId(BASE_ID)
                .setGenerationNo(no)
                .setEmbeddingModel("text-embedding-3-small")
                .setDimension(1536)
                .setCollectionName(AiKnowledgeIndexGenerationServiceImpl.collectionName("handbook", no))
                .setStatus(status)
                .setVersion(rowVersion);
    }

    @Test
    void startGenerationIncrementsNumberAndDerivesCollectionName() {
        when(baseMapper.selectById(BASE_ID)).thenReturn(base(AiKnowledgeBaseDO.STATUS_ENABLED, 1, 4));
        when(generationMapper.selectBuilding(BASE_ID)).thenReturn(null);
        when(generationMapper.selectByKnowledgeBase(BASE_ID))
                .thenReturn(List.of(generation(1, AiKnowledgeIndexGenerationDO.STATUS_ACTIVE, 3)));

        assertThat(service.startGeneration(BASE_ID)).isEqualTo(2);

        ArgumentCaptor<AiKnowledgeIndexGenerationDO> inserted =
                ArgumentCaptor.forClass(AiKnowledgeIndexGenerationDO.class);
        verify(generationMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo(AiKnowledgeIndexGenerationDO.STATUS_BUILDING);
        assertThat(inserted.getValue().getCollectionName()).isEqualTo("kb_handbook_g2");
        assertThat(inserted.getValue().getDimension()).as("维度来自知识库声明，不接受调用方自报").isEqualTo(1536);
    }

    @Test
    void startGenerationRefusesConcurrentBuildAndDisabledBase() {
        when(baseMapper.selectById(BASE_ID)).thenReturn(base(AiKnowledgeBaseDO.STATUS_ENABLED, 0, 0));
        when(generationMapper.selectBuilding(BASE_ID))
                .thenReturn(generation(1, AiKnowledgeIndexGenerationDO.STATUS_BUILDING, 0));

        assertThatThrownBy(() -> service.startGeneration(BASE_ID))
                .as("同一知识库同时只允许一个构建中的索引代")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_GENERATION_CONFLICT));

        when(baseMapper.selectById(BASE_ID)).thenReturn(base(AiKnowledgeBaseDO.STATUS_DISABLED, 0, 0));
        assertThatThrownBy(() -> service.startGeneration(BASE_ID))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_STATE_INVALID));
        verify(generationMapper, never()).insert(any(AiKnowledgeIndexGenerationDO.class));
    }

    @Test
    void activateRetiresPreviousGenerationAndSwitchesPointer() {
        when(baseMapper.selectById(BASE_ID)).thenReturn(base(AiKnowledgeBaseDO.STATUS_ENABLED, 1, 4));
        when(generationMapper.selectByGenerationNo(BASE_ID, 2))
                .thenReturn(generation(2, AiKnowledgeIndexGenerationDO.STATUS_BUILDING, 0));
        when(generationMapper.selectActive(BASE_ID))
                .thenReturn(generation(1, AiKnowledgeIndexGenerationDO.STATUS_ACTIVE, 2));
        when(chunkMapper.countByGeneration(BASE_ID, 2)).thenReturn(12L);
        when(chunkMapper.countDistinctVersions(BASE_ID, 2)).thenReturn(3L);
        when(generationMapper.updateWithVersion(any(AiKnowledgeIndexGenerationDO.class), any()))
                .thenReturn(1);
        when(baseMapper.updateWithVersion(any(AiKnowledgeBaseDO.class), eq(4))).thenReturn(1);

        service.activate(BASE_ID, 2);

        ArgumentCaptor<AiKnowledgeIndexGenerationDO> updates =
                ArgumentCaptor.forClass(AiKnowledgeIndexGenerationDO.class);
        verify(generationMapper, org.mockito.Mockito.times(2)).updateWithVersion(updates.capture(), any());
        assertThat(updates.getAllValues().get(0).getStatus())
                .as("上一代先退役")
                .isEqualTo(AiKnowledgeIndexGenerationDO.STATUS_RETIRED);
        assertThat(updates.getAllValues().get(1).getStatus()).isEqualTo(AiKnowledgeIndexGenerationDO.STATUS_ACTIVE);
        assertThat(updates.getAllValues().get(1).getChunkCount()).isEqualTo(12);
        assertThat(updates.getAllValues().get(1).getDocumentCount()).isEqualTo(3);
        ArgumentCaptor<AiKnowledgeBaseDO> baseUpdate = ArgumentCaptor.forClass(AiKnowledgeBaseDO.class);
        verify(baseMapper).updateWithVersion(baseUpdate.capture(), eq(4));
        assertThat(baseUpdate.getValue().getActiveGenerationNo()).isEqualTo(2);
    }

    @Test
    void activateRefusesDimensionOrModelMismatch() {
        when(baseMapper.selectById(BASE_ID)).thenReturn(base(AiKnowledgeBaseDO.STATUS_ENABLED, 0, 0));
        when(generationMapper.selectByGenerationNo(BASE_ID, 1))
                .thenReturn(generation(1, AiKnowledgeIndexGenerationDO.STATUS_BUILDING, 0)
                        .setDimension(64));

        assertThatThrownBy(() -> service.activate(BASE_ID, 1))
                .as("维度与知识库声明不一致（配置被改过）时拒绝激活")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_GENERATION_CONFLICT));

        when(generationMapper.selectByGenerationNo(BASE_ID, 1))
                .thenReturn(generation(1, AiKnowledgeIndexGenerationDO.STATUS_BUILDING, 0)
                        .setEmbeddingModel("other"));
        assertThatThrownBy(() -> service.activate(BASE_ID, 1))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_GENERATION_CONFLICT));
    }

    @Test
    void activateAndFailRespectTheStateMachine() {
        when(baseMapper.selectById(BASE_ID)).thenReturn(base(AiKnowledgeBaseDO.STATUS_ENABLED, 1, 4));
        when(generationMapper.selectByGenerationNo(BASE_ID, 1))
                .thenReturn(generation(1, AiKnowledgeIndexGenerationDO.STATUS_RETIRED, 5));

        assertThatThrownBy(() -> service.activate(BASE_ID, 1))
                .as("退役的索引代不可复活")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_STATE_INVALID));
        assertThatThrownBy(() -> service.fail(BASE_ID, 1, "boom"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_STATE_INVALID));

        when(generationMapper.selectByGenerationNo(BASE_ID, 9)).thenReturn(null);
        assertThatThrownBy(() -> service.getGeneration(BASE_ID, 9))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_STATE_INVALID));
    }

    @Test
    void failRecordsSanitizedReasonAndRetireClearsPointer() {
        when(generationMapper.selectByGenerationNo(BASE_ID, 2))
                .thenReturn(generation(2, AiKnowledgeIndexGenerationDO.STATUS_BUILDING, 0));
        when(generationMapper.updateWithVersion(any(AiKnowledgeIndexGenerationDO.class), eq(0)))
                .thenReturn(1);

        service.fail(BASE_ID, 2, "embedding-timeout\n上游超时");

        ArgumentCaptor<AiKnowledgeIndexGenerationDO> failed =
                ArgumentCaptor.forClass(AiKnowledgeIndexGenerationDO.class);
        verify(generationMapper).updateWithVersion(failed.capture(), eq(0));
        assertThat(failed.getValue().getStatus()).isEqualTo(AiKnowledgeIndexGenerationDO.STATUS_FAILED);
        assertThat(failed.getValue().getFailureReason()).isEqualTo("embedding-timeout 上游超时");

        when(baseMapper.selectById(BASE_ID)).thenReturn(base(AiKnowledgeBaseDO.STATUS_ENABLED, 2, 4));
        when(generationMapper.selectByGenerationNo(BASE_ID, 2))
                .thenReturn(generation(2, AiKnowledgeIndexGenerationDO.STATUS_ACTIVE, 3));
        when(generationMapper.updateWithVersion(any(AiKnowledgeIndexGenerationDO.class), eq(3)))
                .thenReturn(1);
        when(baseMapper.updateWithVersion(any(AiKnowledgeBaseDO.class), eq(4))).thenReturn(1);

        service.retire(BASE_ID, 2);

        ArgumentCaptor<AiKnowledgeBaseDO> baseUpdate = ArgumentCaptor.forClass(AiKnowledgeBaseDO.class);
        verify(baseMapper).updateWithVersion(baseUpdate.capture(), eq(4));
        assertThat(baseUpdate.getValue().getActiveGenerationNo())
                .as("退役当前生效的一代要清空指针（不再用退役内容检索）")
                .isZero();
    }
}
