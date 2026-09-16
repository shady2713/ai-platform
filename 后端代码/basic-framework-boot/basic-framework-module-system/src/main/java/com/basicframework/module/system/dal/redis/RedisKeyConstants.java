package com.basicframework.module.system.dal.redis;

/**
 * System Redis Key 枚举类
 *
 */
public interface RedisKeyConstants {

    // 以下 Spring Cache 键统一带 #ttl 兜底过期时间（TimeoutRedisCacheManager 解析），
    // 正常由写操作 @CacheEvict 精确失效；TTL 只兜住漏失效的脏数据，不替代失效逻辑。

    /**
     * 字典数据列表的缓存（按状态与类型筛选）
     * <p>
     * KEY 格式：dict_data_list:{status}:{dictType}
     * VALUE 数据类型：String 字典数据数组
     * 兜底过期时间：30 分钟
     */
    String DICT_DATA_LIST = "dict_data_list#30m";

    /**
     * 指定类型的字典数据数组的缓存
     * <p>
     * KEY 格式：dict_data_list_by_type:{dictType}
     * VALUE 数据类型：String 字典数据数组
     * 兜底过期时间：30 分钟
     */
    String DICT_DATA_LIST_BY_TYPE = "dict_data_list_by_type#30m";

    /** 账号密码登录失败次数；动态过期时间，KEY 参数为用户编号。 */
    String LOGIN_FAILURES = "login_failures:%s";

    /** 账号密码登录锁定状态；动态过期时间，KEY 参数为用户编号。 */
    String LOGIN_LOCK = "login_lock:%s";

    /** 短信验证码校验失败次数；动态过期时间不超过验证码剩余有效期，KEY 参数为验证码记录编号。 */
    String SMS_CODE_VALIDATE_FAILURES = "sms_code_validate_failures:%d";

    /**
     * 站内信模版的缓存
     * <p>
     * KEY 格式：notify_template:{code}
     * VALUE 数据格式：String 模版信息
     */
    String NOTIFY_TEMPLATE = "notify_template";

    /**
     * 短信模版的缓存
     * <p>
     * KEY 格式：sms_template:{id}
     * VALUE 数据格式：String 模版信息
     */
    String SMS_TEMPLATE = "sms_template";
}
