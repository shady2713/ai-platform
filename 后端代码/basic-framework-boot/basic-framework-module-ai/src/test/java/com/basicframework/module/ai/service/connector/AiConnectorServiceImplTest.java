package com.basicframework.module.ai.service.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.ExternalHttpException;
import com.basicframework.framework.ai.core.http.ExternalHttpRequest;
import com.basicframework.framework.ai.core.http.ExternalHttpResponse;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlPoolRegistry;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorProbeDO;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorMapper;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorProbeMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.connector.dto.AiConnectorProbeResultDTO;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** D01 连接器服务：秘密加密与不回显、引用保护、探测走统一出站策略、乐观锁。 */
class AiConnectorServiceImplTest {

    private static final Long CONNECTOR_ID = 71L;

    private final AiConnectorMapper connectorMapper = mock(AiConnectorMapper.class);

    private final AiConnectorProbeMapper probeMapper = mock(AiConnectorProbeMapper.class);

    private final CredentialCipher credentialCipher = mock(CredentialCipher.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ExternalHttpClient> httpClientProvider = mock(ObjectProvider.class);

    private final ExternalHttpClient httpClient = mock(ExternalHttpClient.class);

    private final AiConnectorReferenceChecker referenceChecker = mock(AiConnectorReferenceChecker.class);

    private final AiConnectorServiceImpl service = new AiConnectorServiceImpl(
            connectorMapper,
            probeMapper,
            credentialCipher,
            httpClientProvider,
            List.of(referenceChecker),
            new AiMysqlPoolRegistry());

    @BeforeEach
    void setUp() {
        when(credentialCipher.encrypt(anyString(), anyString())).thenReturn("v1:encrypted");
        when(credentialCipher.decrypt(anyString(), anyString())).thenReturn("secret-value");
        when(referenceChecker.findReference(any())).thenReturn(Optional.empty());
        when(httpClientProvider.getIfAvailable()).thenReturn(httpClient);
        when(connectorMapper.updateWithVersion(any(), anyInt())).thenReturn(1);
    }

    private static AiConnectorDO connector(String type, String status, String config) {
        return new AiConnectorDO()
                .setId(CONNECTOR_ID)
                .setCode("crm-http")
                .setName("CRM 接口")
                .setConnectorType(type)
                .setStatus(status)
                .setConfigJson(config)
                .setCredentialRevision(1)
                .setReferenced(false)
                .setVersion(0);
    }

    private static AiConnectorSaveDTO httpSave() {
        return new AiConnectorSaveDTO()
                .setCode("crm-http")
                .setName("CRM 接口")
                .setConnectorType("HTTP")
                .setConfigJson("{\"baseUrl\":\"https://crm.example.com\",\"method\":\"GET\"}")
                .setCredential("sk-connector-secret");
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void createValidatesConfigAndEncryptsSecretWithoutEchoing() {
        when(connectorMapper.selectByCode("crm-http")).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
                    ((AiConnectorDO) invocation.getArgument(0)).setId(CONNECTOR_ID);
                    return 1;
                })
                .when(connectorMapper)
                .insert(any(AiConnectorDO.class));

        assertThat(service.create(httpSave())).isEqualTo(CONNECTOR_ID);

        ArgumentCaptor<AiConnectorDO> insertCaptor = ArgumentCaptor.forClass(AiConnectorDO.class);
        verify(connectorMapper).insert(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getCredentialCiphertext())
                .as("插入时不带明文，也不带密文")
                .isNull();
        ArgumentCaptor<AiConnectorDO> updateCaptor = ArgumentCaptor.forClass(AiConnectorDO.class);
        verify(connectorMapper).updateWithVersion(updateCaptor.capture(), eq(0));
        assertThat(updateCaptor.getValue().getCredentialCiphertext())
                .as("秘密只以密文写入")
                .isEqualTo("v1:encrypted")
                .isNotEqualTo("sk-connector-secret");
        assertThat(updateCaptor.getValue().getCredentialRevision()).isEqualTo(1);
        verify(credentialCipher).encrypt("sk-connector-secret", "ai_connector:" + CONNECTOR_ID);

        when(connectorMapper.selectByCode("crm-http"))
                .thenReturn(connector("HTTP", AiConnectorDO.STATUS_ENABLED, "{}"));
        assertThatThrownBy(() -> service.create(httpSave()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_CODE_DUPLICATE));

        assertThatThrownBy(() -> service.create(httpSave().setConfigJson("{\"baseUrl\":\"http://x\"}")))
                .as("非法配置在服务层再次拒绝（不只依赖协议层）")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_CONFIG_INVALID));
        assertThatThrownBy(() -> service.create(httpSave().setCode("1bad code")))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_CONFIG_INVALID));
    }

    @Test
    void updateKeepsSecretWhenBlankAndRejectsCodeOrTypeChange() {
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector(
                        "HTTP",
                        AiConnectorDO.STATUS_ENABLED,
                        "{\"baseUrl\":\"https://crm.example.com\",\"method\":\"GET\"}"));

        service.update(new AiConnectorSaveDTO()
                .setId(CONNECTOR_ID)
                .setCode("crm-http")
                .setName("CRM 接口 v2")
                .setConnectorType("HTTP")
                .setConfigJson("{\"baseUrl\":\"https://crm.example.com\",\"method\":\"POST\"}")
                .setVersion(0));

        ArgumentCaptor<AiConnectorDO> captor = ArgumentCaptor.forClass(AiConnectorDO.class);
        verify(connectorMapper).updateWithVersion(captor.capture(), eq(0));
        assertThat(captor.getValue().getCredentialCiphertext())
                .as("留空秘密表示保留，不覆盖密文")
                .isNull();
        assertThat(captor.getValue().getCredentialRevision()).isNull();

        assertThatThrownBy(() -> service.update(new AiConnectorSaveDTO()
                        .setId(CONNECTOR_ID)
                        .setCode("crm-http-v2")
                        .setName("x")
                        .setConnectorType("HTTP")
                        .setConfigJson("{\"baseUrl\":\"https://crm.example.com\",\"method\":\"GET\"}")
                        .setVersion(0)))
                .as("标识是引用方的稳定键：不可修改")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));

        assertThatThrownBy(() -> service.update(new AiConnectorSaveDTO()
                        .setId(CONNECTOR_ID)
                        .setCode("crm-http")
                        .setName("x")
                        .setConnectorType("MYSQL")
                        .setConfigJson(
                                "{\"host\":\"mysql.internal\",\"port\":3306,\"database\":\"crm\",\"username\":\"ro\"}")
                        .setVersion(0)))
                .as("类型不可修改")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));

        when(connectorMapper.updateWithVersion(any(), anyInt())).thenReturn(0);
        assertThatThrownBy(() -> service.update(new AiConnectorSaveDTO()
                        .setId(CONNECTOR_ID)
                        .setCode("crm-http")
                        .setName("x")
                        .setConnectorType("HTTP")
                        .setConfigJson("{\"baseUrl\":\"https://crm.example.com\",\"method\":\"GET\"}")
                        .setVersion(0)))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));
    }

    @Test
    void rotateAndStatusAndDeleteFollowTheContract() {
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector(
                        "HTTP",
                        AiConnectorDO.STATUS_ENABLED,
                        "{\"baseUrl\":\"https://crm.example.com\",\"method\":\"GET\"}"));

        service.rotateCredential(CONNECTOR_ID, 0, "sk-rotated");
        ArgumentCaptor<AiConnectorDO> rotateCaptor = ArgumentCaptor.forClass(AiConnectorDO.class);
        verify(connectorMapper).updateWithVersion(rotateCaptor.capture(), eq(0));
        assertThat(rotateCaptor.getValue().getCredentialRevision())
                .as("轮换只递增秘密版本")
                .isEqualTo(2);
        assertThat(rotateCaptor.getValue().getCredentialCiphertext()).isEqualTo("v1:encrypted");

        service.updateStatus(CONNECTOR_ID, 0, false);
        ArgumentCaptor<AiConnectorDO> statusCaptor = ArgumentCaptor.forClass(AiConnectorDO.class);
        verify(connectorMapper, org.mockito.Mockito.atLeastOnce()).updateWithVersion(statusCaptor.capture(), anyInt());
        assertThat(statusCaptor.getValue().getStatus()).isEqualTo(AiConnectorDO.STATUS_DISABLED);

        // 引用保护：引用方报告引用时拒绝删除
        when(referenceChecker.findReference(CONNECTOR_ID)).thenReturn(Optional.of("数据集 ds-1 正在使用"));
        assertThatThrownBy(() -> service.delete(CONNECTOR_ID, 0))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_REFERENCED));
        verify(connectorMapper, never()).deleteById(any());

        // referenced 标记同样拒绝
        when(referenceChecker.findReference(CONNECTOR_ID)).thenReturn(Optional.empty());
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(
                        connector("HTTP", AiConnectorDO.STATUS_ENABLED, "{}").setReferenced(true));
        assertThatThrownBy(() -> service.delete(CONNECTOR_ID, 0))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_REFERENCED));

        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector("HTTP", AiConnectorDO.STATUS_ENABLED, "{}"));
        service.delete(CONNECTOR_ID, 0);
        verify(connectorMapper).deleteById(CONNECTOR_ID);
    }

    @Test
    void probeGoesThroughTheGovernedOutboundClientAndReportsStableCodes() {
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector(
                        "HTTP",
                        AiConnectorDO.STATUS_ENABLED,
                        "{\"baseUrl\":\"https://crm.example.com\",\"method\":\"GET\",\"healthPath\":\"/health\"}"));
        when(httpClient.execute(any(ExternalHttpRequest.class)))
                .thenReturn(new ExternalHttpResponse(200, Map.of(), new byte[0], 0));

        AiConnectorProbeResultDTO supported = service.probe(CONNECTOR_ID);
        assertThat(supported.getStatus()).isEqualTo(AiConnectorProbeDO.STATUS_SUPPORTED);
        assertThat(supported.getDetailCode()).isNull();
        ArgumentCaptor<ExternalHttpRequest> requestCaptor = ArgumentCaptor.forClass(ExternalHttpRequest.class);
        verify(httpClient).execute(requestCaptor.capture());
        assertThat(requestCaptor.getValue().url()).isEqualTo("https://crm.example.com/health");

        // 上游 5xx：记录稳定原因码（不含正文）
        when(httpClient.execute(any(ExternalHttpRequest.class)))
                .thenReturn(new ExternalHttpResponse(503, Map.of(), new byte[0], 0));
        assertThat(service.probe(CONNECTOR_ID).getDetailCode()).isEqualTo("HTTP_503");

        // 出站策略拒绝：记录原因码而不是异常正文
        when(httpClient.execute(any(ExternalHttpRequest.class)))
                .thenThrow(new ExternalHttpException(ExternalHttpException.Reason.TARGET_NOT_ALLOWED, "目标未允许"));
        assertThat(service.probe(CONNECTOR_ID).getDetailCode()).isEqualTo("TARGET_NOT_ALLOWED");

        // 停用的连接器不允许探测
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector("HTTP", AiConnectorDO.STATUS_DISABLED, "{}"));
        assertThatThrownBy(() -> service.probe(CONNECTOR_ID))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_DISABLED));

        // MySQL 未配置秘密：稳定原因码
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector(
                                "MYSQL",
                                AiConnectorDO.STATUS_ENABLED,
                                "{\"host\":\"mysql.internal\",\"port\":3306,\"database\":\"crm\",\"username\":\"ro\"}")
                        .setCredentialCiphertext(null));
        assertThat(service.probe(CONNECTOR_ID).getDetailCode()).isEqualTo("CREDENTIAL_UNAVAILABLE");

        verify(probeMapper, org.mockito.Mockito.atLeastOnce()).insert(any(AiConnectorProbeDO.class));
    }

    @Test
    void readsAreBoundedAndProbeHistoryIsReturned() {
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector("HTTP", AiConnectorDO.STATUS_ENABLED, "{}"));
        when(connectorMapper.selectPage(any(PageParam.class), anyString(), anyString()))
                .thenReturn(new PageResult<>(List.of(connector("HTTP", AiConnectorDO.STATUS_ENABLED, "{}")), 1L));
        when(probeMapper.selectByConnector(CONNECTOR_ID))
                .thenReturn(List.of(new AiConnectorProbeDO().setId(1L).setConnectorId(CONNECTOR_ID)));

        assertThat(service.getConnector(CONNECTOR_ID).getCode()).isEqualTo("crm-http");
        assertThat(service.getPage(new PageParam(), "HTTP", "ENABLED").getTotal())
                .isEqualTo(1);
        assertThat(service.listProbes(CONNECTOR_ID)).hasSize(1);

        when(connectorMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.getConnector(999L))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_NOT_FOUND));
        assertThatThrownBy(() -> service.getConnector(null))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_NOT_FOUND));
    }
}
