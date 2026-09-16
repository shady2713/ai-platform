package com.basicframework.module.system.service.sms;

import com.basicframework.module.system.dal.dataobject.sms.SmsTemplateDO;
import java.util.Map;

/**
 * 短信日志 Service 接口（仅保留写入方法，查询功能已移除）
 */
public interface SmsLogService {

    /**
     * 创建短信日志
     */
    Long createSmsLog(
            String mobile,
            Long userId,
            Integer userType,
            Boolean isSend,
            SmsTemplateDO template,
            String templateContent,
            Map<String, Object> templateParams);

    /**
     * 更新短信发送结果
     */
    void updateSmsSendResult(
            Long id, Boolean success, String apiSendCode, String apiSendMsg, String apiRequestId, String apiSerialNo);

    /**
     * 更新短信接收结果
     */
    boolean updateSmsReceiveResult(SmsReceiveResultCommand command);
}
