package com.basicframework.module.system.framework.sms.core.client;

import com.basicframework.module.system.framework.sms.core.property.SmsChannelProperties;

/**
 * 短信客户端的工厂接口
 *
 * @since 2021/1/28 14:01
 */
public interface SmsClientFactory {

    /**
     * 获得短信 Client
     *
     * @param channelId 渠道编号
     * @return 短信 Client
     */
    SmsClient getSmsClient(Long channelId);

    /**
     * 获得短信 Client
     *
     * @param channelCode 渠道编码
     * @return 短信 Client
     */
    SmsClient getSmsClient(String channelCode);

    /**
     * 创建短信 Client
     *
     * @param properties 配置对象
     * @return 短信 Client
     */
    SmsClient createOrUpdateSmsClient(SmsChannelProperties properties);

    /**
     * 按指定配置创建不注册到共享缓存的临时短信 Client。
     *
     * @param properties 渠道配置
     * @return 临时短信 Client
     */
    SmsClient createTransientSmsClient(SmsChannelProperties properties);

    /**
     * 移除指定渠道的短信 Client。
     *
     * @param channelId 渠道编号
     */
    void removeSmsClient(Long channelId);
}
