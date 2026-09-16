package com.basicframework.module.system.framework.sms.core.client.impl;

import com.basicframework.module.system.framework.sms.core.client.SmsClient;
import com.basicframework.module.system.framework.sms.core.client.SmsClientFactory;
import com.basicframework.module.system.framework.sms.core.enums.SmsChannelEnum;
import com.basicframework.module.system.framework.sms.core.property.SmsChannelProperties;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;

/**
 * 短信客户端工厂接口
 *
 */
@Validated
@Slf4j
public class SmsClientFactoryImpl implements SmsClientFactory {

    /**
     * 短信客户端 Map
     * key：渠道编号，使用 {@link SmsChannelProperties#getId()}
     */
    private final ConcurrentMap<Long, AbstractSmsClient> channelIdClients = new ConcurrentHashMap<>();

    /**
     * 短信客户端 Map
     * key：渠道编码，使用 {@link SmsChannelProperties#getCode()} ()}
     *
     * 注意，一些场景下，需要获得某个渠道类型的客户端，所以需要使用它。
     * 例如说，解析短信接收结果，是相对通用的，不需要使用某个渠道编号的 {@link #channelIdClients}
     */
    private final ConcurrentMap<String, AbstractSmsClient> channelCodeClients = new ConcurrentHashMap<>();

    public SmsClientFactoryImpl() {
        // 初始化 channelCodeClients 集合
        Arrays.stream(SmsChannelEnum.values()).forEach(channel -> {
            // 创建一个空的 SmsChannelProperties 对象
            SmsChannelProperties properties = new SmsChannelProperties()
                    .setCode(channel.getCode())
                    .setApiKey("default default")
                    .setApiSecret("default");
            // 创建 Sms 客户端
            AbstractSmsClient smsClient = createSmsClient(properties);
            channelCodeClients.put(channel.getCode(), smsClient);
        });
    }

    @Override
    public SmsClient getSmsClient(Long channelId) {
        return channelIdClients.get(channelId);
    }

    @Override
    public SmsClient getSmsClient(String channelCode) {
        return channelCodeClients.get(channelCode);
    }

    @Override
    public SmsClient createOrUpdateSmsClient(SmsChannelProperties properties) {
        return channelIdClients.compute(properties.getId(), (channelId, existingClient) -> {
            // 渠道类型变更时必须替换实现，不能在旧供应商客户端上只刷新配置。
            if (existingClient == null || !Objects.equals(existingClient.properties.getCode(), properties.getCode())) {
                return initializeSmsClient(properties);
            }
            existingClient.refresh(properties);
            return existingClient;
        });
    }

    @Override
    public SmsClient createTransientSmsClient(SmsChannelProperties properties) {
        return initializeSmsClient(properties);
    }

    @Override
    public void removeSmsClient(Long channelId) {
        channelIdClients.remove(channelId);
    }

    private AbstractSmsClient initializeSmsClient(SmsChannelProperties properties) {
        AbstractSmsClient client = createSmsClient(properties);
        client.init();
        return client;
    }

    private AbstractSmsClient createSmsClient(SmsChannelProperties properties) {
        SmsChannelEnum channelEnum = SmsChannelEnum.getByCode(properties.getCode());
        Assert.notNull(channelEnum, String.format("渠道类型(%s) 为空", channelEnum));
        // 创建客户端
        switch (channelEnum) {
            case ALIYUN:
                return new AliyunSmsClient(properties);
        }
        // 创建失败，错误日志 + 抛出异常
        String configSummary = summarizeProperties(properties);
        log.error("[createSmsClient][配置摘要({}) 找不到合适的客户端实现]", configSummary);
        throw new IllegalArgumentException(String.format("配置摘要(%s) 找不到合适的客户端实现", configSummary));
    }

    private String summarizeProperties(SmsChannelProperties properties) {
        if (properties == null) {
            return "null";
        }
        return String.format("id(%s) code(%s)", properties.getId(), properties.getCode());
    }
}
