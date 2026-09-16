package com.basicframework.module.infra.service.file;

import java.util.Objects;

/** 文件读取请求的已认证主体与管理权限快照。 */
public record FileAccessPrincipal(Long userId, Integer userType, boolean canManageFiles) {

    public boolean owns(Long ownerUserId, Integer ownerUserType) {
        return userId != null
                && userType != null
                && ownerUserId != null
                && ownerUserType != null
                && Objects.equals(userId, ownerUserId)
                && Objects.equals(userType, ownerUserType);
    }
}
