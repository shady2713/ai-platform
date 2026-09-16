package com.basicframework.module.system.api.session;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.module.system.api.session.dto.UserSessionCheckRespDTO;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.service.session.UserSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 用户会话校验契约实现。 */
@Service
@RequiredArgsConstructor
public class UserSessionApiImpl implements UserSessionCommonApi {

    private final UserSessionService userSessionService;

    @Override
    public Integer getSupportedUserType() {
        return UserTypeEnum.ADMIN.getValue();
    }

    @Override
    public UserSessionCheckRespDTO checkAccessToken(String accessToken) {
        UserSessionDO session = userSessionService.checkAccessToken(accessToken);
        return BeanUtils.toBean(session, UserSessionCheckRespDTO.class);
    }
}
