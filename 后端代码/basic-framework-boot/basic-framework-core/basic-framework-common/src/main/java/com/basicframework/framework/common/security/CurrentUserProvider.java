package com.basicframework.framework.common.security;

import com.basicframework.framework.common.enums.UserTypeEnum;
import org.springframework.lang.Nullable;

/**
 * "当前登录用户身份"能力接缝
 *
 * 由 basic-framework-spring-boot-starter-security 注册实现 Bean，数据来源于
 * Spring Security 上下文中的登录用户。可选消费者可经 ObjectProvider 注入并在缺省时回退为
 * "无登录用户"语义；登记了受保护表的数据权限 starter 必须直接依赖该能力，缺失时启动失败。
 *
 */
public interface CurrentUserProvider {

    /**
     * 获得当前登录用户的编号
     *
     * @return 用户编号；无登录用户时返回 null
     */
    @Nullable
    Long getLoginUserId();

    /**
     * 获得当前登录用户的类型
     *
     * @return 用户类型，关联 {@link UserTypeEnum}；无登录用户时返回 null
     */
    @Nullable
    Integer getLoginUserType();

    /**
     * 获得当前登录用户请求级上下文中的缓存值
     *
     * @param key 缓存键
     * @param type 缓存值类型
     * @param <T> 缓存值类型
     * @return 缓存值；无登录用户或缓存不存在时返回 null
     */
    @Nullable
    <T> T getContext(String key, Class<T> type);

    /**
     * 写入当前登录用户请求级上下文，用于基于登录用户维度的临时缓存
     *
     * @param key 缓存键
     * @param value 缓存值；无登录用户时忽略本次写入
     */
    void setContext(String key, Object value);
}
