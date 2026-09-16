package com.basicframework.module.system.service.sms;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.util.exception.SafeExceptionLogUtils.format;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.common.core.KeyValue;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.datapermission.core.annotation.DataPermission;
import com.basicframework.module.system.dal.dataobject.sms.SmsChannelDO;
import com.basicframework.module.system.dal.dataobject.sms.SmsTemplateDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.framework.sms.core.client.SmsClient;
import com.basicframework.module.system.framework.sms.core.client.dto.SmsReceiveRespDTO;
import com.basicframework.module.system.framework.sms.core.client.dto.SmsSendRespDTO;
import com.basicframework.module.system.mq.message.sms.SmsSendMessage;
import com.basicframework.module.system.mq.producer.sms.SmsProducer;
import com.basicframework.module.system.service.user.AdminUserService;
import com.google.common.annotations.VisibleForTesting;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 短信发送 Service 发送的实现
 *
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmsSendServiceImpl implements SmsSendService {

    private final AdminUserService adminUserService;

    private final SmsChannelService smsChannelService;

    private final SmsTemplateService smsTemplateService;

    private final SmsLogService smsLogService;

    private final SmsProducer smsProducer;

    @Override
    @DataPermission(enable = false) // 发送短信时，无需考虑数据权限
    public Long sendSingleSmsToAdmin(
            String mobile, Long userId, String templateCode, Map<String, Object> templateParams) {
        // 如果 mobile 为空，则加载用户编号对应的手机号
        if (StrUtil.isEmpty(mobile)) {
            AdminUserDO user = adminUserService.getUser(userId);
            if (user != null) {
                mobile = user.getMobile();
            }
        }
        // 执行发送
        return sendSingleSms(mobile, userId, UserTypeEnum.ADMIN.getValue(), templateCode, templateParams);
    }

    @Override
    public Long sendSingleSmsToMember(
            String mobile, Long userId, String templateCode, Map<String, Object> templateParams) {
        // 执行发送 (mobile 为空时由 validateMobile 抛出异常)
        return sendSingleSms(mobile, userId, UserTypeEnum.MEMBER.getValue(), templateCode, templateParams);
    }

    @Override
    public Long sendSingleSms(
            String mobile, Long userId, Integer userType, String templateCode, Map<String, Object> templateParams) {
        // 校验短信模板是否合法
        SmsTemplateDO template = validateSmsTemplate(templateCode);
        // 校验短信渠道是否合法
        SmsChannelDO smsChannel = validateSmsChannel(template.getChannelId());

        // 校验手机号码是否存在
        mobile = validateMobile(mobile);
        // 构建有序的模板参数。为什么放在这个位置，是提前保证模板参数的正确性，而不是到了插入发送日志
        List<KeyValue<String, Object>> newTemplateParams = buildTemplateParams(template, templateParams);

        // 创建发送日志。如果模板被禁用，则不发送短信，只记录日志
        Boolean isSend = CommonStatusEnum.ENABLE.getStatus().equals(template.getStatus())
                && CommonStatusEnum.ENABLE.getStatus().equals(smsChannel.getStatus());
        String content = smsTemplateService.formatSmsTemplateContent(template.getContent(), templateParams);
        Long sendLogId =
                smsLogService.createSmsLog(mobile, userId, userType, isSend, template, content, templateParams);

        // 发送 MQ 消息，异步执行发送短信
        if (isSend) {
            smsProducer.sendSmsSendMessage(
                    sendLogId, mobile, template.getChannelId(), template.getApiTemplateId(), newTemplateParams);
        }
        return sendLogId;
    }

    @VisibleForTesting
    SmsChannelDO validateSmsChannel(Long channelId) {
        // 获得短信模板。考虑到效率，从缓存中获取
        SmsChannelDO channelDO = smsChannelService.getSmsChannel(channelId);
        // 短信模板不存在
        if (channelDO == null) {
            throw exception(SMS_CHANNEL_NOT_EXISTS);
        }
        return channelDO;
    }

    @VisibleForTesting
    SmsTemplateDO validateSmsTemplate(String templateCode) {
        // 获得短信模板。考虑到效率，从缓存中获取
        SmsTemplateDO template = smsTemplateService.getSmsTemplateByCodeFromCache(templateCode);
        // 短信模板不存在
        if (template == null) {
            throw exception(SMS_SEND_TEMPLATE_NOT_EXISTS);
        }
        return template;
    }

    /**
     * 将参数模板，处理成有序的 KeyValue 数组
     * <p>
     * 原因是，部分短信平台并不是使用 key 作为参数，而是数组下标（按模板占位符顺序取值）
     *
     * @param template       短信模板
     * @param templateParams 原始参数
     * @return 处理后的参数
     */
    @VisibleForTesting
    List<KeyValue<String, Object>> buildTemplateParams(SmsTemplateDO template, Map<String, Object> templateParams) {
        return template.getParams().stream()
                .map(key -> {
                    Object value = templateParams.get(key);
                    if (value == null) {
                        throw exception(SMS_SEND_MOBILE_TEMPLATE_PARAM_MISS, key);
                    }
                    return new KeyValue<>(key, value);
                })
                .collect(Collectors.toList());
    }

    @VisibleForTesting
    public String validateMobile(String mobile) {
        if (StrUtil.isEmpty(mobile)) {
            throw exception(SMS_SEND_MOBILE_NOT_EXISTS);
        }
        return mobile;
    }

    @Override
    public void doSendSms(SmsSendMessage message) {
        // 获得渠道对应的 SmsClient 客户端
        SmsClient smsClient = smsChannelService.getSmsClient(message.getChannelId());
        if (smsClient == null) {
            throw exception(SMS_CLIENT_NOT_EXISTS, message.getChannelId());
        }
        // 发送短信
        try {
            SmsSendRespDTO sendResponse = smsClient.sendSms(
                    message.getLogId(), message.getMobile(), message.getApiTemplateId(), message.getTemplateParams());
            smsLogService.updateSmsSendResult(
                    message.getLogId(),
                    sendResponse.getSuccess(),
                    sendResponse.getApiCode(),
                    sendResponse.getApiMsg(),
                    sendResponse.getApiRequestId(),
                    sendResponse.getSerialNo());
        } catch (Exception ex) {
            log.error("[doSendSms][发送短信异常，日志编号({})，stackTrace({})]", message.getLogId(), format(ex));
            smsLogService.updateSmsSendResult(
                    message.getLogId(), false, "EXCEPTION", ex.getClass().getName(), null, null);
        }
    }

    @Override
    @Transactional(rollbackFor = Throwable.class)
    public void receiveSmsStatus(String channelCode, String text) throws Exception {
        // 获得渠道对应的 SmsClient 客户端
        SmsClient smsClient = smsChannelService.getSmsClient(channelCode);
        if (smsClient == null) {
            throw exception(SMS_CLIENT_NOT_EXISTS, channelCode);
        }
        // 解析内容
        List<SmsReceiveRespDTO> receiveResults = smsClient.parseSmsReceiveStatus(text);
        if (CollUtil.isEmpty(receiveResults)) {
            return;
        }
        // 更新短信日志的接收结果. 因为量一般不大，所以先使用 for 循环更新
        for (SmsReceiveRespDTO result : receiveResults) {
            boolean updated = smsLogService.updateSmsReceiveResult(new SmsReceiveResultCommand(
                    channelCode,
                    result.getLogId(),
                    result.getSerialNo(),
                    result.getSuccess(),
                    result.getReceiveTime(),
                    result.getErrorCode(),
                    result.getErrorMsg()));
            if (!updated) {
                throw new IllegalStateException("短信回执无法匹配已发送日志");
            }
        }
    }
}
