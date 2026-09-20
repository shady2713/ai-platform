package com.basicframework.module.ai.service.connector;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_CONFIG_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_DISABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_NOT_FOUND;

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
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 独立 MySQL 只读连接器实现（D03）。
 *
 * <p>职责边界：本类只做"解析连接器 → 解密秘密 → 组装连接目标 → 交给适配器"，
 * 不持有连接、不拼 SQL、不缓存秘密；连接生命周期全部由 {@link AiMysqlPoolRegistry} 管理。
 *
 * <p>默认拒绝：类型不是 MYSQL、已停用、没有凭据、白名单为空，都在这里被挡住（稳定错误码）。
 */
@Service
@RequiredArgsConstructor
public class AiMysqlConnectorServiceImpl implements AiMysqlConnectorService {

    /** 秘密加密的 AAD 上下文前缀（与 D01 写入时保持一致）。 */
    private static final String CREDENTIAL_CONTEXT_PREFIX = "ai_connector:";

    private final AiConnectorMapper connectorMapper;

    private final CredentialCipher credentialCipher;

    private final AiMysqlPoolRegistry poolRegistry;

    private final AiMysqlMetadataDiscovery metadataDiscovery;

    private final AiMysqlReadOnlyExecutor readOnlyExecutor;

    @Override
    public List<AiMysqlObjectMetadata> discoverObjects(Long connectorId) {
        return metadataDiscovery.discover(target(connectorId));
    }

    @Override
    public AiMysqlQueryResultDTO execute(Long connectorId, AiMysqlQueryRequest request) {
        if (request == null) {
            throw exception(AI_CONNECTOR_CONFIG_INVALID);
        }
        return readOnlyExecutor.execute(target(connectorId), request);
    }

    @Override
    public boolean cancel(String handleId) {
        return readOnlyExecutor.cancel(handleId);
    }

    @Override
    public List<String> inFlightHandles() {
        return readOnlyExecutor.inFlightHandles();
    }

    @Override
    public AiMysqlPoolStateDTO poolState(Long connectorId) {
        return poolRegistry.state(connectorId);
    }

    @Override
    public boolean closePool(Long connectorId) {
        return poolRegistry.closePool(connectorId);
    }

    /** 解析连接器并组装只读连接目标（秘密只在此处解密，用完即随目标进入适配器）。 */
    private AiMysqlConnectionTarget target(Long connectorId) {
        AiConnectorDO connector = connectorMapper.selectById(connectorId);
        if (connector == null) {
            throw exception(AI_CONNECTOR_NOT_FOUND);
        }
        if (!AiConnectorDO.TYPE_MYSQL.equals(connector.getConnectorType())) {
            throw exception(AI_CONNECTOR_CONFIG_INVALID);
        }
        if (!AiConnectorDO.STATUS_ENABLED.equals(connector.getStatus())) {
            throw exception(AI_CONNECTOR_DISABLED);
        }
        if (!StringUtils.hasText(connector.getCredentialCiphertext())) {
            throw exception(AI_CONNECTOR_CONFIG_INVALID);
        }
        AiConnectorConfig config = AiConnectorConfig.parse(connector.getConnectorType(), connector.getConfigJson());
        String password = credentialCipher.decrypt(
                connector.getCredentialCiphertext(), CREDENTIAL_CONTEXT_PREFIX + connector.getId());
        return new AiMysqlConnectionTarget(
                connector.getId(),
                config.jdbcUrl(),
                config.string("database"),
                config.string("username"),
                password,
                config.allowedObjects(),
                connector.getCredentialRevision() == null ? 0 : connector.getCredentialRevision());
    }
}
