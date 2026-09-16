package com.basicframework.module.system.service.sms;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.util.date.DateUtils.isToday;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.map.MapUtil;
import com.baomidou.lock.annotation.Lock4j;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.dal.dataobject.sms.SmsCodeDO;
import com.basicframework.module.system.dal.mysql.sms.SmsCodeMapper;
import com.basicframework.module.system.dal.redis.sms.SmsCodeAttemptRedisDAO;
import com.basicframework.module.system.enums.sms.SmsSceneEnum;
import com.basicframework.module.system.framework.sms.config.SmsCodeProperties;
import com.basicframework.module.system.service.sms.dto.SmsCodeSendReqDTO;
import com.basicframework.module.system.service.sms.dto.SmsCodeUseReqDTO;
import com.basicframework.module.system.service.sms.dto.SmsCodeValidateReqDTO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * 短信验证码 Service 实现类
 *
 */
@Service
@Validated
@RequiredArgsConstructor
public class SmsCodeServiceImpl implements SmsCodeService {

    private static final int SMS_CODE_LENGTH = 6;
    private static final int SMS_CODE_BOUND = 1_000_000;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SmsCodeProperties smsCodeProperties;

    private final SmsCodeMapper smsCodeMapper;

    private final SmsCodeAttemptRedisDAO smsCodeAttemptRedisDAO;

    private final SmsSendService smsSendService;

    @Override
    @Lock4j(name = "sms_code_send", keys = "#reqDTO.mobile")
    public void sendSmsCode(SmsCodeSendReqDTO reqDTO) {
        SmsSceneEnum sceneEnum = SmsSceneEnum.getCodeByScene(reqDTO.getScene());
        if (sceneEnum == null) {
            throw exception(SMS_CODE_SCENE_NOT_EXISTS, reqDTO.getScene());
        }
        // 创建验证码
        String code = createSmsCode(reqDTO.getMobile(), reqDTO.getScene(), reqDTO.getCreateIp());
        // 发送验证码
        smsSendService.sendSingleSms(
                reqDTO.getMobile(), null, null, sceneEnum.getTemplateCode(), MapUtil.of("code", code));
    }

    private String createSmsCode(String mobile, Integer scene, String ip) {
        // 校验是否可以发送验证码，不用筛选场景
        SmsCodeDO lastSmsCode = smsCodeMapper.selectLastByMobile(mobile, null, null);
        if (lastSmsCode != null) {
            if (LocalDateTimeUtil.between(lastSmsCode.getCreateTime(), LocalDateTime.now())
                            .toMillis()
                    < smsCodeProperties.getSendFrequency().toMillis()) { // 发送过于频繁
                throw exception(SMS_CODE_SEND_TOO_FAST);
            }
            if (isToday(lastSmsCode.getCreateTime())
                    && // 必须是今天，才能计算超过当天的上限
                    lastSmsCode.getTodayIndex() >= smsCodeProperties.getSendMaximumQuantityPerDay()) { // 超过当天发送的上限。
                throw exception(SMS_CODE_EXCEED_SEND_MAXIMUM_QUANTITY_PER_DAY);
            }
            // 当前仅按手机号做发送频控，IP 维度限流后续如有明确需求再单独补充。
        }

        // 创建验证码记录
        String code = String.format("%0" + SMS_CODE_LENGTH + "d", SECURE_RANDOM.nextInt(SMS_CODE_BOUND));
        SmsCodeDO newSmsCode = SmsCodeDO.builder()
                .mobile(mobile)
                .code(code)
                .scene(scene)
                .todayIndex(
                        lastSmsCode != null && isToday(lastSmsCode.getCreateTime())
                                ? lastSmsCode.getTodayIndex() + 1
                                : 1)
                .createIp(ip)
                .used(false)
                .build();
        smsCodeMapper.insert(newSmsCode);
        return code;
    }

    @Override
    public void useSmsCode(SmsCodeUseReqDTO reqDTO) {
        // 检测验证码是否有效
        SmsCodeDO lastSmsCode = validateSmsCode0(reqDTO.getMobile(), reqDTO.getCode(), reqDTO.getScene());
        // 条件更新核销：UPDATE 携带 used = 0 条件，并发请求只有一个能核销成功
        int updated = smsCodeMapper.markUsedIfUnused(lastSmsCode.getId(), LocalDateTime.now(), reqDTO.getUsedIp());
        if (updated == 0) {
            throw exception(SMS_CODE_USED);
        }
        // 核销成功后清空失败计数
        smsCodeAttemptRedisDAO.clear(lastSmsCode.getId());
    }

    @Override
    public void validateSmsCode(SmsCodeValidateReqDTO reqDTO) {
        validateSmsCode0(reqDTO.getMobile(), reqDTO.getCode(), reqDTO.getScene());
    }

    private SmsCodeDO validateSmsCode0(String mobile, String code, Integer scene) {
        // 始终校验最新代次，避免同场景内较早但尚未过期的验证码继续生效。
        SmsCodeDO lastSmsCode = smsCodeMapper.selectLastByMobile(mobile, null, scene);
        if (lastSmsCode == null) {
            throw exception(SMS_CODE_NOT_EXISTS);
        }
        LocalDateTime expiresAt = lastSmsCode.getCreateTime().plus(smsCodeProperties.getExpireTimes());
        LocalDateTime now = LocalDateTime.now();
        if (!expiresAt.isAfter(now)) {
            throw exception(SMS_CODE_EXPIRED);
        }
        if (Boolean.TRUE.equals(lastSmsCode.getUsed())) {
            throw exception(SMS_CODE_USED);
        }
        if (!codeEquals(lastSmsCode.getCode(), code)) {
            throw recordValidateFailure(lastSmsCode, Duration.between(now, expiresAt), SMS_CODE_NOT_EXISTS);
        }
        return lastSmsCode;
    }

    private static boolean codeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 记录一次校验失败；失败次数达到上限时作废当前最新验证码，阻断对 6 位短码的持续猜测。
     *
     * @param smsCode 当前最新验证码记录
     * @param ttl 当前验证码的剩余有效期
     * @param errorCode 原始校验错误
     * @return 原始校验错误对应的异常，供调用处直接抛出
     */
    private ServiceException recordValidateFailure(SmsCodeDO smsCode, Duration ttl, ErrorCode errorCode) {
        long failures = smsCodeAttemptRedisDAO.recordFailure(smsCode.getId(), ttl);
        if (failures >= smsCodeProperties.getMaxValidateFailures()) {
            smsCodeMapper.markUsedIfUnused(smsCode.getId(), LocalDateTime.now(), null);
        }
        return exception(errorCode);
    }
}
