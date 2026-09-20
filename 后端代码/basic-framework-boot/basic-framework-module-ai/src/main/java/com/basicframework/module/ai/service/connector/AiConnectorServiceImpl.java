package com.basicframework.module.ai.service.connector;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_CODE_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_CONFIG_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_DISABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_REFERENCED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.ExternalHttpException;
import com.basicframework.framework.ai.core.http.ExternalHttpRequest;
import com.basicframework.framework.ai.core.http.ExternalHttpResponse;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorProbeDO;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorMapper;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorProbeMapper;
import com.basicframework.module.ai.service.connector.dto.AiConnectorProbeResultDTO;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 连接器配置与秘密管理实现（D01）。
 *
 * <p>秘密只在写入路径出现（加密后落库）；探测与查询都不返回秘密，
 * 异常与日志只出现稳定原因码（不含主机、凭据与上游报文）。
 */
@Service
@RequiredArgsConstructor
public class AiConnectorServiceImpl implements AiConnectorService {

    /** 秘密加密的 AAD 上下文前缀（与端点凭据保持同一做法）。 */
    private static final String CREDENTIAL_CONTEXT_PREFIX = "ai_connector:";

    /** 连接器标识：字母开头，字母数字与连字符/下划线，长度 3..64。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{2,63}$");

    private static final int MAX_NAME_LENGTH = 128;

    private static final int MAX_CONFIG_LENGTH = 2_000;

    private static final int MAX_CREDENTIAL_LENGTH = 1_024;

    /** MySQL 探测的登录超时（秒）。 */
    private static final int MYSQL_PROBE_TIMEOUT_SECONDS = 5;

    private final AiConnectorMapper connectorMapper;

    private final AiConnectorProbeMapper probeMapper;

    private final CredentialCipher credentialCipher;

    /** 受控出站客户端：只在 AI 能力启用时装配（与模型客户端同一条件）。 */
    private final ObjectProvider<ExternalHttpClient> httpClientProvider;

    /** 引用检查：数据集/工具在各自卡片里注册实现。 */
    private final List<AiConnectorReferenceChecker> referenceCheckers;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AiConnectorSaveDTO saveDTO) {
        requireSaveFields(saveDTO, true);
        AiConnectorConfig config = AiConnectorConfig.parse(saveDTO.getConnectorType(), saveDTO.getConfigJson());
        if (connectorMapper.selectByCode(saveDTO.getCode()) != null) {
            throw exception(AI_CONNECTOR_CODE_DUPLICATE, saveDTO.getCode());
        }
        AiConnectorDO connector = new AiConnectorDO()
                .setCode(saveDTO.getCode())
                .setName(saveDTO.getName())
                .setConnectorType(config.type())
                .setStatus(AiConnectorDO.STATUS_ENABLED)
                .setConfigJson(config.canonicalJson())
                .setCredentialRevision(0)
                .setReferenced(false)
                .setVersion(0);
        connectorMapper.insert(connector);
        if (StringUtils.hasText(saveDTO.getCredential())) {
            // 秘密密文需要连接器编号作为 AAD：插入后再写入
            connectorMapper.updateWithVersion(
                    new AiConnectorDO()
                            .setId(connector.getId())
                            .setCredentialCiphertext(credentialCipher.encrypt(
                                    saveDTO.getCredential(), credentialContext(connector.getId())))
                            .setCredentialRevision(1)
                            .setVersion(1),
                    0);
        }
        return connector.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(AiConnectorSaveDTO saveDTO) {
        requireSaveFields(saveDTO, false);
        AiConnectorDO existing = requireConnector(saveDTO.getId());
        if (!existing.getCode().equals(saveDTO.getCode())) {
            // 标识是引用方的稳定键：创建后不可修改
            throw exception(AI_REQUEST_INVALID);
        }
        if (!existing.getConnectorType().equals(saveDTO.getConnectorType())) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiConnectorConfig config = AiConnectorConfig.parse(saveDTO.getConnectorType(), saveDTO.getConfigJson());
        AiConnectorDO update = new AiConnectorDO()
                .setId(existing.getId())
                .setName(saveDTO.getName())
                .setConfigJson(config.canonicalJson())
                .setVersion(saveDTO.getVersion() + 1);
        if (StringUtils.hasText(saveDTO.getCredential())) {
            // 修改携带秘密时才轮换：留空表示保留已有秘密
            update.setCredentialCiphertext(
                            credentialCipher.encrypt(saveDTO.getCredential(), credentialContext(existing.getId())))
                    .setCredentialRevision(
                            (existing.getCredentialRevision() == null ? 0 : existing.getCredentialRevision()) + 1);
        }
        if (connectorMapper.updateWithVersion(update, saveDTO.getVersion()) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rotateCredential(Long id, Integer version, String credential) {
        requireVersion(version);
        if (!StringUtils.hasText(credential) || credential.length() > MAX_CREDENTIAL_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiConnectorDO existing = requireConnector(id);
        if (connectorMapper.updateWithVersion(
                        new AiConnectorDO()
                                .setId(id)
                                .setCredentialCiphertext(credentialCipher.encrypt(credential, credentialContext(id)))
                                .setCredentialRevision((existing.getCredentialRevision() == null
                                                ? 0
                                                : existing.getCredentialRevision())
                                        + 1)
                                .setVersion(version + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer version, Boolean enabled) {
        requireVersion(version);
        if (enabled == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        requireConnector(id);
        if (connectorMapper.updateWithVersion(
                        new AiConnectorDO()
                                .setId(id)
                                .setStatus(enabled ? AiConnectorDO.STATUS_ENABLED : AiConnectorDO.STATUS_DISABLED)
                                .setVersion(version + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Integer version) {
        requireVersion(version);
        AiConnectorDO existing = requireConnector(id);
        // 引用保护：被数据集/工具引用时拒绝删除（引用方通过检查端口注册）
        Optional<String> reference = referenceCheckers.stream()
                .map(checker -> checker.findReference(id))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
        if (reference.isPresent() || Boolean.TRUE.equals(existing.getReferenced())) {
            throw exception(AI_CONNECTOR_REFERENCED);
        }
        if (connectorMapper.updateWithVersion(new AiConnectorDO().setId(id).setVersion(version + 1), version) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        connectorMapper.deleteById(id);
    }

    @Override
    public AiConnectorProbeResultDTO probe(Long id) {
        AiConnectorDO connector = requireConnector(id);
        if (!AiConnectorDO.STATUS_ENABLED.equals(connector.getStatus())) {
            // 停用的连接器不允许探测：避免"停用后仍在对外发起连接"
            throw exception(AI_CONNECTOR_DISABLED);
        }
        AiConnectorConfig config = AiConnectorConfig.parse(connector.getConnectorType(), connector.getConfigJson());
        long startedAt = System.nanoTime();
        String detailCode = AiConnectorDO.TYPE_HTTP.equals(connector.getConnectorType())
                ? probeHttp(connector, config)
                : probeMysql(connector, config);
        int latency = (int) ((System.nanoTime() - startedAt) / 1_000_000L);
        AiConnectorProbeDO probe = new AiConnectorProbeDO()
                .setConnectorId(id)
                .setProbeKind(
                        AiConnectorDO.TYPE_HTTP.equals(connector.getConnectorType())
                                ? AiConnectorProbeDO.KIND_HTTP_CONNECTIVITY
                                : AiConnectorProbeDO.KIND_MYSQL_CONNECTIVITY)
                .setStatus(detailCode == null ? AiConnectorProbeDO.STATUS_SUPPORTED : AiConnectorProbeDO.STATUS_FAILED)
                .setDetailCode(detailCode)
                .setLatencyMs(latency)
                .setVersion(0);
        probeMapper.insert(probe);
        return new AiConnectorProbeResultDTO()
                .setConnectorId(id)
                .setProbeKind(probe.getProbeKind())
                .setStatus(probe.getStatus())
                .setDetailCode(detailCode)
                .setLatencyMs(latency);
    }

    /** HTTP 探测：经平台受控出站客户端（默认拒绝一切目标），失败只返回稳定原因码。 */
    private String probeHttp(AiConnectorDO connector, AiConnectorConfig config) {
        ExternalHttpClient client = httpClientProvider.getIfAvailable();
        if (client == null) {
            return "AI_DISABLED";
        }
        String url =
                config.string("baseUrl") + (config.string("healthPath") == null ? "" : config.string("healthPath"));
        try {
            ExternalHttpResponse response = client.execute(new ExternalHttpRequest(
                    "GET",
                    url,
                    authHeaders(connector, config),
                    null,
                    Duration.ofMillis(
                            config.integer("timeoutMillis") == null ? 5_000 : config.integer("timeoutMillis"))));
            return response.status() >= 400 ? "HTTP_" + response.status() : null;
        } catch (ExternalHttpException failure) {
            // 目标未允许/私网被拒/超时/超限：只记录稳定原因码，不记录主机与异常正文
            return failure.getReason().name();
        }
    }

    /** HTTP 认证头：秘密在内存中解密后只放进本次请求头，不写日志、不进异常。 */
    private Map<String, String> authHeaders(AiConnectorDO connector, AiConnectorConfig config) {
        String authType = config.string("authType") == null
                ? "NONE"
                : config.string("authType").toUpperCase(java.util.Locale.ROOT);
        if ("NONE".equals(authType) || !StringUtils.hasText(connector.getCredentialCiphertext())) {
            return Map.of();
        }
        String credential =
                credentialCipher.decrypt(connector.getCredentialCiphertext(), credentialContext(connector.getId()));
        if ("BEARER".equals(authType)) {
            return Map.of("Authorization", "Bearer " + credential);
        }
        // BASIC：秘密按 "用户名:密码" 提供（用户名属于秘密的一部分，不单独落库）
        return Map.of(
                "Authorization",
                "Basic "
                        + java.util.Base64.getEncoder()
                                .encodeToString(credential.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** MySQL 探测：只连接由已校验字段拼出的地址，失败只返回稳定原因码。 */
    private String probeMysql(AiConnectorDO connector, AiConnectorConfig config) {
        if (!StringUtils.hasText(connector.getCredentialCiphertext())) {
            return "CREDENTIAL_UNAVAILABLE";
        }
        String password =
                credentialCipher.decrypt(connector.getCredentialCiphertext(), credentialContext(connector.getId()));
        try (Connection ignored = DriverManager.getConnection(config.jdbcUrl(), config.string("username"), password)) {
            return null;
        } catch (SQLException failure) {
            return "MYSQL_CONNECT_FAILED";
        }
    }

    @Override
    public List<AiConnectorProbeDO> listProbes(Long id) {
        requireConnector(id);
        return probeMapper.selectByConnector(id);
    }

    @Override
    public AiConnectorDO getConnector(Long id) {
        return requireConnector(id);
    }

    @Override
    public PageResult<AiConnectorDO> getPage(PageParam pageParam, String connectorType, String status) {
        return connectorMapper.selectPage(pageParam, connectorType, status);
    }

    private AiConnectorDO requireConnector(Long id) {
        AiConnectorDO connector = id == null ? null : connectorMapper.selectById(id);
        if (connector == null) {
            throw exception(AI_CONNECTOR_NOT_FOUND);
        }
        return connector;
    }

    private static void requireSaveFields(AiConnectorSaveDTO saveDTO, boolean creating) {
        if (saveDTO == null
                || !CODE_PATTERN
                        .matcher(saveDTO.getCode() == null ? "" : saveDTO.getCode())
                        .matches()
                || !StringUtils.hasText(saveDTO.getName())
                || saveDTO.getName().length() > MAX_NAME_LENGTH
                || !StringUtils.hasText(saveDTO.getConfigJson())
                || saveDTO.getConfigJson().length() > MAX_CONFIG_LENGTH
                || (saveDTO.getCredential() != null && saveDTO.getCredential().length() > MAX_CREDENTIAL_LENGTH)) {
            throw exception(AI_CONNECTOR_CONFIG_INVALID);
        }
        if (!creating) {
            requireVersion(saveDTO.getVersion());
        }
    }

    private static void requireVersion(Integer version) {
        if (version == null || version < 0) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private static String credentialContext(Long connectorId) {
        return CREDENTIAL_CONTEXT_PREFIX + connectorId;
    }
}
