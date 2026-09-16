package com.basicframework.module.system.service.sms;

import com.basicframework.module.system.mq.message.sms.SmsSendMessage;
import java.util.Map;

/**
 * 短信发送 Service 接口
 *
 */
public interface SmsSendService {

    /**
     * 发送单条短信给管理后台的用户
     *
     * 在 mobile 为空时，使用 userId 加载对应管理员的手机号
     *
     * @param mobile 手机号
     * @param userId 用户编号
     * @param templateCode 短信模板编号
     * @param templateParams 短信模板参数
     * @return 发送日志编号
     */
    Long sendSingleSmsToAdmin(String mobile, Long userId, String templateCode, Map<String, Object> templateParams);

    /**
     * 发送单条短信给应用端会员
     *
     * 在 mobile 为空时，使用 userId 加载对应会员的手机号
     *
     * @param mobile 手机号
     * @param userId 用户编号
     * @param templateCode 短信模板编号
     * @param templateParams 短信模板参数
     * @return 发送日志编号
     */
    Long sendSingleSmsToMember(String mobile, Long userId, String templateCode, Map<String, Object> templateParams);

    /**
     * 发送单条短信给用户
     *
     * @param mobile 手机号
     * @param userId 用户编号
     * @param userType 用户类型
     * @param templateCode 短信模板编号
     * @param templateParams 短信模板参数
     * @return 发送日志编号
     */
    Long sendSingleSms(
            String mobile, Long userId, Integer userType, String templateCode, Map<String, Object> templateParams);

    /**
     * 执行真正的短信发送
     * 注意，该方法仅仅提供给 MQ Consumer 使用
     *
     * @param message 短信
     */
    void doSendSms(SmsSendMessage message);

    /**
     * 接收短信的接收结果
     *
     * @param channelCode 渠道编码
     * @param text 结果内容
     * @throws Exception 处理失败时，抛出异常
     */
    void receiveSmsStatus(String channelCode, String text) throws Exception;
}
