package com.basicframework.module.system.service.sms;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;
import static com.basicframework.module.system.enums.ErrorCodeConstants.SMS_CHANNEL_CODE_DUPLICATE;
import static com.basicframework.module.system.enums.ErrorCodeConstants.SMS_CHANNEL_HAS_CHILDREN;
import static com.basicframework.module.system.enums.ErrorCodeConstants.SMS_CHANNEL_NOT_EXISTS;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.system.dal.dataobject.sms.SmsChannelDO;
import com.basicframework.module.system.dal.mysql.sms.SmsChannelMapper;
import com.basicframework.module.system.framework.sms.core.client.SmsClient;
import com.basicframework.module.system.framework.sms.core.client.SmsClientFactory;
import com.basicframework.module.system.framework.sms.core.enums.SmsChannelEnum;
import com.basicframework.module.system.framework.sms.core.property.SmsChannelProperties;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * 短信渠道 Service 实现类
 *
 */
@Service
public class SmsChannelServiceImpl implements SmsChannelService {

    private static final String API_KEY_CONTEXT = "sms-channel:api-key";
    private static final String API_SECRET_CONTEXT = "sms-channel:api-secret";

    private final SmsClientFactory smsClientFactory;

    private final SmsChannelMapper smsChannelMapper;

    private final ObjectProvider<SmsTemplateService> smsTemplateServiceProvider;

    private final CredentialCipher credentialCipher;

    public SmsChannelServiceImpl(
            SmsClientFactory smsClientFactory,
            SmsChannelMapper smsChannelMapper,
            ObjectProvider<SmsTemplateService> smsTemplateServiceProvider,
            CredentialCipher credentialCipher) {
        this.smsClientFactory = smsClientFactory;
        this.smsChannelMapper = smsChannelMapper;
        this.smsTemplateServiceProvider = smsTemplateServiceProvider;
        this.credentialCipher = credentialCipher;
    }

    @Override
    public Long createSmsChannel(SmsChannelDO channel) {
        // 校验渠道编码唯一
        validateSmsChannelCodeUnique(null, channel.getCode());

        channel.setApiKeyCiphertext(encryptRequiredApiKey(channel.getApiKey()))
                .setApiKey(null)
                .setApiSecretCiphertext(encryptRequiredSecret(channel.getApiSecret()))
                .setApiSecret(null);
        smsChannelMapper.insert(channel);
        return channel.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSmsChannel(SmsChannelDO updateObj) {
        // 校验存在
        SmsChannelDO existing = validateAndLockSmsChannel(updateObj.getId());
        // 校验渠道编码唯一
        validateSmsChannelCodeUnique(updateObj.getId(), updateObj.getCode());

        if (!StringUtils.hasText(updateObj.getApiKey())) {
            updateObj.setApiKeyCiphertext(normalizeStoredApiKey(existing.getApiKeyCiphertext()));
        } else {
            updateObj.setApiKeyCiphertext(encryptRequiredApiKey(updateObj.getApiKey()));
        }
        updateObj.setApiKey(null);
        if (StringUtils.hasText(updateObj.getApiSecret())) {
            updateObj.setApiSecretCiphertext(credentialCipher.encrypt(updateObj.getApiSecret(), API_SECRET_CONTEXT));
        } else {
            updateObj.setApiSecretCiphertext(existing.getApiSecretCiphertext());
        }
        updateObj.setApiSecret(null);
        smsChannelMapper.updateById(updateObj);
        removeSmsClientsAfterCommit(List.of(updateObj.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSmsChannel(Long id) {
        // 校验存在
        validateAndLockSmsChannel(id);
        // 校验是否有在使用该账号的模版
        if (getSmsTemplateService().getSmsTemplateCountByChannelId(id) > 0) {
            throw exception(SMS_CHANNEL_HAS_CHILDREN);
        }
        // 删除
        smsChannelMapper.deleteById(id);
        removeSmsClientsAfterCommit(List.of(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSmsChannelList(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw exception(SMS_CHANNEL_NOT_EXISTS);
        }
        List<Long> channelIds = ids.stream().distinct().sorted().toList();
        channelIds.forEach(this::validateAndLockSmsChannel);
        // 1. 校验是否有在使用该账号的模版
        channelIds.forEach(id -> {
            if (getSmsTemplateService().getSmsTemplateCountByChannelId(id) > 0) {
                throw exception(SMS_CHANNEL_HAS_CHILDREN);
            }
        });

        // 2. 批量删除
        smsChannelMapper.deleteByIds(channelIds);
        removeSmsClientsAfterCommit(channelIds);
    }

    private SmsTemplateService getSmsTemplateService() {
        return smsTemplateServiceProvider.getObject();
    }

    private SmsChannelDO validateAndLockSmsChannel(Long id) {
        SmsChannelDO channel = id == null ? null : smsChannelMapper.selectByIdForUpdate(id);
        if (channel == null) {
            throw exception(SMS_CHANNEL_NOT_EXISTS);
        }
        return channel;
    }

    private void removeSmsClientsAfterCommit(List<Long> channelIds) {
        Runnable cleanup = () -> channelIds.forEach(smsClientFactory::removeSmsClient);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanup.run();
            }
        });
    }

    private void validateSmsChannelCodeUnique(Long id, String code) {
        SmsChannelDO channel = smsChannelMapper.selectByCode(code);
        if (channel == null) {
            return;
        }
        SmsChannelEnum channelEnum = SmsChannelEnum.getByCode(code);
        String channelName = channelEnum != null ? channelEnum.getName() : code;
        if (id == null) {
            throw exception(SMS_CHANNEL_CODE_DUPLICATE, channelName);
        }
        if (!channel.getId().equals(id)) {
            throw exception(SMS_CHANNEL_CODE_DUPLICATE, channelName);
        }
    }

    @Override
    public SmsChannelDO getSmsChannel(Long id) {
        return smsChannelMapper.selectById(id);
    }

    @Override
    public List<SmsChannelDO> getSmsChannelList() {
        return smsChannelMapper.selectList();
    }

    @Override
    public PageResult<SmsChannelDO> getSmsChannelPage(
            PageParam pageParam, String signature, String code, Integer status, LocalDateTime[] createTime) {
        return smsChannelMapper.selectPage(pageParam, signature, code, status, createTime);
    }

    @Override
    public SmsClient getSmsClient(Long id) {
        SmsChannelDO channel = smsChannelMapper.selectById(id);
        if (channel == null) {
            return null;
        }
        SmsChannelProperties properties = BeanUtils.toBean(channel, SmsChannelProperties.class);
        properties.setApiKey(decryptStoredApiKey(channel));
        properties.setApiSecret(decryptStoredSecret(channel));
        return smsClientFactory.createOrUpdateSmsClient(properties);
    }

    @Override
    public SmsClient createTransientSmsClient(SmsChannelDO channel) {
        SmsChannelProperties properties = BeanUtils.toBean(channel, SmsChannelProperties.class);
        if (!StringUtils.hasText(properties.getApiKey()) && StringUtils.hasText(channel.getApiKeyCiphertext())) {
            properties.setApiKey(decryptStoredApiKey(channel));
        }
        if (!StringUtils.hasText(properties.getApiSecret()) && StringUtils.hasText(channel.getApiSecretCiphertext())) {
            properties.setApiSecret(decryptStoredSecret(channel));
        }
        return smsClientFactory.createTransientSmsClient(properties);
    }

    @Override
    public SmsClient getSmsClient(String code) {
        return smsClientFactory.getSmsClient(code);
    }

    private String encryptRequiredSecret(String apiSecret) {
        if (!StringUtils.hasText(apiSecret)) {
            throw invalidParamException("短信 API Secret 不能为空");
        }
        return credentialCipher.encrypt(apiSecret, API_SECRET_CONTEXT);
    }

    private String encryptRequiredApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            throw invalidParamException("短信 API Key 不能为空");
        }
        return credentialCipher.encrypt(apiKey, API_KEY_CONTEXT);
    }

    private String normalizeStoredApiKey(String storedValue) {
        if (!StringUtils.hasText(storedValue)) {
            throw new IllegalStateException("短信渠道未配置 API Key");
        }
        return credentialCipher.isEncryptedValue(storedValue)
                ? storedValue
                : credentialCipher.encrypt(storedValue, API_KEY_CONTEXT);
    }

    private String decryptStoredApiKey(SmsChannelDO channel) {
        String storedValue = channel.getApiKeyCiphertext();
        if (!StringUtils.hasText(storedValue)) {
            throw new IllegalStateException("短信渠道未配置 API Key");
        }
        if (credentialCipher.isEncryptedValue(storedValue)) {
            return credentialCipher.decrypt(storedValue, API_KEY_CONTEXT);
        }
        String ciphertext = credentialCipher.encrypt(storedValue, API_KEY_CONTEXT);
        if (channel.getId() != null && smsChannelMapper.replaceLegacyApiKey(channel.getId(), storedValue, ciphertext)) {
            channel.setApiKeyCiphertext(ciphertext);
        }
        return storedValue;
    }

    private String decryptStoredSecret(SmsChannelDO channel) {
        if (!StringUtils.hasText(channel.getApiSecretCiphertext())) {
            throw new IllegalStateException("短信渠道未配置 API Secret");
        }
        return credentialCipher.decrypt(channel.getApiSecretCiphertext(), API_SECRET_CONTEXT);
    }
}
