package com.basicframework.module.ai.service.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlConnectionTarget;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlMetadataDiscovery;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlObjectMetadata;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlPoolRegistry;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlPoolStateDTO;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlQueryRequest;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlQueryResultDTO;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlReadOnlyExecutor;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** D03 服务层：解析连接器 → 解密秘密 → 组装只读目标；默认拒绝（不存在/类型不符/停用/无凭据）。 */
class AiMysqlConnectorServiceImplTest {

    private static final Long CONNECTOR_ID = 42L;

    private final AiConnectorMapper connectorMapper = mock(AiConnectorMapper.class);

    private final CredentialCipher credentialCipher = mock(CredentialCipher.class);

    private final AiMysqlPoolRegistry poolRegistry = mock(AiMysqlPoolRegistry.class);

    private final AiMysqlMetadataDiscovery metadataDiscovery = mock(AiMysqlMetadataDiscovery.class);

    private final AiMysqlReadOnlyExecutor readOnlyExecutor = mock(AiMysqlReadOnlyExecutor.class);

    private final AiMysqlConnectorServiceImpl service = new AiMysqlConnectorServiceImpl(
            connectorMapper, credentialCipher, poolRegistry, metadataDiscovery, readOnlyExecutor);

    private static AiConnectorDO connector() {
        return new AiConnectorDO()
                .setId(CONNECTOR_ID)
                .setCode("crm-readonly")
                .setConnectorType(AiConnectorDO.TYPE_MYSQL)
                .setStatus(AiConnectorDO.STATUS_ENABLED)
                .setConfigJson("{\"host\":\"db.internal\",\"port\":3306,\"database\":\"crm\",\"username\":\"readonly\","
                        + "\"allowedObjects\":[\"crm.orders\",\"crm.order_view\"]}")
                .setCredentialCiphertext("v1:encrypted")
                .setCredentialRevision(3);
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @BeforeEach
    void setUp() {
        when(connectorMapper.selectById(CONNECTOR_ID)).thenReturn(connector());
        when(credentialCipher.decrypt(anyString(), anyString())).thenReturn("integration-only-secret");
    }

    @Test
    void buildsReadOnlyTargetWithAllowListAndDecryptedSecretOnly() {
        when(metadataDiscovery.discover(any()))
                .thenReturn(List.of(
                        new AiMysqlObjectMetadata("crm", "orders", AiMysqlObjectMetadata.TYPE_TABLE, List.of())));

        List<AiMysqlObjectMetadata> objects = service.discoverObjects(CONNECTOR_ID);
        assertThat(objects).hasSize(1);

        ArgumentCaptor<AiMysqlConnectionTarget> captor = ArgumentCaptor.forClass(AiMysqlConnectionTarget.class);
        verify(metadataDiscovery).discover(captor.capture());
        AiMysqlConnectionTarget target = captor.getValue();
        assertThat(target.connectorId()).isEqualTo(CONNECTOR_ID);
        assertThat(target.database()).isEqualTo("crm");
        assertThat(target.allowedObjects()).containsExactly("crm.orders", "crm.order_view");
        assertThat(target.credentialRevision()).isEqualTo(3);
        assertThat(target.authorizes("crm", "orders")).isTrue();
        assertThat(target.toString())
                .as("目标字符串不得含秘密与连接串")
                .doesNotContain("integration-only-secret", "db.internal", "jdbc:mysql");
        // 秘密解密上下文与 D01 写入时一致（AAD 绑定连接器编号）
        verify(credentialCipher).decrypt("v1:encrypted", "ai_connector:" + CONNECTOR_ID);
    }

    @Test
    void delegatesExecutionCancelAndPoolLifecycle() {
        AiMysqlQueryRequest request = new AiMysqlQueryRequest("SELECT id FROM crm.orders", List.of(), 5, 1_000);
        AiMysqlQueryResultDTO result = new AiMysqlQueryResultDTO().setHandleId("handle-1");
        when(readOnlyExecutor.execute(any(), any())).thenReturn(result);
        when(readOnlyExecutor.cancel("handle-1")).thenReturn(true);
        when(readOnlyExecutor.inFlightHandles()).thenReturn(List.of("handle-1"));
        when(poolRegistry.state(CONNECTOR_ID)).thenReturn(AiMysqlPoolStateDTO.absent(CONNECTOR_ID));
        when(poolRegistry.closePool(CONNECTOR_ID)).thenReturn(true);

        assertThat(service.execute(CONNECTOR_ID, request)).isSameAs(result);
        assertThat(service.cancel("handle-1")).isTrue();
        assertThat(service.inFlightHandles()).containsExactly("handle-1");
        assertThat(service.poolState(CONNECTOR_ID).state()).isEqualTo(AiMysqlPoolStateDTO.STATE_ABSENT);
        assertThat(service.closePool(CONNECTOR_ID)).isTrue();
        assertThatThrownBy(() -> service.execute(CONNECTOR_ID, null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_CONFIG_INVALID));
    }

    @Test
    void rejectsMissingDisabledWrongTypeAndCredentialLessConnectors() {
        when(connectorMapper.selectById(CONNECTOR_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.discoverObjects(CONNECTOR_ID))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_NOT_FOUND));

        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector().setConnectorType(AiConnectorDO.TYPE_HTTP));
        assertThatThrownBy(() -> service.discoverObjects(CONNECTOR_ID))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_CONFIG_INVALID));

        when(connectorMapper.selectById(CONNECTOR_ID)).thenReturn(connector().setStatus(AiConnectorDO.STATUS_DISABLED));
        assertThatThrownBy(() -> service.discoverObjects(CONNECTOR_ID))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_DISABLED));

        when(connectorMapper.selectById(CONNECTOR_ID)).thenReturn(connector().setCredentialCiphertext(null));
        assertThatThrownBy(() -> service.discoverObjects(CONNECTOR_ID))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_CONFIG_INVALID));
    }

    @Test
    void discoveryIsEmptyWhenNoObjectIsAuthorized() {
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector()
                        .setConfigJson("{\"host\":\"db.internal\",\"port\":3306,\"database\":\"crm\","
                                + "\"username\":\"readonly\"}"));
        when(metadataDiscovery.discover(any())).thenReturn(List.of());

        assertThat(service.discoverObjects(CONNECTOR_ID)).isEmpty();

        ArgumentCaptor<AiMysqlConnectionTarget> captor = ArgumentCaptor.forClass(AiMysqlConnectionTarget.class);
        verify(metadataDiscovery).discover(captor.capture());
        assertThat(captor.getValue().allowedObjects()).isEmpty();
    }
}
