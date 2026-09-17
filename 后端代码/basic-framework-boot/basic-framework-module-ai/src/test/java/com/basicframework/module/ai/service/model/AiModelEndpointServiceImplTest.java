package com.basicframework.module.ai.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.dal.mysql.model.AiModelEndpointMapper;
import com.basicframework.module.ai.dal.mysql.model.AiModelEndpointRevisionMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 模型端点服务边界：非秘密配置版本化、凭据只轮换不入历史、引用后地址冻结、乐观锁冲突、
 * 停用端点不可用于调用。
 */
class AiModelEndpointServiceImplTest {

    private AiModelEndpointMapper endpointMapper;

    private AiModelEndpointRevisionMapper revisionMapper;

    private CredentialCipher credentialCipher;

    private AiModelEndpointServiceImpl service;

    @BeforeEach
    void setUp() {
        endpointMapper = mock(AiModelEndpointMapper.class);
        revisionMapper = mock(AiModelEndpointRevisionMapper.class);
        credentialCipher = mock(CredentialCipher.class);
        service = new AiModelEndpointServiceImpl(endpointMapper, revisionMapper, credentialCipher);
    }

    private static AiModelEndpointSaveDTO reqVO() {
        AiModelEndpointSaveDTO reqVO = new AiModelEndpointSaveDTO();
        reqVO.setName("openai-生产");
        reqVO.setProvider("openai_compatible");
        reqVO.setBaseUrl("https://api.openai.com/v1");
        reqVO.setModelId("gpt-4o-mini");
        reqVO.setCapabilities(List.of("TEXT"));
        return reqVO;
    }

    private static AiModelEndpointDO endpoint(Long id, int version, boolean referenced) {
        return new AiModelEndpointDO()
                .setId(id)
                .setName("openai-生产")
                .setProvider("openai_compatible")
                .setBaseUrl("https://api.openai.com/v1")
                .setConfigRevision(1)
                .setCredentialRevision(1)
                .setCredentialCiphertext("v1:encrypted")
                .setEnabled(true)
                .setReferenced(referenced)
                .setVersion(version);
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(callable))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());
    }

    @Test
    void embeddingDimensionIsRecordedOnFirstObservationAndChangeIsRejected() {
        // 首次观测：写入维度
        when(endpointMapper.selectById(9L)).thenReturn(endpoint(9L, 0, false));
        when(endpointMapper.updateEmbeddingDimensionIfAbsent(9L, 1536)).thenReturn(1);

        service.assertEmbeddingDimensionUnchanged(9L, 1536);

        verify(endpointMapper).updateEmbeddingDimensionIfAbsent(9L, 1536);

        // 维度不变：放行
        AiModelEndpointDO recorded = endpoint(9L, 1, false).setEmbeddingDimension(1536);
        when(endpointMapper.selectById(9L)).thenReturn(recorded);
        service.assertEmbeddingDimensionUnchanged(9L, 1536);

        // 维度改变：拒绝写既有索引
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> service.assertEmbeddingDimensionUnchanged(9L, 3072)))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MODEL_EMBEDDING_DIMENSION_CHANGED.getCode());
    }

    @Test
    void embeddingDimensionConcurrentFirstWriteFallsBackToStoredValue() {
        when(endpointMapper.selectById(9L)).thenReturn(endpoint(9L, 0, false));
        // 本请求写入失败（并发已写），数据库最终值为 1536
        when(endpointMapper.updateEmbeddingDimensionIfAbsent(9L, 3072)).thenReturn(0);
        when(endpointMapper.selectById(9L))
                .thenReturn(endpoint(9L, 0, false), endpoint(9L, 1, false).setEmbeddingDimension(1536));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> service.assertEmbeddingDimensionUnchanged(9L, 3072)))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MODEL_EMBEDDING_DIMENSION_CHANGED.getCode());
    }

    @Test
    void embeddingDimensionRejectsInvalidObservation() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> service.assertEmbeddingDimensionUnchanged(9L, null)))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        verify(endpointMapper, never()).selectById(any());
    }

    @Test
    void createInsertsEndpointAndFirstRevision() {
        when(endpointMapper.selectByName("openai-生产")).thenReturn(null);
        when(endpointMapper.insert(any(AiModelEndpointDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AiModelEndpointDO.class).setId(9L);
            return 1;
        });

        Long id = service.createEndpoint(reqVO());

        assertThat(id).isEqualTo(9L);
        ArgumentCaptor<AiModelEndpointDO> endpointCaptor = ArgumentCaptor.forClass(AiModelEndpointDO.class);
        verify(endpointMapper).insert(endpointCaptor.capture());
        assertThat(endpointCaptor.getValue().getConfigRevision()).isEqualTo(1);
        // 未提供凭据 → 凭据版本 0、无密文
        assertThat(endpointCaptor.getValue().getCredentialRevision()).isZero();
        assertThat(endpointCaptor.getValue().getCredentialCiphertext()).isNull();
        assertThat(endpointCaptor.getValue().getEnabled()).isFalse();

        ArgumentCaptor<AiModelEndpointRevisionDO> revisionCaptor =
                ArgumentCaptor.forClass(AiModelEndpointRevisionDO.class);
        verify(revisionMapper).insert(revisionCaptor.capture());
        assertThat(revisionCaptor.getValue().getRevision()).isEqualTo(1);
        assertThat(revisionCaptor.getValue().getCapabilities()).isEqualTo("TEXT");
    }

    @Test
    void createWithCredentialEncryptsItWithEndpointBoundContext() {
        when(endpointMapper.selectByName("openai-生产")).thenReturn(null);
        when(endpointMapper.insert(any(AiModelEndpointDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AiModelEndpointDO.class).setId(9L);
            return 1;
        });
        when(credentialCipher.encrypt("sk-secret", "ai_model_endpoint:9")).thenReturn("v1:cipher");
        AiModelEndpointSaveDTO reqVO = reqVO();
        reqVO.setCredential("sk-secret");

        service.createEndpoint(reqVO);

        // 密文以端点编号作为 AAD 写入，凭据版本置 1
        verify(credentialCipher).encrypt("sk-secret", "ai_model_endpoint:9");
        ArgumentCaptor<AiModelEndpointDO> updateCaptor = ArgumentCaptor.forClass(AiModelEndpointDO.class);
        verify(endpointMapper).updateWithVersion(updateCaptor.capture(), eq(0));
        assertThat(updateCaptor.getValue().getCredentialCiphertext()).isEqualTo("v1:cipher");
    }

    @Test
    void createRejectsDuplicateNameAndUnsupportedCapability() {
        when(endpointMapper.selectByName("openai-生产")).thenReturn(endpoint(1L, 0, false));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.createEndpoint(reqVO())))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("端点名称(openai-生产) 已存在");

        AiModelEndpointSaveDTO invalid = reqVO();
        invalid.setCapabilities(List.of("IMAGE"));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.createEndpoint(invalid)))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MODEL_CAPABILITY_UNSUPPORTED.getCode());
    }

    @Test
    void updateCreatesNewRevisionOnlyWhenNonSecretConfigChanged() {
        when(endpointMapper.selectById(1L)).thenReturn(endpoint(1L, 3, false));
        when(revisionMapper.selectByEndpointIdAndRevision(1L, 1))
                .thenReturn(new AiModelEndpointRevisionDO()
                        .setEndpointId(1L)
                        .setRevision(1)
                        .setModelId("gpt-4o-mini")
                        .setCapabilities("TEXT"));
        when(revisionMapper.selectNextRevision(1L)).thenReturn(2);
        when(endpointMapper.updateWithVersion(any(AiModelEndpointDO.class), eq(3)))
                .thenReturn(1);

        // 只改名字 → 不产生新版本
        AiModelEndpointSaveDTO renameOnly = reqVO();
        renameOnly.setId(1L);
        renameOnly.setVersion(3);
        renameOnly.setName("openai-生产-2");
        service.updateEndpoint(renameOnly);
        verify(revisionMapper, never()).insert(any(AiModelEndpointRevisionDO.class));

        // 改模型标识 → 产生不可变版本 2
        AiModelEndpointSaveDTO modelChanged = reqVO();
        modelChanged.setId(1L);
        modelChanged.setVersion(3);
        modelChanged.setModelId("gpt-4o");
        service.updateEndpoint(modelChanged);
        ArgumentCaptor<AiModelEndpointRevisionDO> revisionCaptor =
                ArgumentCaptor.forClass(AiModelEndpointRevisionDO.class);
        verify(revisionMapper).insert(revisionCaptor.capture());
        assertThat(revisionCaptor.getValue().getRevision()).isEqualTo(2);
        assertThat(revisionCaptor.getValue().getModelId()).isEqualTo("gpt-4o");

        ArgumentCaptor<AiModelEndpointDO> updateCaptor = ArgumentCaptor.forClass(AiModelEndpointDO.class);
        verify(endpointMapper, org.mockito.Mockito.times(2)).updateWithVersion(updateCaptor.capture(), eq(3));
        assertThat(updateCaptor.getValue().getConfigRevision()).isEqualTo(2);
        assertThat(updateCaptor.getValue().getVersion()).isEqualTo(4);
    }

    @Test
    void referencedEndpointRejectsProviderAndBaseUrlChangeButAllowsOtherFields() {
        when(endpointMapper.selectById(1L)).thenReturn(endpoint(1L, 2, true));

        AiModelEndpointSaveDTO addressChange = reqVO();
        addressChange.setId(1L);
        addressChange.setVersion(2);
        addressChange.setBaseUrl("https://api.openai.com/v2");
        assertConflict(() -> service.updateEndpoint(addressChange));

        // 仅名称变化：允许（地址迁移必须新建端点，但改名不影响已发布引用）
        when(revisionMapper.selectByEndpointIdAndRevision(1L, 1))
                .thenReturn(new AiModelEndpointRevisionDO()
                        .setEndpointId(1L)
                        .setRevision(1)
                        .setModelId("gpt-4o-mini")
                        .setCapabilities("TEXT"));
        when(endpointMapper.updateWithVersion(any(AiModelEndpointDO.class), eq(2)))
                .thenReturn(1);
        AiModelEndpointSaveDTO renameOnly = reqVO();
        renameOnly.setId(1L);
        renameOnly.setVersion(2);
        renameOnly.setName("openai-生产-2");
        service.updateEndpoint(renameOnly);
        verify(endpointMapper).updateWithVersion(any(AiModelEndpointDO.class), eq(2));
    }

    @Test
    void optimisticLockConflictSurfacesAsStateConflict() {
        when(endpointMapper.selectById(1L)).thenReturn(endpoint(1L, 5, false));
        when(endpointMapper.updateWithVersion(any(AiModelEndpointDO.class), eq(5)))
                .thenReturn(0);

        assertConflict(() -> service.updateEndpointStatus(1L, 5, false));
    }

    @Test
    void credentialRotationOnlyBumpsCredentialRevisionAndNeverTouchesHistory() {
        when(endpointMapper.selectById(1L)).thenReturn(endpoint(1L, 4, false));
        when(credentialCipher.encrypt("sk-rotated", "ai_model_endpoint:1")).thenReturn("v1:rotated");
        when(endpointMapper.updateWithVersion(any(AiModelEndpointDO.class), eq(4)))
                .thenReturn(1);

        service.rotateCredential(1L, 4, "sk-rotated");

        ArgumentCaptor<AiModelEndpointDO> captor = ArgumentCaptor.forClass(AiModelEndpointDO.class);
        verify(endpointMapper).updateWithVersion(captor.capture(), eq(4));
        assertThat(captor.getValue().getCredentialRevision()).isEqualTo(2);
        assertThat(captor.getValue().getCredentialCiphertext()).isEqualTo("v1:rotated");
        // 凭据轮换不产生配置版本：历史里永远没有秘密
        verify(revisionMapper, never()).insert(any(AiModelEndpointRevisionDO.class));
    }

    @Test
    void deleteRejectsReferencedEndpoint() {
        when(endpointMapper.selectById(1L)).thenReturn(endpoint(1L, 1, true));

        assertConflict(() -> service.deleteEndpoint(1L, 1));
        verify(endpointMapper, never()).updateWithVersion(any(AiModelEndpointDO.class), any());
    }

    @Test
    void getEnabledEndpointRejectsDisabledAndMissing() {
        when(endpointMapper.selectById(1L)).thenReturn(endpoint(1L, 0, false).setEnabled(false));
        when(endpointMapper.selectById(2L)).thenReturn(null);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.getEnabledEndpoint(1L)))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MODEL_ENDPOINT_DISABLED.getCode());
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.getEnabledEndpoint(2L)))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MODEL_ENDPOINT_NOT_FOUND.getCode());

        when(endpointMapper.selectById(3L)).thenReturn(endpoint(3L, 0, false).setEnabled(true));
        assertThat(service.getEnabledEndpoint(3L).getId()).isEqualTo(3L);
    }

    @Test
    void markReferencedUsesVersionCas() {
        when(endpointMapper.selectById(1L)).thenReturn(endpoint(1L, 7, false));
        when(endpointMapper.updateWithVersion(any(AiModelEndpointDO.class), eq(7)))
                .thenReturn(1);

        service.markReferenced(1L, 7);

        ArgumentCaptor<AiModelEndpointDO> captor = ArgumentCaptor.forClass(AiModelEndpointDO.class);
        verify(endpointMapper).updateWithVersion(captor.capture(), eq(7));
        assertThat(captor.getValue().getReferenced()).isTrue();
    }
}
