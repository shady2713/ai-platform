package com.basicframework.module.ai.service.conversation;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 会话主体解析（O01）：从**服务端会话**得到可信身份（应用 + 主体类型 + 外部用户标识）。
 *
 * <p>与文件主体解析同源：身份来自 A05 写入 MEMBER 会话的附加信息，**不接受请求参数直接构造**。
 * 解析失败（非 MEMBER、缺少会话信息、编号或类型非法）一律返回空，调用方必须按"拒绝"处理——
 * 无权限与不存在同语义，客户端无法用编号枚举他人会话。
 */
@Component
@RequiredArgsConstructor
public class AiConversationSubjectResolver {

    /** 从当前登录用户解析（无会话信息时返回空）。 */
    public Optional<AiConversationSubject> resolveCurrent() {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser == null
                || !UserTypeEnum.MEMBER.getValue().equals(loginUser.getUserType())
                || loginUser.getInfo() == null) {
            return Optional.empty();
        }
        return build(
                loginUser.getInfo().get(AiUserSessionCommonApi.INFO_KEY_APPLICATION_ID),
                loginUser.getInfo().get(AiUserSessionCommonApi.INFO_KEY_SUBJECT_TYPE),
                loginUser.getInfo().get(AiUserSessionCommonApi.INFO_KEY_EXTERNAL_USER_ID));
    }

    private static Optional<AiConversationSubject> build(
            String applicationId, String subjectType, String externalUserId) {
        if (applicationId == null || subjectType == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new AiConversationSubject(
                    Long.valueOf(applicationId),
                    AiSubjectType.valueOf(subjectType),
                    externalUserId == null ? "" : externalUserId));
        } catch (IllegalArgumentException exception) {
            // 编号或主体类型非法：视为不可信
            return Optional.empty();
        }
    }
}
