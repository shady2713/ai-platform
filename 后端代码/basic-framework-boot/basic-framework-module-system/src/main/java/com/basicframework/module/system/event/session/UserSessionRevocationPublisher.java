package com.basicframework.module.system.event.session;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** 发布用户会话撤销事件，隔离业务服务与会话服务。 */
@Component
@RequiredArgsConstructor
public class UserSessionRevocationPublisher {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 撤销一个管理端用户的全部会话。
     *
     * @param userId 用户编号
     * @param reason 撤销原因
     */
    public void revokeAdminSession(Long userId, UserSessionRevocationReasonEnum reason) {
        revokeAdminSessions(Set.of(userId), reason);
    }

    /**
     * 撤销多个管理端用户的全部会话。
     *
     * @param userIds 用户编号集合
     * @param reason 撤销原因
     */
    public void revokeAdminSessions(Collection<Long> userIds, UserSessionRevocationReasonEnum reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        Set<Long> normalizedUserIds = new LinkedHashSet<>();
        userIds.stream().filter(Objects::nonNull).forEach(normalizedUserIds::add);
        if (normalizedUserIds.isEmpty()) {
            return;
        }
        eventPublisher.publishEvent(
                new UserSessionRevocationEvent(Set.copyOf(normalizedUserIds), UserTypeEnum.ADMIN.getValue(), reason));
    }
}
