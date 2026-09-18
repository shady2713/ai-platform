package com.basicframework.module.ai.adapter.file;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.ai.dal.dataobject.token.AiAccessTicketDO;
import com.basicframework.module.ai.dal.mysql.token.AiAccessTicketMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.service.file.dto.AiFileSubject;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * AI 文件主体解析（A07）：从**服务端会话**得到可信身份（应用 + 主体类型 + 外部用户标识）。
 *
 * <p>两条来源：
 * <ol>
 *   <li>当前请求的 MEMBER 登录用户（A05 写入的会话附加信息）——控制器路径使用；</li>
 *   <li>infra 业务授权 SPI 传入的 {@code subject(userType=MEMBER, userId=票据编号)}——
 *       由票据回查应用与主体（票据必须 ACTIVE 且未过期，否则视为不可信）。</li>
 * </ol>
 * 解析失败一律返回空，调用方必须按"拒绝"处理（无权限与不存在同语义）。
 */
@Component
@RequiredArgsConstructor
public class AiFileSubjectResolver {

    private final AiAccessTicketMapper ticketMapper;

    /** 从当前登录用户解析（无会话信息时返回空）。 */
    public Optional<AiFileSubject> resolveCurrent() {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser == null
                || !UserTypeEnum.MEMBER.getValue().equals(loginUser.getUserType())
                || loginUser.getInfo() == null) {
            return Optional.empty();
        }
        return build(
                loginUser.getInfo().get(AiUserSessionCommonApi.INFO_KEY_APPLICATION_ID),
                loginUser.getInfo().get(AiUserSessionCommonApi.INFO_KEY_SUBJECT_TYPE),
                loginUser.getInfo().get(AiUserSessionCommonApi.INFO_KEY_EXTERNAL_USER_ID),
                loginUser.getId());
    }

    /** 由 infra 传入的主体（票据编号）回查：票据必须有效。 */
    public Optional<AiFileSubject> resolveByTicket(Long ticketId) {
        if (ticketId == null) {
            return Optional.empty();
        }
        AiAccessTicketDO ticket = ticketMapper.selectById(ticketId);
        if (ticket == null
                || !AiAccessTicketDO.STATUS_ACTIVE.equals(ticket.getStatus())
                || ticket.getExpiresTime() == null
                || ticket.getExpiresTime().isBefore(LocalDateTime.now())) {
            return Optional.empty();
        }
        return build(
                String.valueOf(ticket.getApplicationId()),
                ticket.getSubjectType(),
                ticket.getExternalUserId(),
                ticket.getId());
    }

    private static Optional<AiFileSubject> build(
            String applicationId, String subjectType, String externalUserId, Long ticketId) {
        if (applicationId == null || subjectType == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new AiFileSubject(
                    Long.valueOf(applicationId),
                    AiSubjectType.valueOf(subjectType),
                    externalUserId == null ? "" : externalUserId,
                    ticketId));
        } catch (IllegalArgumentException exception) {
            // 编号或主体类型非法：视为不可信
            return Optional.empty();
        }
    }
}
