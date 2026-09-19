package com.basicframework.module.ai.service.model;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_CAPABILITY_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_EMBEDDING_DIMENSION_CHANGED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_ENDPOINT_DISABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_ENDPOINT_NAME_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_ENDPOINT_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.dal.mysql.model.AiModelEndpointMapper;
import com.basicframework.module.ai.dal.mysql.model.AiModelEndpointRevisionMapper;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/**
 * 模型端点服务实现。
 *
 * <p>关键约束：
 * <ul>
 *   <li>非秘密配置（modelId/能力）每次变更写入新的不可变版本；</li>
 *   <li>凭据经 {@link CredentialCipher} 加密后只存在端点行，轮换仅递增 credentialRevision；</li>
 *   <li>被发布服务引用后 provider/baseUrl 不可原地修改，地址迁移必须新建端点；</li>
 *   <li>所有修改走 version 乐观锁 CAS，冲突返回状态冲突（409 语义）。</li>
 * </ul>
 */
@Service
@Validated
@RequiredArgsConstructor
public class AiModelEndpointServiceImpl implements AiModelEndpointService {

    /** 凭据加密的上下文（AAD）：与端点强绑定，密文不可跨端点搬移。 */
    private static final String CREDENTIAL_CONTEXT_PREFIX = "ai_model_endpoint:";

    /** 可声明能力以接缝的 {@code ModelCapability} 词汇为准：新增能力不需要再改这里（S01 修正）。
     *
     * <p>历史上这里是硬编码的 {@code List.of("TEXT", "EMBEDDING")}，导致 M03/M04 新增的
     * {@code TEXT_STREAM}/{@code STRUCTURED_OUTPUT}/{@code TOOL_CALLING} 无法配置到端点上。
     */
    private static final java.util.Set<String> SUPPORTED_CAPABILITIES = java.util.Arrays.stream(
                    com.basicframework.framework.ai.core.model.ModelCapability.values())
            .map(Enum::name)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final AiModelEndpointMapper endpointMapper;

    private final AiModelEndpointRevisionMapper revisionMapper;

    private final CredentialCipher credentialCipher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createEndpoint(AiModelEndpointSaveDTO saveDTO) {
        validateCapabilities(saveDTO.getCapabilities());
        if (endpointMapper.selectByName(saveDTO.getName()) != null) {
            throw exception(AI_MODEL_ENDPOINT_NAME_DUPLICATE, saveDTO.getName());
        }
        boolean credentialProvided = StrUtil.isNotBlank(saveDTO.getCredential());
        AiModelEndpointDO endpoint = new AiModelEndpointDO()
                .setName(saveDTO.getName())
                .setProvider(saveDTO.getProvider())
                .setBaseUrl(saveDTO.getBaseUrl())
                .setConfigRevision(1)
                .setCredentialRevision(credentialProvided ? 1 : 0)
                .setEnabled(false)
                .setReferenced(false)
                .setVersion(0);
        endpointMapper.insert(endpoint);
        // 凭据密文需要端点编号作为 AAD，插入后再写入
        if (credentialProvided) {
            updateWithVersionCas(
                    endpoint.getId(),
                    0,
                    new AiModelEndpointDO()
                            .setCredentialCiphertext(credentialCipher.encrypt(
                                    saveDTO.getCredential(), credentialContext(endpoint.getId()))));
        }
        revisionMapper.insert(new AiModelEndpointRevisionDO()
                .setEndpointId(endpoint.getId())
                .setRevision(1)
                .setModelId(saveDTO.getModelId())
                .setCapabilities(String.join(",", saveDTO.getCapabilities())));
        return endpoint.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateEndpoint(AiModelEndpointSaveDTO saveDTO) {
        AiModelEndpointDO existing = validateEndpointExists(saveDTO.getId());
        validateCapabilities(saveDTO.getCapabilities());
        requireVersion(saveDTO.getVersion());
        // 引用后地址与提供方冻结：地址迁移必须新建端点
        if (Boolean.TRUE.equals(existing.getReferenced())
                && (!Objects.equals(existing.getProvider(), saveDTO.getProvider())
                        || !Objects.equals(existing.getBaseUrl(), saveDTO.getBaseUrl()))) {
            throw exception(AI_STATE_CONFLICT, "端点已被发布服务引用，provider/baseUrl 不可修改；地址迁移请新建端点");
        }
        AiModelEndpointRevisionDO current =
                revisionMapper.selectByEndpointIdAndRevision(existing.getId(), existing.getConfigRevision());
        boolean nonSecretChanged = current == null
                || !Objects.equals(current.getModelId(), saveDTO.getModelId())
                || !Objects.equals(current.getCapabilities(), String.join(",", saveDTO.getCapabilities()));
        Integer nextRevision =
                nonSecretChanged ? revisionMapper.selectNextRevision(existing.getId()) : existing.getConfigRevision();
        AiModelEndpointDO update = new AiModelEndpointDO()
                .setId(existing.getId())
                .setName(saveDTO.getName())
                .setProvider(saveDTO.getProvider())
                .setBaseUrl(saveDTO.getBaseUrl())
                .setConfigRevision(nextRevision);
        // 修改端点不携带凭据时不做任何凭据变更（轮换走独立入口）
        if (StrUtil.isNotBlank(saveDTO.getCredential())) {
            update.setCredentialCiphertext(
                            credentialCipher.encrypt(saveDTO.getCredential(), credentialContext(existing.getId())))
                    .setCredentialRevision(existing.getCredentialRevision() + 1);
        }
        // 先做版本 CAS：失败直接抛冲突，不留下任何孤儿版本行
        if (updateWithVersionCas(existing.getId(), saveDTO.getVersion(), update) == 0) {
            throw exception(AI_STATE_CONFLICT, "端点已被其他操作修改，请刷新后重试");
        }
        if (nonSecretChanged) {
            revisionMapper.insert(new AiModelEndpointRevisionDO()
                    .setEndpointId(existing.getId())
                    .setRevision(nextRevision)
                    .setModelId(saveDTO.getModelId())
                    .setCapabilities(String.join(",", saveDTO.getCapabilities())));
        }
    }

    @Override
    public void updateEndpointStatus(Long id, Integer version, Boolean enabled) {
        validateEndpointExists(id);
        if (updateWithVersionCas(id, version, new AiModelEndpointDO().setEnabled(enabled)) == 0) {
            throw exception(AI_STATE_CONFLICT, "端点已被其他操作修改，请刷新后重试");
        }
    }

    @Override
    public void rotateCredential(Long id, Integer version, String credential) {
        AiModelEndpointDO existing = validateEndpointExists(id);
        AiModelEndpointDO update = new AiModelEndpointDO()
                .setCredentialCiphertext(credentialCipher.encrypt(credential, credentialContext(id)))
                .setCredentialRevision(existing.getCredentialRevision() + 1);
        if (updateWithVersionCas(id, version, update) == 0) {
            throw exception(AI_STATE_CONFLICT, "端点已被其他操作修改，请刷新后重试");
        }
    }

    public void deleteEndpoint(Long id, Integer version) {
        AiModelEndpointDO existing = validateEndpointExists(id);
        if (Boolean.TRUE.equals(existing.getReferenced())) {
            throw exception(AI_STATE_CONFLICT, "端点已被发布服务引用，删除前必须先解除引用");
        }
        // 先做版本 CAS（并发保护），再走 MyBatis-Plus 标准逻辑删除：
        // 逻辑删除列不能被普通 update 设置，因此两步在同一个事务内完成。
        if (updateWithVersionCas(id, version, new AiModelEndpointDO().setName(existing.getName())) == 0) {
            throw exception(AI_STATE_CONFLICT, "端点已被其他操作修改，请刷新后重试");
        }
        endpointMapper.deleteById(id);
    }

    @Override
    public void markReferenced(Long id, Integer version) {
        validateEndpointExists(id);
        if (updateWithVersionCas(id, version, new AiModelEndpointDO().setReferenced(true)) == 0) {
            throw exception(AI_STATE_CONFLICT, "端点已被其他操作修改，请刷新后重试");
        }
    }

    @Override
    public AiModelEndpointDO getEndpoint(Long id) {
        return validateEndpointExists(id);
    }

    @Override
    public PageResult<AiModelEndpointDO> getEndpointPage(PageParam pageParam, String name, String provider) {
        return endpointMapper.selectPage(pageParam, name, provider);
    }

    @Override
    public List<AiModelEndpointRevisionDO> getRevisions(Long endpointId) {
        validateEndpointExists(endpointId);
        return revisionMapper.selectListByEndpointId(endpointId);
    }

    @Override
    public AiModelEndpointDO getEnabledEndpoint(Long id) {
        AiModelEndpointDO endpoint = validateEndpointExists(id);
        if (!Boolean.TRUE.equals(endpoint.getEnabled())) {
            throw exception(AI_MODEL_ENDPOINT_DISABLED);
        }
        return endpoint;
    }

    @Override
    public void assertEmbeddingDimensionUnchanged(Long endpointId, Integer observedDimension) {
        if (observedDimension == null || observedDimension <= 0) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiModelEndpointDO endpoint = validateEndpointExists(endpointId);
        Integer recorded = endpoint.getEmbeddingDimension();
        if (recorded == null) {
            if (endpointMapper.updateEmbeddingDimensionIfAbsent(endpointId, observedDimension) > 0) {
                return;
            }
            // 并发首写：以数据库中的最终值为准，必须与本次观测一致
            recorded = validateEndpointExists(endpointId).getEmbeddingDimension();
        }
        if (!observedDimension.equals(recorded)) {
            throw exception(AI_MODEL_EMBEDDING_DIMENSION_CHANGED);
        }
    }

    private AiModelEndpointDO validateEndpointExists(Long id) {
        AiModelEndpointDO endpoint = id == null ? null : endpointMapper.selectById(id);
        if (endpoint == null) {
            // 不存在与无权访问保持同一语义（见 F08 错误码映射）
            throw exception(AI_MODEL_ENDPOINT_NOT_FOUND);
        }
        return endpoint;
    }

    private static void requireVersion(Integer version) {
        if (version == null) {
            throw exception(AI_REQUEST_INVALID, "修改端点必须携带乐观锁版本");
        }
    }

    private static void validateCapabilities(List<String> capabilities) {
        if (capabilities == null
                || capabilities.isEmpty()
                || capabilities.stream().anyMatch(capability -> !SUPPORTED_CAPABILITIES.contains(capability))) {
            throw exception(AI_MODEL_CAPABILITY_UNSUPPORTED);
        }
    }

    private int updateWithVersionCas(Long id, Integer expectedVersion, AiModelEndpointDO update) {
        update.setId(id);
        update.setVersion(expectedVersion + 1);
        return endpointMapper.updateWithVersion(update, expectedVersion);
    }

    private static String credentialContext(Long endpointId) {
        return CREDENTIAL_CONTEXT_PREFIX + endpointId;
    }
}
