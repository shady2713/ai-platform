package com.basicframework.module.ai.service.knowledge;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_BASE_CODE_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_BASE_CONFIG_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_BASE_DISABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_BASE_NOT_EMPTY;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_BASE_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_BASE_REFERENCED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeDocumentMapper;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceResourceMapper;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeBaseSaveDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 知识库管理实现（K02）。
 *
 * <p>不可修改的字段（标识、可见性、所属应用、嵌入模型与维度）在更新时被**忽略而不是报错**：
 * 与 D04 的"标识与来源不可修改"同一处理方式，避免客户端把查询回来的整对象再提交时被无谓拒绝；
 * 但服务端不会因为忽略就接受新的取值——库里仍是创建时的值。
 */
@Service
@RequiredArgsConstructor
public class AiKnowledgeBaseServiceImpl implements AiKnowledgeBaseService {

    private final AiKnowledgeBaseMapper baseMapper;

    private final AiKnowledgeDocumentMapper documentMapper;

    private final AiServiceResourceMapper serviceResourceMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AiKnowledgeBaseSaveDTO saveDTO) {
        if (saveDTO == null) {
            throw exception(AI_KNOWLEDGE_BASE_CONFIG_INVALID);
        }
        String code = AiKnowledgeStates.requireCode(saveDTO.getCode());
        String name = requireName(saveDTO.getName());
        String visibility = AiKnowledgeStates.requireVisibility(saveDTO.getVisibility());
        Long ownerApplicationId = saveDTO.getOwnerApplicationId();
        if (AiKnowledgeBaseDO.VISIBILITY_APPLICATION.equals(visibility) && ownerApplicationId == null) {
            // 应用专用知识库必须声明所属应用；共享库反过来不能挂在某个应用下
            throw exception(AI_KNOWLEDGE_BASE_CONFIG_INVALID);
        }
        if (AiKnowledgeBaseDO.VISIBILITY_SHARED.equals(visibility) && ownerApplicationId != null) {
            throw exception(AI_KNOWLEDGE_BASE_CONFIG_INVALID);
        }
        String embeddingModel = AiKnowledgeStates.requireEmbeddingModel(saveDTO.getEmbeddingModel());
        int dimension = AiKnowledgeStates.requireDimension(saveDTO.getEmbeddingDimension());
        int retentionDays = AiKnowledgeStates.requireRetentionDays(saveDTO.getRetentionDays());
        if (baseMapper.selectByCode(code) != null) {
            throw exception(AI_KNOWLEDGE_BASE_CODE_DUPLICATE, code);
        }
        AiKnowledgeBaseDO knowledgeBase = new AiKnowledgeBaseDO()
                .setCode(code)
                .setName(name)
                .setDescription(saveDTO.getDescription() == null ? "" : saveDTO.getDescription())
                .setVisibility(visibility)
                .setOwnerApplicationId(ownerApplicationId)
                .setManagerUserId(saveDTO.getManagerUserId())
                .setEmbeddingModel(embeddingModel)
                .setEmbeddingDimension(dimension)
                .setActiveGenerationNo(0)
                .setRetentionDays(retentionDays)
                .setStatus(AiKnowledgeBaseDO.STATUS_ENABLED)
                .setVersion(0);
        baseMapper.insert(knowledgeBase);
        return knowledgeBase.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(AiKnowledgeBaseSaveDTO saveDTO) {
        if (saveDTO == null || saveDTO.getId() == null || saveDTO.getVersion() == null) {
            throw exception(AI_KNOWLEDGE_BASE_CONFIG_INVALID);
        }
        AiKnowledgeBaseDO existing = requireKnowledgeBase(saveDTO.getId());
        String name = requireName(saveDTO.getName());
        int retentionDays = AiKnowledgeStates.requireRetentionDays(saveDTO.getRetentionDays());
        if (baseMapper.updateWithVersion(
                        new AiKnowledgeBaseDO()
                                .setId(existing.getId())
                                .setName(name)
                                .setDescription(saveDTO.getDescription() == null ? "" : saveDTO.getDescription())
                                .setManagerUserId(saveDTO.getManagerUserId())
                                .setRetentionDays(retentionDays)
                                .setVersion(existing.getVersion() + 1),
                        saveDTO.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer version, Boolean enabled) {
        if (id == null || version == null || enabled == null) {
            throw exception(AI_KNOWLEDGE_BASE_CONFIG_INVALID);
        }
        AiKnowledgeBaseDO existing = requireKnowledgeBase(id);
        String status = enabled ? AiKnowledgeBaseDO.STATUS_ENABLED : AiKnowledgeBaseDO.STATUS_DISABLED;
        if (baseMapper.updateWithVersion(
                        new AiKnowledgeBaseDO()
                                .setId(existing.getId())
                                .setStatus(status)
                                .setVersion(existing.getVersion() + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Integer version) {
        if (id == null || version == null) {
            throw exception(AI_KNOWLEDGE_BASE_CONFIG_INVALID);
        }
        AiKnowledgeBaseDO existing = requireKnowledgeBase(id);
        long bindings = serviceResourceMapper.selectCount(new LambdaQueryWrapper<AiServiceResourceDO>()
                .eq(AiServiceResourceDO::getResourceType, AiResourceType.KNOWLEDGE_BASE.name())
                .eq(AiServiceResourceDO::getResourceKey, existing.getCode()));
        if (bindings > 0) {
            // 服务绑定（草稿或已发布版本）都算引用：先解除绑定再删库
            throw exception(AI_KNOWLEDGE_BASE_REFERENCED);
        }
        if (!documentMapper.selectByKnowledgeBase(id).isEmpty()) {
            // 仍有存活文档：先删除文档（K07 回收索引与切片）再删库
            throw exception(AI_KNOWLEDGE_BASE_NOT_EMPTY);
        }
        if (baseMapper.updateWithVersion(
                        new AiKnowledgeBaseDO().setId(existing.getId()).setVersion(existing.getVersion() + 1), version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        baseMapper.deleteById(existing.getId());
    }

    @Override
    public AiKnowledgeBaseDO getKnowledgeBase(Long id) {
        return requireKnowledgeBase(id);
    }

    @Override
    public AiKnowledgeBaseDO getByCode(String code) {
        AiKnowledgeBaseDO knowledgeBase = code == null ? null : baseMapper.selectByCode(code.trim());
        if (knowledgeBase == null) {
            throw exception(AI_KNOWLEDGE_BASE_NOT_FOUND);
        }
        return knowledgeBase;
    }

    @Override
    public AiKnowledgeBaseDO requireEnabled(Long id) {
        AiKnowledgeBaseDO knowledgeBase = requireKnowledgeBase(id);
        if (!AiKnowledgeBaseDO.STATUS_ENABLED.equals(knowledgeBase.getStatus())) {
            // 停用后不接受新入库与索引换代：已入库内容的检索由 K06 按库状态判定
            throw exception(AI_KNOWLEDGE_BASE_DISABLED);
        }
        return knowledgeBase;
    }

    @Override
    public PageResult<AiKnowledgeBaseDO> getKnowledgeBasePage(
            PageParam pageParam, String visibility, Long ownerApplicationId, String status) {
        return baseMapper.selectPage(
                pageParam,
                visibility == null ? null : AiKnowledgeStates.requireVisibility(visibility),
                ownerApplicationId,
                status == null ? null : AiKnowledgeStates.requireBaseStatus(status));
    }

    private AiKnowledgeBaseDO requireKnowledgeBase(Long id) {
        AiKnowledgeBaseDO knowledgeBase = id == null ? null : baseMapper.selectById(id);
        if (knowledgeBase == null) {
            throw exception(AI_KNOWLEDGE_BASE_NOT_FOUND);
        }
        return knowledgeBase;
    }

    private static String requireName(String name) {
        if (!StringUtils.hasText(name) || name.trim().length() > 128) {
            throw exception(AI_KNOWLEDGE_BASE_CONFIG_INVALID);
        }
        return name.trim();
    }
}
