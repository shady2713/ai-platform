package com.basicframework.module.infra.service.file;

import com.basicframework.framework.common.enums.UserTypeEnum;

/** 上传文件的已认证业务主体。 */
public record FileUploadPrincipal(Long userId, Integer userType) {

    public boolean isAuthenticatedApplicationUser() {
        return userId != null
                && userId > 0
                && userType != null
                && UserTypeEnum.valueOf(userType) != null
                && !UserTypeEnum.SYSTEM.getValue().equals(userType);
    }
}
