package com.basicframework.module.ai.adapter.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelClientFactory;
import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 客户端解析器边界（M02）：停用/不存在、无凭据、未知能力、版本组装与失效委派。
 */
class AiModelClientResolverTest {

    private static final String CIPHERTEXT = "v1:cipher";

    private AiModelEndpointService endpointService;

    private CredentialCipher credentialCipher;

    private ModelClientFactory clientFactory;

    private AiModelClientResolver resolver;

    private AiModelClientResolver disabledResolver;

    @BeforeEach
    void setUp() {
        endpointService = mock(AiModelEndpointService.class);
        credentialCipher = mock(CredentialCipher.class);
        clientFactory = mock(ModelClientFactory.class);
        ObjectProvider<ModelClientFactory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(clientFactory);
        resolver = new AiModelClientResolver(endpointService, credentialCipher, provider);
        disabledResolver = new AiModelClientResolver(endpointService, credentialCipher, mock(ObjectProvider.class));
    }

    private static AiModelEndpointDO endpoint(int credentialRevision) {
        return new AiModelEndpointDO()
                .setId(9L)
                .setProvider("openai_compatible")
                .setBaseUrl("https://api.example.com/v1")
                .setConfigRevision(2)
                .setCredentialRevision(credentialRevision)
                .setCredentialCiphertext(credentialRevision > 0 ? CIPHERTEXT : null);
    }

    private static AiModelEndpointRevisionDO revision(String capabilities) {
        return new AiModelEndpointRevisionDO()
                .setEndpointId(9L)
                .setRevision(2)
                .setModelId("gpt-4o-mini")
                .setCapabilities(capabilities);
    }

    @Test
    void resolvesSnapshotWithDecryptedCredentialAndVersionIdentities() {
        when(endpointService.getEnabledEndpoint(9L)).thenReturn(endpoint(3));
        when(endpointService.getRevisions(9L)).thenReturn(List.of(revision("TEXT,EMBEDDING")));
        when(credentialCipher.decrypt(CIPHERTEXT, "ai_model_endpoint:9")).thenReturn("sk-decrypted");
        ModelPort port = mock(ModelPort.class);
        when(clientFactory.getOrCreate(any(ModelEndpointSnapshot.class))).thenReturn(port);

        assertThat(resolver.resolve(9L)).isSameAs(port);

        ArgumentCaptor<ModelEndpointSnapshot> captor = ArgumentCaptor.forClass(ModelEndpointSnapshot.class);
        verify(clientFactory).getOrCreate(captor.capture());
        ModelEndpointSnapshot snapshot = captor.getValue();
        assertThat(snapshot.endpointId()).isEqualTo(9L);
        assertThat(snapshot.configRevision()).isEqualTo(2);
        assertThat(snapshot.credentialRevision()).isEqualTo(3);
        assertThat(snapshot.modelId()).isEqualTo("gpt-4o-mini");
        assertThat(snapshot.capabilities()).containsExactlyInAnyOrder(ModelCapability.TEXT, ModelCapability.EMBEDDING);
        // 解密后的凭据只进入快照，不进入键
        assertThat(snapshot.apiKey()).isEqualTo("sk-decrypted");
        assertThat(snapshot.key().toString()).doesNotContain("sk-decrypted");
    }

    @Test
    void probeResolutionAllowsDisabledEndpointButKeepsOtherGuards() {
        // 停用端点：探测走 getEndpoint，不走 getEnabledEndpoint，因此仍可解析
        when(endpointService.getEndpoint(9L)).thenReturn(endpoint(3));
        when(endpointService.getRevisions(9L)).thenReturn(List.of(revision("TEXT")));
        when(credentialCipher.decrypt(CIPHERTEXT, "ai_model_endpoint:9")).thenReturn("sk-decrypted");
        ModelPort port = mock(ModelPort.class);
        when(clientFactory.getOrCreate(any(ModelEndpointSnapshot.class))).thenReturn(port);

        assertThat(resolver.resolveForProbe(9L)).isSameAs(port);
        verify(endpointService, never()).getEnabledEndpoint(any());

        ArgumentCaptor<ModelEndpointSnapshot> captor = ArgumentCaptor.forClass(ModelEndpointSnapshot.class);
        verify(clientFactory).getOrCreate(captor.capture());
        assertThat(captor.getValue().capabilities()).containsExactly(ModelCapability.TEXT);
    }

    @Test
    void probeResolutionStillRejectsMissingCredentialAndDisabledCapability() {
        when(endpointService.getEndpoint(9L)).thenReturn(endpoint(0));
        when(endpointService.getRevisions(9L)).thenReturn(List.of(revision("TEXT")));

        assertThatThrownBy(() -> resolver.resolveForProbe(9L))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.CREDENTIAL_UNAVAILABLE));

        // 未启用 AI 能力时探测同样给出明确错误，而不是静默成功
        assertThatThrownBy(() -> disabledResolver.resolveForProbe(9L))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.AI_DISABLED));
        verify(clientFactory, never()).getOrCreate(any());
    }

    @Test
    void rejectsWhenCredentialMissingOrCapabilityUnknownOrRevisionMissing() {
        // 无凭据（解析顺序：先取当前配置版本，再解密凭据）
        when(endpointService.getEnabledEndpoint(9L)).thenReturn(endpoint(0));
        when(endpointService.getRevisions(9L)).thenReturn(List.of(revision("TEXT")));
        assertReason(ModelException.Reason.CREDENTIAL_UNAVAILABLE);

        // 未知能力
        when(endpointService.getEnabledEndpoint(9L)).thenReturn(endpoint(1));
        when(endpointService.getRevisions(9L)).thenReturn(List.of(revision("TEXT,IMAGE")));
        assertReason(ModelException.Reason.CAPABILITY_UNSUPPORTED);

        // 缺少配置版本
        when(endpointService.getRevisions(9L)).thenReturn(List.of());
        assertReason(ModelException.Reason.ENDPOINT_NOT_FOUND);
        verify(clientFactory, never()).getOrCreate(any());
    }

    @Test
    void disabledOrMissingEndpointPropagatesServiceRejection() {
        when(endpointService.getEnabledEndpoint(9L)).thenThrow(new ServiceException(1_003_002_001, "模型端点已停用"));

        assertThatThrownBy(() -> resolver.resolve(9L)).isInstanceOf(ServiceException.class);
        verify(clientFactory, never()).getOrCreate(any());
    }

    @Test
    void rejectsWithExplicitReasonWhenAiCapabilityIsNotEnabled() {
        when(endpointService.getEnabledEndpoint(9L)).thenReturn(endpoint(1));
        when(endpointService.getRevisions(9L)).thenReturn(List.of(revision("TEXT")));
        when(credentialCipher.decrypt(CIPHERTEXT, "ai_model_endpoint:9")).thenReturn("sk");

        assertThatThrownBy(() -> disabledResolver.resolve(9L))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.AI_DISABLED));
    }

    @Test
    void invalidateDelegatesToFactory() {
        resolver.invalidate(9L);

        verify(clientFactory).invalidate(9L);
    }

    private void assertReason(ModelException.Reason reason) {
        assertThatThrownBy(() -> resolver.resolve(9L))
                .isInstanceOf(ModelException.class)
                .satisfies(exception ->
                        assertThat(((ModelException) exception).getReason()).isEqualTo(reason));
    }

    @Test
    void capabilitiesToleranEmptyDeclaration() {
        when(endpointService.getEnabledEndpoint(9L)).thenReturn(endpoint(1));
        when(endpointService.getRevisions(9L)).thenReturn(List.of(revision("")));
        when(credentialCipher.decrypt(CIPHERTEXT, "ai_model_endpoint:9")).thenReturn("sk");
        when(clientFactory.getOrCreate(any(ModelEndpointSnapshot.class))).thenReturn(mock(ModelPort.class));

        resolver.resolve(9L);

        ArgumentCaptor<ModelEndpointSnapshot> captor = ArgumentCaptor.forClass(ModelEndpointSnapshot.class);
        verify(clientFactory).getOrCreate(captor.capture());
        assertThat(captor.getValue().capabilities()).isEmpty();
        Set<ModelCapability> unused = Set.of();
        assertThat(unused).isEmpty();
    }
}
