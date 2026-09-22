package com.basicframework.module.ai.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeDocumentMapper;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceResourceMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeBaseSaveDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** K02 知识库服务：可见性与应用归属、不可修改字段、启停、删除保护。 */
class AiKnowledgeBaseServiceImplTest {

    private static final Long BASE_ID = 61L;

    private final AiKnowledgeBaseMapper baseMapper = mock(AiKnowledgeBaseMapper.class);

    private final AiKnowledgeDocumentMapper documentMapper = mock(AiKnowledgeDocumentMapper.class);

    private final AiServiceResourceMapper serviceResourceMapper = mock(AiServiceResourceMapper.class);

    private final AiKnowledgeBaseServiceImpl service =
            new AiKnowledgeBaseServiceImpl(baseMapper, documentMapper, serviceResourceMapper);

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static AiKnowledgeBaseSaveDTO applicationBase() {
        return new AiKnowledgeBaseSaveDTO()
                .setCode("handbook")
                .setName("员工手册")
                .setVisibility(AiKnowledgeBaseDO.VISIBILITY_APPLICATION)
                .setOwnerApplicationId(9L)
                .setEmbeddingModel("text-embedding-3-small")
                .setEmbeddingDimension(1536);
    }

    private static AiKnowledgeBaseDO existing() {
        return new AiKnowledgeBaseDO()
                .setId(BASE_ID)
                .setCode("handbook")
                .setName("员工手册")
                .setVisibility(AiKnowledgeBaseDO.VISIBILITY_APPLICATION)
                .setOwnerApplicationId(9L)
                .setEmbeddingModel("text-embedding-3-small")
                .setEmbeddingDimension(1536)
                .setActiveGenerationNo(0)
                .setRetentionDays(365)
                .setStatus(AiKnowledgeBaseDO.STATUS_ENABLED)
                .setVersion(2);
    }

    @Test
    void createRequiresOwnerApplicationForApplicationVisibilityAndRejectsDuplicateCode() {
        doAnswer(invocation -> {
                    AiKnowledgeBaseDO inserted = invocation.getArgument(0);
                    inserted.setId(BASE_ID);
                    return 1;
                })
                .when(baseMapper)
                .insert(any(AiKnowledgeBaseDO.class));

        assertThat(service.create(applicationBase())).isEqualTo(BASE_ID);
        ArgumentCaptor<AiKnowledgeBaseDO> inserted = ArgumentCaptor.forClass(AiKnowledgeBaseDO.class);
        verify(baseMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo(AiKnowledgeBaseDO.STATUS_ENABLED);
        assertThat(inserted.getValue().getActiveGenerationNo()).isZero();
        assertThat(inserted.getValue().getRetentionDays()).isEqualTo(365);

        when(baseMapper.selectByCode("handbook")).thenReturn(existing());
        assertThatThrownBy(() -> service.create(applicationBase()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_CODE_DUPLICATE));
    }

    @Test
    void createRejectsMismatchedVisibilityAndInvalidNumbers() {
        // 应用专用必须给所属应用
        assertThatThrownBy(() -> service.create(applicationBase().setOwnerApplicationId(null)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_CONFIG_INVALID));
        // 共享库不能挂在某个应用下
        assertThatThrownBy(() -> service.create(applicationBase().setVisibility(AiKnowledgeBaseDO.VISIBILITY_SHARED)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_CONFIG_INVALID));
        // 维度越界
        assertThatThrownBy(() -> service.create(applicationBase().setEmbeddingDimension(0)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_CONFIG_INVALID));
        // 模型标识非法
        assertThatThrownBy(() -> service.create(applicationBase().setEmbeddingModel("bad model")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_CONFIG_INVALID));
        assertThatThrownBy(() -> service.create(null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_CONFIG_INVALID));
    }

    @Test
    void updateOnlyTouchesMutableFieldsAndKeepsEmbeddingContract() {
        when(baseMapper.selectById(BASE_ID)).thenReturn(existing());
        when(baseMapper.updateWithVersion(any(AiKnowledgeBaseDO.class), eq(2))).thenReturn(1);

        service.update(new AiKnowledgeBaseSaveDTO()
                .setId(BASE_ID)
                .setName("员工手册（2026）")
                .setDescription("新版")
                .setManagerUserId(7L)
                .setRetentionDays(180)
                // 这些字段不可修改：传入也不生效（库里仍是创建时的值）
                .setCode("other-code")
                .setEmbeddingModel("other-model")
                .setEmbeddingDimension(64)
                .setVersion(2));

        ArgumentCaptor<AiKnowledgeBaseDO> update = ArgumentCaptor.forClass(AiKnowledgeBaseDO.class);
        verify(baseMapper).updateWithVersion(update.capture(), eq(2));
        assertThat(update.getValue().getName()).isEqualTo("员工手册（2026）");
        assertThat(update.getValue().getRetentionDays()).isEqualTo(180);
        assertThat(update.getValue().getCode()).as("标识不可修改").isNull();
        assertThat(update.getValue().getEmbeddingModel()).as("嵌入模型不可修改").isNull();
        assertThat(update.getValue().getEmbeddingDimension()).as("维度不可修改").isNull();
    }

    @Test
    void updateStatusAndUpdateUseOptimisticLock() {
        when(baseMapper.selectById(BASE_ID)).thenReturn(existing());
        when(baseMapper.updateWithVersion(any(AiKnowledgeBaseDO.class), eq(9))).thenReturn(0);

        assertThatThrownBy(() -> service.updateStatus(BASE_ID, 9, false))
                .as("版本不一致必须冲突而不是覆盖")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_STATE_CONFLICT));
        assertThatThrownBy(() -> service.updateStatus(null, 1, true))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_CONFIG_INVALID));
    }

    @Test
    void deleteRefusesReferencedOrNonEmptyKnowledgeBase() {
        when(baseMapper.selectById(BASE_ID)).thenReturn(existing());

        when(serviceResourceMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.delete(BASE_ID, 2))
                .as("被服务绑定引用不能删除")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_REFERENCED));

        when(serviceResourceMapper.selectCount(any())).thenReturn(0L);
        when(documentMapper.selectByKnowledgeBase(BASE_ID)).thenReturn(List.of(new AiKnowledgeDocumentDO()));
        assertThatThrownBy(() -> service.delete(BASE_ID, 2))
                .as("仍有文档不能删除")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_NOT_EMPTY));

        when(documentMapper.selectByKnowledgeBase(BASE_ID)).thenReturn(List.of());
        when(baseMapper.updateWithVersion(any(AiKnowledgeBaseDO.class), eq(2))).thenReturn(1);
        service.delete(BASE_ID, 2);
        verify(baseMapper).deleteById(BASE_ID);
    }

    @Test
    void requireEnabledRejectsDisabledKnowledgeBase() {
        when(baseMapper.selectById(BASE_ID)).thenReturn(existing().setStatus(AiKnowledgeBaseDO.STATUS_DISABLED));

        assertThatThrownBy(() -> service.requireEnabled(BASE_ID))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_DISABLED));
        when(baseMapper.selectById(BASE_ID)).thenReturn(existing());
        assertThat(service.requireEnabled(BASE_ID).getCode()).isEqualTo("handbook");

        when(baseMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.getKnowledgeBase(404L))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_NOT_FOUND));
    }
}
