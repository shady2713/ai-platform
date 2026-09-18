package com.basicframework.module.ai.framework.security;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.auth.dto.AiTicketContextDTO;
import com.basicframework.module.system.api.session.UserSessionCommonApi;
import com.basicframework.module.system.api.session.dto.UserSessionCheckRespDTO;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AI 应用端会话校验 Provider（A05）：把 A04 的访问票据接入框架的 MEMBER 用户类型。
 *
 * <p>职责与边界：
 * <ul>
 *   <li>只声明 {@link UserTypeEnum#MEMBER}，与 module-system 的 ADMIN Provider 互不重叠；
 *       同一用户类型出现第二个 Provider 时框架在启动期直接失败（禁止静默覆盖）；</li>
 *   <li>票据校验每次都重新读取应用与主体状态（A04 的语义），因此撤销后旧票据立即认证失败，
 *       不存在"身份降级到 ADMIN"或复用旧会话的路径；</li>
 *   <li>用户编号使用票据编号（平台内唯一、短期），**外部主体身份**通过
 *       {@link UserSessionCheckRespDTO#getUserInfo()} 传递，绝不映射为系统用户编号。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AiUserSessionCommonApi implements UserSessionCommonApi {

    /** 会话附加信息键：应用编号。 */
    public static final String INFO_KEY_APPLICATION_ID = "aiApplicationId";

    /** 会话附加信息键：主体类型（APP/USER）。 */
    public static final String INFO_KEY_SUBJECT_TYPE = "aiSubjectType";

    /** 会话附加信息键：可信外部用户标识。 */
    public static final String INFO_KEY_EXTERNAL_USER_ID = "aiExternalUserId";

    /** 会话附加信息键：范围指纹（范围收窄后可据此重新鉴权）。 */
    public static final String INFO_KEY_SCOPE_FINGERPRINT = "aiScopeFingerprint";

    private final AiTicketService ticketService;

    @Override
    public Integer getSupportedUserType() {
        return UserTypeEnum.MEMBER.getValue();
    }

    @Override
    public UserSessionCheckRespDTO checkAccessToken(String accessToken) {
        AiTicketContextDTO ticket = ticketService.verify(accessToken);
        Map<String, String> info = new LinkedHashMap<>();
        info.put(INFO_KEY_APPLICATION_ID, String.valueOf(ticket.getApplicationId()));
        info.put(INFO_KEY_SUBJECT_TYPE, ticket.getSubjectType());
        info.put(INFO_KEY_EXTERNAL_USER_ID, ticket.getExternalUserId());
        if (ticket.getScopeFingerprint() != null) {
            info.put(INFO_KEY_SCOPE_FINGERPRINT, ticket.getScopeFingerprint());
        }
        return new UserSessionCheckRespDTO()
                .setUserId(ticket.getTicketId())
                .setUserType(UserTypeEnum.MEMBER.getValue())
                .setUserInfo(info)
                .setAccessExpiresTime(ticket.getExpiresTime());
    }
}
