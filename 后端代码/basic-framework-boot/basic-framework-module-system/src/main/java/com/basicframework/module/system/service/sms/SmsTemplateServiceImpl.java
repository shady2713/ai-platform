package com.basicframework.module.system.service.sms;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.util.exception.SafeExceptionLogUtils.format;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;

import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.sms.SmsChannelDO;
import com.basicframework.module.system.dal.dataobject.sms.SmsTemplateDO;
import com.basicframework.module.system.dal.mysql.sms.SmsChannelMapper;
import com.basicframework.module.system.dal.mysql.sms.SmsTemplateMapper;
import com.basicframework.module.system.dal.mysql.sms.SmsTemplateQuery;
import com.basicframework.module.system.dal.redis.RedisKeyConstants;
import com.basicframework.module.system.framework.sms.core.client.SmsClient;
import com.basicframework.module.system.framework.sms.core.client.dto.SmsTemplateRespDTO;
import com.basicframework.module.system.framework.sms.core.enums.SmsTemplateAuditStatusEnum;
import com.basicframework.module.system.util.TemplateUtils;
import com.google.common.annotations.VisibleForTesting;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 短信模板 Service 实现类
 *
 * @since 2021/1/25 9:25
 */
@Slf4j
@Service
public class SmsTemplateServiceImpl implements SmsTemplateService {

    private final SmsTemplateMapper smsTemplateMapper;

    private final SmsChannelMapper smsChannelMapper;

    private final SmsChannelService smsChannelService;

    private final TransactionTemplate transactionTemplate;

    public SmsTemplateServiceImpl(
            SmsTemplateMapper smsTemplateMapper,
            SmsChannelMapper smsChannelMapper,
            SmsChannelService smsChannelService,
            TransactionTemplate transactionTemplate) {
        this.smsTemplateMapper = smsTemplateMapper;
        this.smsChannelMapper = smsChannelMapper;
        this.smsChannelService = smsChannelService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Long createSmsTemplate(SmsTemplateDO template) {
        // 远程平台校验不放入数据库事务，避免持有渠道行锁等待外部网络。
        SmsChannelDO validatedChannel = validateSmsChannel(template.getChannelId());
        validateApiTemplate(validatedChannel, template.getApiTemplateId());
        template.setParams(parseTemplateContentParams(template.getContent()));
        return transactionTemplate.execute(status -> {
            SmsChannelDO lockedChannel = validateSmsChannelForReferenceWrite(validatedChannel);
            validateSmsTemplateCodeDuplicate(null, template.getCode());
            template.setChannelCode(lockedChannel.getCode());
            smsTemplateMapper.insert(template);
            return template.getId();
        });
    }

    @Override
    @CacheEvict(
            cacheNames = RedisKeyConstants.SMS_TEMPLATE,
            allEntries = true) // allEntries 清空所有缓存，因为可能修改到 code 字段，不好清理
    public void updateSmsTemplate(SmsTemplateDO updateObj) {
        // 远程平台校验不放入数据库事务，避免持有渠道行锁等待外部网络。
        SmsChannelDO validatedChannel = validateSmsChannel(updateObj.getChannelId());
        validateApiTemplate(validatedChannel, updateObj.getApiTemplateId());
        updateObj.setParams(parseTemplateContentParams(updateObj.getContent()));
        transactionTemplate.executeWithoutResult(status -> {
            validateAndLockSmsTemplate(updateObj.getId());
            SmsChannelDO lockedChannel = validateSmsChannelForReferenceWrite(validatedChannel);
            validateSmsTemplateCodeDuplicate(updateObj.getId(), updateObj.getCode());
            updateObj.setChannelCode(lockedChannel.getCode());
            smsTemplateMapper.updateById(updateObj);
        });
    }

    @Override
    @CacheEvict(
            cacheNames = RedisKeyConstants.SMS_TEMPLATE,
            allEntries = true) // allEntries 清空所有缓存，因为 id 不是直接的缓存 code，不好清理
    @Transactional(rollbackFor = Exception.class)
    public void deleteSmsTemplate(Long id) {
        // 校验存在
        validateAndLockSmsTemplate(id);
        // 更新
        smsTemplateMapper.deleteById(id);
    }

    @Override
    @CacheEvict(
            cacheNames = RedisKeyConstants.SMS_TEMPLATE,
            allEntries = true) // allEntries 清空所有缓存，因为 id 不是直接的缓存 code，不好清理
    @Transactional(rollbackFor = Exception.class)
    public void deleteSmsTemplateList(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw exception(SMS_TEMPLATE_NOT_EXISTS);
        }
        List<Long> templateIds = ids.stream().distinct().sorted().toList();
        templateIds.forEach(this::validateAndLockSmsTemplate);
        smsTemplateMapper.deleteByIds(templateIds);
    }

    @Override
    public SmsTemplateDO getSmsTemplate(Long id) {
        return smsTemplateMapper.selectById(id);
    }

    @Override
    @Cacheable(cacheNames = RedisKeyConstants.SMS_TEMPLATE, key = "#code", unless = "#result == null")
    public SmsTemplateDO getSmsTemplateByCodeFromCache(String code) {
        return smsTemplateMapper.selectByCode(code);
    }

    @Override
    public PageResult<SmsTemplateDO> getSmsTemplatePage(PageParam pageParam, SmsTemplateQuery query) {
        return smsTemplateMapper.selectPage(pageParam, query);
    }

    @Override
    public Long getSmsTemplateCountByChannelId(Long channelId) {
        return smsTemplateMapper.selectCountByChannelId(channelId);
    }

    @VisibleForTesting
    public SmsChannelDO validateSmsChannel(Long channelId) {
        SmsChannelDO channelDO = smsChannelService.getSmsChannel(channelId);
        if (channelDO == null) {
            throw exception(SMS_CHANNEL_NOT_EXISTS);
        }
        if (CommonStatusEnum.isDisable(channelDO.getStatus())) {
            throw exception(SMS_CHANNEL_DISABLE);
        }
        return channelDO;
    }

    private SmsChannelDO validateSmsChannelForReferenceWrite(SmsChannelDO validatedChannel) {
        SmsChannelDO lockedChannel = smsChannelMapper.selectByIdForShare(validatedChannel.getId());
        if (lockedChannel == null) {
            throw exception(SMS_CHANNEL_NOT_EXISTS);
        }
        if (CommonStatusEnum.isDisable(lockedChannel.getStatus())) {
            throw exception(SMS_CHANNEL_DISABLE);
        }
        if (!hasSameRemoteConfiguration(validatedChannel, lockedChannel)) {
            throw exception(SMS_CHANNEL_CHANGED);
        }
        return lockedChannel;
    }

    private void validateAndLockSmsTemplate(Long id) {
        if (id == null || smsTemplateMapper.selectByIdForUpdate(id) == null) {
            throw exception(SMS_TEMPLATE_NOT_EXISTS);
        }
    }

    private static boolean hasSameRemoteConfiguration(SmsChannelDO expected, SmsChannelDO actual) {
        return Objects.equals(expected.getCode(), actual.getCode())
                && Objects.equals(expected.getSignature(), actual.getSignature())
                && Objects.equals(expected.getApiKeyCiphertext(), actual.getApiKeyCiphertext())
                && Objects.equals(expected.getApiSecretCiphertext(), actual.getApiSecretCiphertext())
                && Objects.equals(expected.getCallbackUrl(), actual.getCallbackUrl());
    }

    @VisibleForTesting
    public void validateSmsTemplateCodeDuplicate(Long id, String code) {
        SmsTemplateDO template = smsTemplateMapper.selectByCode(code);
        if (template == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的字典类型
        if (id == null) {
            throw exception(SMS_TEMPLATE_CODE_DUPLICATE, code);
        }
        if (!template.getId().equals(id)) {
            throw exception(SMS_TEMPLATE_CODE_DUPLICATE, code);
        }
    }

    /**
     * 校验 API 短信平台的模板是否有效
     *
     * @param channel 渠道配置快照
     * @param apiTemplateId API 模板编号
     */
    @VisibleForTesting
    void validateApiTemplate(SmsChannelDO channel, String apiTemplateId) {
        // 临时客户端不写共享缓存，最终事务可安全复核本次远程校验使用的精确配置快照。
        SmsClient smsClient = smsChannelService.createTransientSmsClient(channel);
        SmsTemplateRespDTO template;
        try {
            template = smsClient.getSmsTemplate(apiTemplateId);
        } catch (Exception ex) {
            // 远程校验失败时向调用方隐藏平台细节，但必须留下根因日志供排障
            log.error("校验短信模板({}) 的 API 模板({}) 失败：{}", channel.getId(), apiTemplateId, format(ex));
            throw exception(SMS_TEMPLATE_API_ERROR);
        }
        // 校验短信模版
        if (template == null) {
            throw exception(SMS_TEMPLATE_API_NOT_EXISTS);
        }
        if (Objects.equals(template.getAuditStatus(), SmsTemplateAuditStatusEnum.CHECKING.getStatus())) {
            throw exception(SMS_TEMPLATE_API_AUDIT_CHECKING);
        }
        if (Objects.equals(template.getAuditStatus(), SmsTemplateAuditStatusEnum.FAIL.getStatus())) {
            throw exception(SMS_TEMPLATE_API_AUDIT_FAIL, template.getAuditReason());
        }
        if (!Objects.equals(template.getAuditStatus(), SmsTemplateAuditStatusEnum.SUCCESS.getStatus())) {
            throw new IllegalArgumentException(
                    String.format("短信模板(%s) 审核状态(%d) 不正确", apiTemplateId, template.getAuditStatus()));
        }
    }

    @Override
    public String formatSmsTemplateContent(String content, Map<String, Object> params) {
        return StrUtil.format(content, params);
    }

    @VisibleForTesting
    List<String> parseTemplateContentParams(String content) {
        return TemplateUtils.parseContentParams(content);
    }
}
