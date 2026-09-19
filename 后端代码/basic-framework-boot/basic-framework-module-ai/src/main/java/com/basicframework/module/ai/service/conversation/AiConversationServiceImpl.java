package com.basicframework.module.ai.service.conversation;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONVERSATION_KEY_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SERVICE_RELEASE_NOT_PUBLISHED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationDO;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationMessageDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.mysql.conversation.AiConversationMapper;
import com.basicframework.module.ai.dal.mysql.conversation.AiConversationMessageMapper;
import com.basicframework.module.ai.domain.runtime.AiRunSnapshot;
import com.basicframework.module.ai.enums.AiFieldRules;
import com.basicframework.module.ai.service.conversation.dto.AiConversationCreateDTO;
import com.basicframework.module.ai.service.conversation.dto.AiConversationMessageSaveDTO;
import com.basicframework.module.ai.service.conversation.dto.AiConversationRunContextDTO;
import com.basicframework.module.ai.service.serviceconfig.AiServiceReleaseService;
import com.basicframework.module.ai.service.serviceconfig.AiServiceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 会话与消息存储实现（O01）。
 *
 * <p>归属判定只用服务端解析出的主体：所有查询都带应用+主体条件，
 * 越权访问与不存在同语义。删除先关闭访问（状态 + 消息），再走标准逻辑删除；
 * 正文的物理清理由 O06 的保留策略处理。
 */
@Service
@RequiredArgsConstructor
public class AiConversationServiceImpl implements AiConversationService {

    /** 标题长度上限（与 V59 列定义一致）。 */
    private static final int MAX_TITLE_LENGTH = 128;

    /** 业务上下文长度上限（与 V59 列定义一致）。 */
    private static final int MAX_CONTEXT_LENGTH = 4_000;

    /** 历史消息默认与最大条数（运行入口按预算再裁剪）。 */
    private static final int DEFAULT_HISTORY_LIMIT = 20;

    private static final int MAX_HISTORY_LIMIT = 100;

    /** 允许的消息角色（与上下文构造器的历史角色词表一致）。 */
    private static final Set<String> ROLES = Set.of(
            AiConversationMessageDO.ROLE_USER,
            AiConversationMessageDO.ROLE_ASSISTANT,
            AiConversationMessageDO.ROLE_SYSTEM);

    private final AiConversationMapper conversationMapper;

    private final AiConversationMessageMapper messageMapper;

    private final AiConversationSubjectResolver subjectResolver;

    private final AiServiceService serviceService;

    private final AiServiceReleaseService releaseService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AiConversationCreateDTO createDTO) {
        AiConversationSubject subject = requireSubject();
        if (createDTO == null
                || !AiFieldRules.isValidConversationKey(createDTO.getConversationKey())
                || (createDTO.getTitle() != null && createDTO.getTitle().length() > MAX_TITLE_LENGTH)) {
            throw exception(AI_REQUEST_INVALID);
        }
        String context = normalizeContext(createDTO.getBusinessContext());
        if (conversationMapper.selectByKey(
                        subject.applicationId(),
                        subject.subjectTypeName(),
                        subject.externalUserId(),
                        createDTO.getConversationKey())
                != null) {
            throw exception(AI_CONVERSATION_KEY_DUPLICATE, createDTO.getConversationKey());
        }
        if (createDTO.getServiceId() != null) {
            requireOwnedService(subject, createDTO.getServiceId());
        }
        AiConversationDO conversation = new AiConversationDO()
                .setApplicationId(subject.applicationId())
                .setSubjectType(subject.subjectTypeName())
                .setExternalUserId(subject.externalUserId())
                .setConversationKey(createDTO.getConversationKey())
                .setTitle(createDTO.getTitle() == null ? "" : createDTO.getTitle())
                .setServiceId(createDTO.getServiceId())
                .setBusinessContext(context)
                .setMessageCount(0)
                .setStatus(AiConversationDO.STATUS_ACTIVE)
                .setVersion(0);
        conversationMapper.insert(conversation);
        return conversation.getId();
    }

    @Override
    public PageResult<AiConversationDO> getPage(PageParam pageParam) {
        AiConversationSubject subject = requireSubject();
        return conversationMapper.selectPageBySubject(
                pageParam, subject.applicationId(), subject.subjectTypeName(), subject.externalUserId());
    }

    @Override
    public AiConversationDO getConversation(Long id) {
        return requireOwned(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rename(Long id, String title, Integer version) {
        requireVersion(version);
        if (!StringUtils.hasText(title) || title.length() > MAX_TITLE_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiConversationDO conversation = requireOwned(id);
        if (conversationMapper.updateWithVersion(
                        new AiConversationDO().setId(id).setTitle(title).setVersion(version + 1), version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Integer version) {
        requireVersion(version);
        AiConversationDO conversation = requireOwned(id);
        if (conversationMapper.updateWithVersion(
                        new AiConversationDO()
                                .setId(id)
                                .setStatus(AiConversationDO.STATUS_DELETED)
                                .setVersion(version + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        // 先关闭访问：会话状态与全部消息同时失效，读取立即 404；正文等待保留策略清理
        messageMapper.releaseAll(id);
        // 逻辑删除列不能由普通 update 设置：状态切换后再走标准逻辑删除（同一事务）
        conversationMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindService(Long id, Long serviceId, Integer version) {
        requireVersion(version);
        if (serviceId == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiConversationSubject subject = requireSubject();
        AiConversationDO conversation = requireOwned(id);
        if (conversation.getReleaseId() != null) {
            // 已固定发布版本：升级会话必须显式新建或迁移，不能悄悄换服务
            throw exception(AI_STATE_CONFLICT);
        }
        requireOwnedService(subject, serviceId);
        if (conversationMapper.updateWithVersion(
                        new AiConversationDO().setId(id).setServiceId(serviceId).setVersion(version + 1), version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindRelease(Long id, Long releaseId, Integer version) {
        requireVersion(version);
        if (releaseId == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiConversationDO conversation = requireOwned(id);
        if (conversation.getServiceId() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (conversation.getReleaseId() != null) {
            // 会话版本只固定一次：换版本需要显式新建或迁移会话
            throw exception(AI_STATE_CONFLICT);
        }
        AiServiceReleaseDO release = requirePublishedRelease(conversation.getServiceId(), releaseId);
        if (conversationMapper.updateWithVersion(
                        new AiConversationDO()
                                .setId(id)
                                .setReleaseId(release.getId())
                                .setVersion(version + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long appendMessage(AiConversationMessageSaveDTO saveDTO) {
        if (saveDTO == null
                || saveDTO.getConversationId() == null
                || !StringUtils.hasText(saveDTO.getContent())
                || saveDTO.getContent().length() > AiFieldRules.MESSAGE_MAX_LENGTH
                || !ROLES.contains(saveDTO.getRole() == null ? "" : saveDTO.getRole())) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiConversationDO conversation = requireOwned(saveDTO.getConversationId());
        int nextSequence = nextSequence(conversation.getId());
        AiConversationMessageDO message = new AiConversationMessageDO()
                .setConversationId(conversation.getId())
                .setApplicationId(conversation.getApplicationId())
                .setSubjectType(conversation.getSubjectType())
                .setExternalUserId(conversation.getExternalUserId())
                .setSequenceNo(nextSequence)
                .setRole(saveDTO.getRole())
                .setContent(saveDTO.getContent())
                .setContentHash(sha256(saveDTO.getContent()))
                .setSourceRunId(saveDTO.getSourceRunId())
                .setStatus(AiConversationMessageDO.STATUS_ACTIVE)
                .setVersion(0);
        messageMapper.insert(message);
        // 会话行乐观锁串行化并发追加：CAS 失败时整个事务回滚，消息不会留下
        int current = conversation.getVersion() == null ? 0 : conversation.getVersion();
        if (conversationMapper.updateWithVersion(
                        new AiConversationDO()
                                .setId(conversation.getId())
                                .setMessageCount(
                                        (conversation.getMessageCount() == null ? 0 : conversation.getMessageCount())
                                                + 1)
                                .setLastMessageTime(LocalDateTime.now())
                                .setVersion(current + 1),
                        current)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        return message.getId();
    }

    @Override
    public List<AiConversationMessageDO> listMessages(Long conversationId, Integer afterSequence, Integer limit) {
        AiConversationDO conversation = requireOwned(conversationId);
        return messageMapper.selectBySequence(conversation.getId(), afterSequence, boundLimit(limit));
    }

    @Override
    public AiConversationRunContextDTO loadRunContext(Long conversationId, Integer historyLimit) {
        AiConversationDO conversation = requireOwned(conversationId);
        AiRunSnapshot pin = null;
        if (conversation.getReleaseId() != null && conversation.getServiceId() != null) {
            AiServiceReleaseDO release =
                    requirePublishedRelease(conversation.getServiceId(), conversation.getReleaseId());
            pin = AiRunSnapshot.of(release, releaseService.listReleaseBindings(release.getId()));
            // 旧上下文不携带权限：进入新运行前按**当前**授权重新判定固定版本，失权即拒绝
            releaseService.resolvePinnedRun(pin);
        }
        return new AiConversationRunContextDTO()
                .setConversation(conversation)
                .setPin(pin)
                .setBusinessContext(conversation.getBusinessContext())
                .setHistory(messageMapper.selectBySequence(conversationId, null, boundLimit(historyLimit)));
    }

    private int nextSequence(Long conversationId) {
        Integer max = messageMapper.selectMaxSequence(conversationId);
        return max == null ? 1 : max + 1;
    }

    private static int boundLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_HISTORY_LIMIT;
        }
        if (limit < 1) {
            throw exception(AI_REQUEST_INVALID);
        }
        return Math.min(limit, MAX_HISTORY_LIMIT);
    }

    private AiConversationDO requireOwned(Long id) {
        AiConversationSubject subject = requireSubject();
        AiConversationDO conversation = id == null
                ? null
                : conversationMapper.selectOwned(
                        id, subject.applicationId(), subject.subjectTypeName(), subject.externalUserId());
        if (conversation == null || !AiConversationDO.STATUS_ACTIVE.equals(conversation.getStatus())) {
            // 越权与不存在同语义：不给出"存在但无权"的区分
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        return conversation;
    }

    private AiConversationSubject requireSubject() {
        return subjectResolver.resolveCurrent().orElseThrow(() -> exception(AI_RESOURCE_NOT_FOUND));
    }

    /** 服务必须属于当前应用：会话不能绑定其它应用的服务。 */
    private void requireOwnedService(AiConversationSubject subject, Long serviceId) {
        AiServiceDO service = serviceService.getService(serviceId);
        if (service == null || !subject.applicationId().equals(service.getAppId())) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
    }

    private AiServiceReleaseDO requirePublishedRelease(Long serviceId, Long releaseId) {
        return releaseService.listReleases(serviceId).stream()
                .filter(release -> releaseId.equals(release.getId()))
                .findFirst()
                .filter(release -> !AiServiceReleaseDO.STATUS_CANDIDATE.equals(release.getStatus()))
                .orElseThrow(() -> releaseService.listReleases(serviceId).stream()
                                .anyMatch(release -> releaseId.equals(release.getId()))
                        ? exception(AI_SERVICE_RELEASE_NOT_PUBLISHED)
                        : exception(AI_RESOURCE_NOT_FOUND));
    }

    /** 业务上下文只要求是 JSON 对象且在长度上限内；已注册字段在运行时由上下文构造器判定。 */
    private static String normalizeContext(String businessContext) {
        if (!StringUtils.hasText(businessContext)) {
            return "{}";
        }
        if (businessContext.length() > MAX_CONTEXT_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
        Map<?, ?> parsed;
        try {
            parsed = JsonUtils.parseObject(businessContext, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (parsed == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        return JsonUtils.toJsonString(parsed);
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException missingAlgorithm) {
            // JDK 必然提供 SHA-256：缺失属于环境损坏，按入参错误终止而不是降级为无摘要
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private static void requireVersion(Integer version) {
        if (version == null || version < 0) {
            throw exception(AI_REQUEST_INVALID);
        }
    }
}
