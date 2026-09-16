package com.basicframework.module.system.service.sms;

import cn.hutool.core.util.StrUtil;
import com.basicframework.module.system.dal.dataobject.sms.SmsLogDO;
import com.basicframework.module.system.dal.dataobject.sms.SmsTemplateDO;
import com.basicframework.module.system.dal.mysql.sms.SmsLogMapper;
import com.basicframework.module.system.enums.sms.SmsReceiveStatusEnum;
import com.basicframework.module.system.enums.sms.SmsSceneEnum;
import com.basicframework.module.system.enums.sms.SmsSendStatusEnum;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 短信日志 Service 实现类（仅保留写入方法）
 */
@Service
@RequiredArgsConstructor
public class SmsLogServiceImpl implements SmsLogService {

    private static final String REDACTED_VERIFICATION_CODE = "[REDACTED]";
    private static final Set<String> VERIFICATION_TEMPLATE_CODES = Arrays.stream(SmsSceneEnum.values())
            .map(SmsSceneEnum::getTemplateCode)
            .collect(Collectors.toUnmodifiableSet());

    private final SmsLogMapper smsLogMapper;

    @Override
    public Long createSmsLog(
            String mobile,
            Long userId,
            Integer userType,
            Boolean isSend,
            SmsTemplateDO template,
            String templateContent,
            Map<String, Object> templateParams) {
        String logTemplateContent = redactVerificationCode(template, templateContent, templateParams);
        Map<String, Object> logTemplateParams = redactVerificationCode(template, templateParams);
        SmsLogDO smsLogDO = SmsLogDO.builder()
                .mobile(mobile)
                .userId(userId)
                .userType(userType)
                .channelId(template.getChannelId())
                .channelCode(template.getChannelCode())
                .templateId(template.getId())
                .templateCode(template.getCode())
                .templateType(template.getType())
                .templateContent(logTemplateContent)
                .templateParams(logTemplateParams)
                .apiTemplateId(template.getApiTemplateId())
                .sendStatus(
                        Boolean.TRUE.equals(isSend)
                                ? SmsSendStatusEnum.INIT.getStatus()
                                : SmsSendStatusEnum.IGNORE.getStatus())
                .receiveStatus(SmsReceiveStatusEnum.INIT.getStatus())
                .build();
        smsLogMapper.insert(smsLogDO);
        return smsLogDO.getId();
    }

    @Override
    public void updateSmsSendResult(
            Long id, Boolean success, String apiSendCode, String apiSendMsg, String apiRequestId, String apiSerialNo) {
        SmsLogDO updateObj = new SmsLogDO();
        updateObj.setId(id);
        updateObj.setSendStatus(
                Boolean.TRUE.equals(success)
                        ? SmsSendStatusEnum.SUCCESS.getStatus()
                        : SmsSendStatusEnum.FAILURE.getStatus());
        updateObj.setSendTime(LocalDateTime.now());
        updateObj.setApiSendCode(apiSendCode);
        updateObj.setApiSendMsg(apiSendMsg);
        updateObj.setApiRequestId(apiRequestId);
        updateObj.setApiSerialNo(apiSerialNo);
        smsLogMapper.updateById(updateObj);
    }

    @Override
    public boolean updateSmsReceiveResult(SmsReceiveResultCommand command) {
        if (command == null
                || StrUtil.isBlank(command.channelCode())
                || StrUtil.isBlank(command.apiSerialNo())
                || command.success() == null) {
            throw new IllegalArgumentException("短信回执缺少渠道、流水号或状态");
        }
        SmsLogDO updateObj = new SmsLogDO();
        updateObj.setReceiveStatus(
                command.success()
                        ? SmsReceiveStatusEnum.SUCCESS.getStatus()
                        : SmsReceiveStatusEnum.FAILURE.getStatus());
        updateObj.setReceiveTime(command.receiveTime());
        updateObj.setApiReceiveCode(command.apiErrorCode());
        updateObj.setApiReceiveMsg(command.apiErrorMsg());
        int updated = smsLogMapper.updateReceiveResult(
                updateObj, command.logId(), command.channelCode(), command.apiSerialNo());
        return updated == 1
                || smsLogMapper.existsByProviderCorrelation(
                        command.logId(), command.channelCode(), command.apiSerialNo());
    }

    private static String redactVerificationCode(
            SmsTemplateDO template, String templateContent, Map<String, Object> templateParams) {
        if (!isVerificationTemplate(template) || templateContent == null || templateParams == null) {
            return templateContent;
        }
        Object code = templateParams.get("code");
        if (code == null || code.toString().isEmpty()) {
            return templateContent;
        }
        return templateContent.replace(code.toString(), REDACTED_VERIFICATION_CODE);
    }

    private static Map<String, Object> redactVerificationCode(
            SmsTemplateDO template, Map<String, Object> templateParams) {
        if (!isVerificationTemplate(template) || templateParams == null || !templateParams.containsKey("code")) {
            return templateParams;
        }
        Map<String, Object> redactedParams = new HashMap<>(templateParams);
        redactedParams.put("code", REDACTED_VERIFICATION_CODE);
        return redactedParams;
    }

    private static boolean isVerificationTemplate(SmsTemplateDO template) {
        return template != null && VERIFICATION_TEMPLATE_CODES.contains(template.getCode());
    }
}
