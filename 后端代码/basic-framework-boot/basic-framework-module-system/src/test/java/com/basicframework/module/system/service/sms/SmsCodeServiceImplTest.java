package com.basicframework.module.system.service.sms;

import static com.basicframework.module.system.enums.ErrorCodeConstants.SMS_CODE_EXCEED_SEND_MAXIMUM_QUANTITY_PER_DAY;
import static com.basicframework.module.system.enums.ErrorCodeConstants.SMS_CODE_EXPIRED;
import static com.basicframework.module.system.enums.ErrorCodeConstants.SMS_CODE_NOT_EXISTS;
import static com.basicframework.module.system.enums.ErrorCodeConstants.SMS_CODE_SCENE_NOT_EXISTS;
import static com.basicframework.module.system.enums.ErrorCodeConstants.SMS_CODE_SEND_TOO_FAST;
import static com.basicframework.module.system.enums.ErrorCodeConstants.SMS_CODE_USED;
import static com.basicframework.module.system.testutil.ServiceExceptionAssert.assertServiceException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.lock.annotation.Lock4j;
import com.basicframework.module.system.dal.dataobject.sms.SmsCodeDO;
import com.basicframework.module.system.dal.mysql.sms.SmsCodeMapper;
import com.basicframework.module.system.dal.redis.sms.SmsCodeAttemptRedisDAO;
import com.basicframework.module.system.enums.sms.SmsSceneEnum;
import com.basicframework.module.system.framework.sms.config.SmsCodeProperties;
import com.basicframework.module.system.service.sms.dto.SmsCodeSendReqDTO;
import com.basicframework.module.system.service.sms.dto.SmsCodeUseReqDTO;
import com.basicframework.module.system.service.sms.dto.SmsCodeValidateReqDTO;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@link SmsCodeServiceImpl} 验证码核销的原子性与失败计数测试。 */
class SmsCodeServiceImplTest {

    private static final String MOBILE = "13900000001";
    private static final Integer SCENE = SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene();
    private static final Duration EXPIRE_TIMES = Duration.ofMinutes(5);
    private static final int MAX_VALIDATE_FAILURES = 5;

    private final SmsCodeMapper smsCodeMapper = mock(SmsCodeMapper.class);
    private final SmsCodeAttemptRedisDAO smsCodeAttemptRedisDAO = mock(SmsCodeAttemptRedisDAO.class);
    private final SmsSendService smsSendService = mock(SmsSendService.class);
    private final SmsCodeProperties smsCodeProperties = new SmsCodeProperties();
    private final SmsCodeServiceImpl smsCodeService =
            new SmsCodeServiceImpl(smsCodeProperties, smsCodeMapper, smsCodeAttemptRedisDAO, smsSendService);

    @BeforeEach
    void setUp() {
        smsCodeProperties.setExpireTimes(EXPIRE_TIMES);
        smsCodeProperties.setSendFrequency(Duration.ofMinutes(1));
        smsCodeProperties.setSendMaximumQuantityPerDay(10);
        smsCodeProperties.setMaxValidateFailures(MAX_VALIDATE_FAILURES);
        // 默认首次失败，按场景单独覆盖
        lenient().when(smsCodeAttemptRedisDAO.recordFailure(any(), any())).thenReturn(1L);
    }

    @Test
    void sendSmsCode_hasMobileScopedDistributedLock() throws NoSuchMethodException {
        Lock4j lock = SmsCodeServiceImpl.class
                .getMethod("sendSmsCode", SmsCodeSendReqDTO.class)
                .getAnnotation(Lock4j.class);

        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("sms_code_send");
        assertThat(lock.keys()).containsExactly("#reqDTO.mobile");
    }

    @Test
    void sendSmsCode_validRequest_persistsRandomCodeAndEnqueuesConfiguredTemplate() {
        when(smsCodeMapper.selectLastByMobile(MOBILE, null, null)).thenReturn(null);

        smsCodeService.sendSmsCode(sendReqDTO(SCENE));

        ArgumentCaptor<SmsCodeDO> codeCaptor = ArgumentCaptor.forClass(SmsCodeDO.class);
        verify(smsCodeMapper).insert(codeCaptor.capture());
        SmsCodeDO savedCode = codeCaptor.getValue();
        assertThat(savedCode.getMobile()).isEqualTo(MOBILE);
        assertThat(savedCode.getScene()).isEqualTo(SCENE);
        assertThat(savedCode.getCreateIp()).isEqualTo("127.0.0.1");
        assertThat(savedCode.getCode()).matches("\\d{6}");
        assertThat(savedCode.getTodayIndex()).isEqualTo(1);
        assertThat(savedCode.getUsed()).isFalse();
        verify(smsSendService)
                .sendSingleSms(
                        eq(MOBILE),
                        isNull(),
                        isNull(),
                        eq(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getTemplateCode()),
                        argThat(params -> savedCode.getCode().equals(params.get("code")) && params.size() == 1));
    }

    @Test
    void sendSmsCode_rejectsUnknownSceneBeforePersistingOrSending() {
        assertServiceException(SMS_CODE_SCENE_NOT_EXISTS.getCode(), () -> smsCodeService.sendSmsCode(sendReqDTO(999)));

        verifyNoInteractions(smsCodeMapper, smsSendService);
    }

    @Test
    void sendSmsCode_rejectsRecentCodeBeforePersistingOrSending() {
        SmsCodeDO recentCode = unusedCode(100L);
        recentCode.setCreateTime(LocalDateTime.now().minusSeconds(30));
        when(smsCodeMapper.selectLastByMobile(MOBILE, null, null)).thenReturn(recentCode);

        assertServiceException(SMS_CODE_SEND_TOO_FAST.getCode(), () -> smsCodeService.sendSmsCode(sendReqDTO(SCENE)));

        verify(smsCodeMapper, never()).insert(any(SmsCodeDO.class));
        verifyNoInteractions(smsSendService);
    }

    @Test
    void sendSmsCode_rejectsDailyLimitBeforePersistingOrSending() {
        SmsCodeDO maxedCode = unusedCode(100L);
        maxedCode.setCreateTime(LocalDateTime.now().minusMinutes(2));
        maxedCode.setTodayIndex(smsCodeProperties.getSendMaximumQuantityPerDay());
        when(smsCodeMapper.selectLastByMobile(MOBILE, null, null)).thenReturn(maxedCode);

        assertServiceException(
                SMS_CODE_EXCEED_SEND_MAXIMUM_QUANTITY_PER_DAY.getCode(),
                () -> smsCodeService.sendSmsCode(sendReqDTO(SCENE)));

        verify(smsCodeMapper, never()).insert(any(SmsCodeDO.class));
        verifyNoInteractions(smsSendService);
    }

    @Test
    void useSmsCode_validCode_marksUsedAndClearsFailureCount() {
        SmsCodeDO smsCode = unusedCode(100L);
        when(smsCodeMapper.selectLastByMobile(MOBILE, null, SCENE)).thenReturn(smsCode);
        when(smsCodeMapper.markUsedIfUnused(eq(100L), any(LocalDateTime.class), eq("127.0.0.1")))
                .thenReturn(1);

        smsCodeService.useSmsCode(useReqDTO("123456"));

        verify(smsCodeMapper).markUsedIfUnused(eq(100L), any(LocalDateTime.class), eq("127.0.0.1"));
        verify(smsCodeAttemptRedisDAO).clear(100L);
    }

    @Test
    void useSmsCode_concurrentConsumption_onlyOneSucceeds() {
        // 两个并发请求都通过了前置校验（读取时 used=false），条件更新只有一个能生效
        SmsCodeDO smsCode = unusedCode(100L);
        when(smsCodeMapper.selectLastByMobile(MOBILE, null, SCENE)).thenReturn(smsCode);
        when(smsCodeMapper.markUsedIfUnused(eq(100L), any(LocalDateTime.class), any()))
                .thenReturn(0);

        assertServiceException(SMS_CODE_USED.getCode(), () -> smsCodeService.useSmsCode(useReqDTO("123456")));
        verify(smsCodeAttemptRedisDAO, never()).clear(any());
        // 竞争失败不计入猜测失败次数
        verify(smsCodeAttemptRedisDAO, never()).recordFailure(any(), any());
    }

    @Test
    void useSmsCode_wrongCode_countsFailureAgainstCurrentGenerationAndRemainingTtl() {
        SmsCodeDO smsCode = unusedCode(100L);
        when(smsCodeMapper.selectLastByMobile(MOBILE, null, SCENE)).thenReturn(smsCode);

        assertServiceException(SMS_CODE_NOT_EXISTS.getCode(), () -> smsCodeService.useSmsCode(useReqDTO("000000")));
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(smsCodeAttemptRedisDAO).recordFailure(eq(100L), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isPositive().isLessThanOrEqualTo(EXPIRE_TIMES);
        verify(smsCodeMapper, never()).markUsedIfUnused(any(), any(), any());
    }

    @Test
    void useSmsCode_withoutIssuedCode_doesNotCreateUnscopedFailureCounter() {
        when(smsCodeMapper.selectLastByMobile(MOBILE, null, SCENE)).thenReturn(null);

        assertServiceException(SMS_CODE_NOT_EXISTS.getCode(), () -> smsCodeService.useSmsCode(useReqDTO("000000")));

        verify(smsCodeAttemptRedisDAO, never()).recordFailure(any(), any());
    }

    @Test
    void useSmsCode_failuresReachLimit_invalidatesLatestCode() {
        SmsCodeDO latestCode = unusedCode(101L);
        when(smsCodeMapper.selectLastByMobile(MOBILE, null, SCENE)).thenReturn(latestCode);
        when(smsCodeAttemptRedisDAO.recordFailure(eq(101L), any())).thenReturn((long) MAX_VALIDATE_FAILURES);

        assertServiceException(SMS_CODE_NOT_EXISTS.getCode(), () -> smsCodeService.useSmsCode(useReqDTO("000000")));
        // 达到失败上限后，当前最新验证码被作废（置为已使用）
        verify(smsCodeMapper).markUsedIfUnused(eq(101L), any(LocalDateTime.class), isNull());
    }

    @Test
    void useSmsCode_failuresBelowLimit_keepsLatestCodeUsable() {
        SmsCodeDO latestCode = unusedCode(101L);
        when(smsCodeMapper.selectLastByMobile(MOBILE, null, SCENE)).thenReturn(latestCode);
        when(smsCodeAttemptRedisDAO.recordFailure(eq(101L), any())).thenReturn((long) MAX_VALIDATE_FAILURES - 1);

        assertServiceException(SMS_CODE_NOT_EXISTS.getCode(), () -> smsCodeService.useSmsCode(useReqDTO("000000")));
        verify(smsCodeMapper, never()).markUsedIfUnused(any(), any(), any());
    }

    @Test
    void useSmsCode_oldGenerationCannotInvalidateNewestCode() {
        SmsCodeDO newestCode = unusedCode(202L);
        newestCode.setCode("654321");
        when(smsCodeMapper.selectLastByMobile(MOBILE, null, SCENE)).thenReturn(newestCode);
        when(smsCodeAttemptRedisDAO.recordFailure(eq(202L), any())).thenReturn((long) MAX_VALIDATE_FAILURES);

        assertServiceException(SMS_CODE_NOT_EXISTS.getCode(), () -> smsCodeService.useSmsCode(useReqDTO("123456")));

        verify(smsCodeMapper).markUsedIfUnused(eq(202L), any(LocalDateTime.class), isNull());
        verify(smsCodeAttemptRedisDAO).recordFailure(eq(202L), any());
    }

    @Test
    void validateSmsCode_expiredCode_doesNotExtendFailureCounterLifetime() {
        SmsCodeDO smsCode = unusedCode(100L);
        smsCode.setCreateTime(LocalDateTime.now().minus(EXPIRE_TIMES).minusSeconds(1));
        when(smsCodeMapper.selectLastByMobile(MOBILE, null, SCENE)).thenReturn(smsCode);

        SmsCodeValidateReqDTO reqDTO = new SmsCodeValidateReqDTO();
        reqDTO.setMobile(MOBILE);
        reqDTO.setScene(SCENE);
        reqDTO.setCode("123456");
        assertServiceException(SMS_CODE_EXPIRED.getCode(), () -> smsCodeService.validateSmsCode(reqDTO));
        verify(smsCodeAttemptRedisDAO, never()).recordFailure(any(), any());
    }

    @Test
    void validateSmsCode_alreadyUsedCode_doesNotAffectFutureGeneration() {
        SmsCodeDO smsCode = unusedCode(100L);
        smsCode.setUsed(true);
        when(smsCodeMapper.selectLastByMobile(MOBILE, null, SCENE)).thenReturn(smsCode);

        SmsCodeValidateReqDTO reqDTO = new SmsCodeValidateReqDTO();
        reqDTO.setMobile(MOBILE);
        reqDTO.setScene(SCENE);
        reqDTO.setCode("123456");
        assertServiceException(SMS_CODE_USED.getCode(), () -> smsCodeService.validateSmsCode(reqDTO));
        verify(smsCodeAttemptRedisDAO, never()).recordFailure(any(), any());
    }

    private static SmsCodeDO unusedCode(Long id) {
        SmsCodeDO smsCode = SmsCodeDO.builder()
                .id(id)
                .mobile(MOBILE)
                .code("123456")
                .scene(SCENE)
                .used(false)
                .build();
        smsCode.setCreateTime(LocalDateTime.now());
        return smsCode;
    }

    private static SmsCodeUseReqDTO useReqDTO(String code) {
        SmsCodeUseReqDTO reqDTO = new SmsCodeUseReqDTO();
        reqDTO.setMobile(MOBILE);
        reqDTO.setScene(SCENE);
        reqDTO.setCode(code);
        reqDTO.setUsedIp("127.0.0.1");
        return reqDTO;
    }

    private static SmsCodeSendReqDTO sendReqDTO(Integer scene) {
        SmsCodeSendReqDTO reqDTO = new SmsCodeSendReqDTO();
        reqDTO.setMobile(MOBILE);
        reqDTO.setScene(scene);
        reqDTO.setCreateIp("127.0.0.1");
        return reqDTO;
    }
}
