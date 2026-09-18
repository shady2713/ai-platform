package com.basicframework.module.ai.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_APPLICATION_CREDENTIAL_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_AUTHORIZATION_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TICKET_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.dal.dataobject.token.AiAccessTicketDO;
import com.basicframework.module.ai.dal.mysql.token.AiAccessTicketMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.auth.dto.AiTicketContextDTO;
import com.basicframework.module.ai.service.auth.dto.AiTicketIssueDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.ai.service.subject.dto.AiSubjectScopeDTO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 访问票据实现（A04）。
 *
 * <p>换票顺序：校验客户端凭据 → 校验主体断言（USER 必须有可信 externalUserId）→
 * 解析主体范围（DENY 即拒绝）→ 按请求对象**裁剪**范围（裁剪后为空则拒绝）→ 生成随机 token、
 * 落库（只存摘要 + 范围快照 + 指纹 + 到期时间）→ 返回明文 token。
 * 事务提交前票据对其他事务不可见，因此回滚不会留下可用票据。
 */
@Service
public class AiTicketServiceImpl implements AiTicketService {

    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AiApplicationService applicationService;

    private final AiSubjectService subjectService;

    private final AiAccessTicketMapper ticketMapper;

    private final Duration ticketTtl;

    public AiTicketServiceImpl(
            AiApplicationService applicationService,
            AiSubjectService subjectService,
            AiAccessTicketMapper ticketMapper,
            @Value("${basic-framework.ai.ticket.ttl:PT10M}") Duration ticketTtl) {
        this.applicationService = applicationService;
        this.subjectService = subjectService;
        this.ticketMapper = ticketMapper;
        this.ticketTtl = ticketTtl;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiTicketIssueDTO issue(
            String appCode,
            String appSecret,
            AiSubjectType subjectType,
            String externalUserId,
            List<String> resourceHints) {
        // 1) 客户端凭据：浏览器没有 appSecret 就换不到任何票据
        AiApplicationDO application = applicationService.authenticate(appCode, appSecret);
        if (subjectType == null) {
            throw exception(AI_APPLICATION_CREDENTIAL_INVALID);
        }
        // 2) 可信主体断言 + 3) 范围解析（DENY 直接拒绝）
        AiSubjectScopeDTO scope = subjectService.resolveScope(
                application.getId(), subjectType, externalUserId, resourceHints == null ? List.of() : resourceHints);
        if (scope.isDenied()) {
            throw exception(AI_AUTHORIZATION_DENIED);
        }
        // 4) 裁剪：只保留本次请求的对象，越界对象被裁掉；裁剪后为空则拒绝签发
        Set<String> trimmedResources = trimResources(scope.getResourceKeys(), resourceHints);
        if (trimmedResources.isEmpty()
                && (!resourceHints.isEmpty())
                && scope.getOrganizationIds().isEmpty()) {
            throw exception(AI_AUTHORIZATION_DENIED);
        }
        String token = generateToken();
        LocalDateTime expiresTime = LocalDateTime.now().plus(ticketTtl);
        AiAccessTicketDO ticket = new AiAccessTicketDO()
                .setApplicationId(application.getId())
                .setSubjectType(subjectType.name())
                .setExternalUserId(scope.getExternalUserId() == null ? "" : scope.getExternalUserId())
                .setTokenDigest(digest(token))
                .setScopeSnapshot(JsonUtils.toJsonString(Map.of(
                        "organizationIds",
                        scope.getOrganizationIds(),
                        "resourceKeys",
                        trimmedResources,
                        "scopeSource",
                        scope.getScopeSource() == null ? "" : scope.getScopeSource(),
                        "scopeVersion",
                        scope.getScopeVersion())))
                .setScopeFingerprint(fingerprint(scope, trimmedResources))
                .setAuthzRevision(1L)
                .setExpiresTime(expiresTime)
                .setStatus(AiAccessTicketDO.STATUS_ACTIVE)
                .setVersion(0);
        ticketMapper.insert(ticket);
        return new AiTicketIssueDTO()
                .setApplicationId(application.getId())
                .setSubjectType(subjectType.name())
                .setExternalUserId(ticket.getExternalUserId())
                .setToken(token)
                .setExpiresTime(expiresTime)
                .setOrganizationIds(scope.getOrganizationIds())
                .setResourceKeys(trimmedResources)
                .setScopeSource(scope.getScopeSource())
                .setScopeFingerprint(ticket.getScopeFingerprint());
    }

    @Override
    public AiTicketContextDTO verify(String token) {
        if (!StringUtils.hasText(token)) {
            throw exception(AI_TICKET_INVALID);
        }
        AiAccessTicketDO ticket = ticketMapper.selectByDigest(digest(token));
        if (ticket == null
                || !AiAccessTicketDO.STATUS_ACTIVE.equals(ticket.getStatus())
                || ticket.getExpiresTime() == null
                || ticket.getExpiresTime().isBefore(LocalDateTime.now())) {
            // 不存在、已撤销、已过期共用同一语义，防止枚举
            throw exception(AI_TICKET_INVALID);
        }
        // 校验时重新读取应用与主体状态：撤销应用/主体后未过期票据同样失效（不使用缓存）
        AiApplicationDO application = applicationService.getApplication(ticket.getApplicationId());
        if (application == null || !Boolean.TRUE.equals(application.getEnabled())) {
            throw exception(AI_TICKET_INVALID);
        }
        if (subjectService
                .findActiveSubject(
                        ticket.getApplicationId(),
                        AiSubjectType.valueOf(ticket.getSubjectType()),
                        ticket.getExternalUserId())
                .isEmpty()) {
            throw exception(AI_TICKET_INVALID);
        }
        return new AiTicketContextDTO()
                .setTicketId(ticket.getId())
                .setApplicationId(ticket.getApplicationId())
                .setSubjectType(ticket.getSubjectType())
                .setExternalUserId(ticket.getExternalUserId())
                .setOrganizationIds(readOrganizations(ticket.getScopeSnapshot()))
                .setResourceKeys(readResources(ticket.getScopeSnapshot()))
                .setScopeFingerprint(ticket.getScopeFingerprint())
                .setAuthzRevision(ticket.getAuthzRevision())
                .setExpiresTime(ticket.getExpiresTime());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeTickets(Long applicationId, AiSubjectType subjectType, String externalUserId) {
        for (AiAccessTicketDO ticket : ticketMapper.selectActive(applicationId, subjectType.name(), externalUserId)) {
            ticketMapper.updateWithVersion(
                    new AiAccessTicketDO()
                            .setId(ticket.getId())
                            .setStatus(AiAccessTicketDO.STATUS_REVOKED)
                            .setVersion(ticket.getVersion() + 1),
                    ticket.getVersion());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeTicketsOfApplication(Long applicationId) {
        for (AiAccessTicketDO ticket : selectActiveTicketsOfApplication(applicationId)) {
            if (AiAccessTicketDO.STATUS_ACTIVE.equals(ticket.getStatus())) {
                ticketMapper.updateWithVersion(
                        new AiAccessTicketDO()
                                .setId(ticket.getId())
                                .setStatus(AiAccessTicketDO.STATUS_REVOKED)
                                .setVersion(ticket.getVersion() + 1),
                        ticket.getVersion());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cleanInvalidTickets(int batchSize, int maxBatches, Duration retention) {
        int size = Math.max(1, batchSize);
        int batches = Math.max(1, maxBatches);
        Duration keep = retention == null || retention.isNegative() ? Duration.ZERO : retention;
        LocalDateTime expiredBefore = LocalDateTime.now().minus(keep);
        int cleaned = 0;
        for (int batch = 0; batch < batches; batch++) {
            // 已撤销的、或过期超过保留期的票据；按 id 升序取一批
            List<AiAccessTicketDO> candidates = ticketMapper.selectList(
                    new com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX<AiAccessTicketDO>()
                            .and(wrapper -> wrapper.eq(AiAccessTicketDO::getStatus, AiAccessTicketDO.STATUS_REVOKED)
                                    .or()
                                    .lt(AiAccessTicketDO::getExpiresTime, expiredBefore))
                            .orderByAsc(AiAccessTicketDO::getId)
                            .last("LIMIT " + size));
            if (candidates.isEmpty()) {
                break;
            }
            for (AiAccessTicketDO ticket : candidates) {
                ticketMapper.deleteById(ticket.getId());
                cleaned++;
            }
            if (candidates.size() < size) {
                break;
            }
        }
        return cleaned;
    }

    /** 撤销应用时枚举该应用的可用票据（应用维度，不区分主体类型）。 */
    private List<AiAccessTicketDO> selectActiveTicketsOfApplication(Long applicationId) {
        return ticketMapper.selectList(
                new com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX<AiAccessTicketDO>()
                        .eq(AiAccessTicketDO::getApplicationId, applicationId)
                        .eq(AiAccessTicketDO::getStatus, AiAccessTicketDO.STATUS_ACTIVE)
                        .orderByAsc(AiAccessTicketDO::getId));
    }

    /** 裁剪：只保留同时出现在主体范围与请求里的对象；请求为空时保留主体范围的全部对象。 */
    private static Set<String> trimResources(Set<String> subjectResources, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return Set.copyOf(subjectResources);
        }
        Set<String> trimmed = new LinkedHashSet<>();
        for (String key : requested) {
            if (key != null && subjectResources.contains(key)) {
                trimmed.add(key);
            }
        }
        return trimmed;
    }

    private static String fingerprint(AiSubjectScopeDTO scope, Set<String> trimmedResources) {
        StringBuilder builder = new StringBuilder();
        scope.getOrganizationIds().stream()
                .sorted()
                .forEach(id -> builder.append("o:").append(id).append(';'));
        trimmedResources.stream()
                .sorted()
                .forEach(key -> builder.append("r:").append(key).append(';'));
        builder.append("sv:").append(scope.getScopeVersion());
        return digest(builder.toString());
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> readOrganizations(String snapshot) {
        Map<String, Object> parsed = JsonUtils.parseObject(snapshot, Map.class);
        if (parsed == null || parsed.get("organizationIds") == null) {
            return Set.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        for (Object value : (Iterable<Object>) parsed.get("organizationIds")) {
            result.add(Long.valueOf(String.valueOf(value)));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> readResources(String snapshot) {
        Map<String, Object> parsed = JsonUtils.parseObject(snapshot, Map.class);
        if (parsed == null || parsed.get("resourceKeys") == null) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object value : (Iterable<Object>) parsed.get("resourceKeys")) {
            result.add(String.valueOf(value));
        }
        return result;
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String digest(String value) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
