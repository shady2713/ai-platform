package com.basicframework.framework.security.core.service;

import com.basicframework.framework.common.security.CurrentUserProvider;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;

/**
 * {@link CurrentUserProvider} 的实现，委托 {@link SecurityFrameworkUtils}，
 * 数据来源于 Spring Security 上下文中的 {@link LoginUser}
 *
 */
public class CurrentUserProviderImpl implements CurrentUserProvider {

    @Override
    public Long getLoginUserId() {
        return SecurityFrameworkUtils.getLoginUserId();
    }

    @Override
    public Integer getLoginUserType() {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        return loginUser != null ? loginUser.getUserType() : null;
    }

    @Override
    public <T> T getContext(String key, Class<T> type) {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        return loginUser != null ? loginUser.getContext(key, type) : null;
    }

    @Override
    public void setContext(String key, Object value) {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser != null) {
            loginUser.setContext(key, value);
        }
    }
}
