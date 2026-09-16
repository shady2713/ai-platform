package com.basicframework.module.system.framework.captcha.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.anji.captcha.model.common.RepCodeEnum;
import com.anji.captcha.model.common.ResponseModel;
import com.anji.captcha.model.vo.CaptchaVO;
import com.anji.captcha.service.CaptchaCacheService;
import com.anji.captcha.service.impl.AbstractCaptchaService;
import com.anji.captcha.service.impl.CaptchaServiceFactory;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

class PictureWordCaptchaServiceImplTest {

    @Test
    void generateRandomText_usesSecureRandomAndValidatesLength() {
        String text = PictureWordCaptchaServiceImpl.generateRandomText(64);

        assertThat(text).matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{64}");
        assertThat(ReflectionTestUtils.getField(PictureWordCaptchaServiceImpl.class, "CAPTCHA_RANDOM"))
                .isInstanceOf(SecureRandom.class);
        assertThatThrownBy(() -> PictureWordCaptchaServiceImpl.generateRandomText(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("验证码长度必须在 1 到 64 之间");
        assertThatThrownBy(() -> PictureWordCaptchaServiceImpl.generateRandomText(65))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("验证码长度必须在 1 到 64 之间");
    }

    @Test
    void captchaTokens_areConsumedAtomicallyWhenRedisCacheIsActive() {
        Object previousCacheType = ReflectionTestUtils.getField(AbstractCaptchaService.class, "cacheType");
        Object previousCaptchaKey = ReflectionTestUtils.getField(AbstractCaptchaService.class, "REDIS_CAPTCHA_KEY");
        Object previousSecondCaptchaKey =
                ReflectionTestUtils.getField(AbstractCaptchaService.class, "REDIS_SECOND_CAPTCHA_KEY");
        ReflectionTestUtils.setField(AbstractCaptchaService.class, "cacheType", "test");
        ReflectionTestUtils.setField(AbstractCaptchaService.class, "REDIS_CAPTCHA_KEY", "captcha:%s");
        ReflectionTestUtils.setField(AbstractCaptchaService.class, "REDIS_SECOND_CAPTCHA_KEY", "second:%s");
        RedisCaptchaServiceImpl cache = mock(RedisCaptchaServiceImpl.class);
        when(cache.getAndDelete("captcha:captcha-token")).thenReturn("malformed");
        when(cache.getAndDelete("second:verification-token")).thenReturn("captcha-token");
        CaptchaVO checkRequest = new CaptchaVO();
        checkRequest.setToken("captcha-token");
        CaptchaVO verificationRequest = new CaptchaVO();
        verificationRequest.setCaptchaVerification("verification-token");

        ResponseModel checkResponse;
        ResponseModel verificationResponse;
        try (MockedStatic<CaptchaServiceFactory> factory = mockStatic(CaptchaServiceFactory.class)) {
            factory.when(() -> CaptchaServiceFactory.getCache("test")).thenReturn(cache);
            PictureWordCaptchaServiceImpl service = new PictureWordCaptchaServiceImpl();
            checkResponse = service.check(checkRequest);
            verificationResponse = service.verification(verificationRequest);
        } finally {
            ReflectionTestUtils.setField(AbstractCaptchaService.class, "cacheType", previousCacheType);
            ReflectionTestUtils.setField(AbstractCaptchaService.class, "REDIS_CAPTCHA_KEY", previousCaptchaKey);
            ReflectionTestUtils.setField(
                    AbstractCaptchaService.class, "REDIS_SECOND_CAPTCHA_KEY", previousSecondCaptchaKey);
        }

        assertThat(checkResponse.getRepCode()).isEqualTo(RepCodeEnum.API_CAPTCHA_INVALID.getCode());
        assertThat(verificationResponse.isSuccess()).isTrue();
        verify(cache).getAndDelete("captcha:captcha-token");
        verify(cache).getAndDelete("second:verification-token");
        verify(cache, never()).exists(anyString());
        verify(cache, never()).get(anyString());
        verify(cache, never()).delete(anyString());
    }

    @Test
    void get_withAesDisabled_storesAnEmptySecretSlotInsteadOfLiteralNull() {
        Object previousCacheType = ReflectionTestUtils.getField(AbstractCaptchaService.class, "cacheType");
        Object previousCaptchaKey = ReflectionTestUtils.getField(AbstractCaptchaService.class, "REDIS_CAPTCHA_KEY");
        ReflectionTestUtils.setField(AbstractCaptchaService.class, "cacheType", "test");
        ReflectionTestUtils.setField(AbstractCaptchaService.class, "REDIS_CAPTCHA_KEY", "captcha:%s");
        CaptchaCacheService cache = mock(CaptchaCacheService.class);
        ResponseModel response;
        try (MockedStatic<CaptchaServiceFactory> factory = mockStatic(CaptchaServiceFactory.class)) {
            factory.when(() -> CaptchaServiceFactory.getCache("test")).thenReturn(cache);
            response = new PictureWordCaptchaServiceImpl().get(new CaptchaVO());
        } finally {
            ReflectionTestUtils.setField(AbstractCaptchaService.class, "cacheType", previousCacheType);
            ReflectionTestUtils.setField(AbstractCaptchaService.class, "REDIS_CAPTCHA_KEY", previousCaptchaKey);
        }

        CaptchaVO imageData = (CaptchaVO) response.getRepData();
        ArgumentCaptor<String> cachedValue = ArgumentCaptor.forClass(String.class);
        assertThat(response.isSuccess()).isTrue();
        assertThat(imageData.getToken()).isNotBlank();
        assertThat(imageData.getSecretKey()).isNull();
        assertThat(imageData.getOriginalImageBase64()).isNotBlank().doesNotContain("\r", "\n");
        verify(cache).set(eq("captcha:" + imageData.getToken()), cachedValue.capture(), anyLong());
        assertThat(cachedValue.getValue()).matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4},");
    }

    @Test
    void check_withAesDisabled_acceptsTheEmptySecretSlot() {
        Object previousCacheType = ReflectionTestUtils.getField(AbstractCaptchaService.class, "cacheType");
        Object previousCaptchaKey = ReflectionTestUtils.getField(AbstractCaptchaService.class, "REDIS_CAPTCHA_KEY");
        Object previousSecondCaptchaKey =
                ReflectionTestUtils.getField(AbstractCaptchaService.class, "REDIS_SECOND_CAPTCHA_KEY");
        ReflectionTestUtils.setField(AbstractCaptchaService.class, "cacheType", "test");
        ReflectionTestUtils.setField(AbstractCaptchaService.class, "REDIS_CAPTCHA_KEY", "captcha:%s");
        ReflectionTestUtils.setField(AbstractCaptchaService.class, "REDIS_SECOND_CAPTCHA_KEY", "second:%s");
        CaptchaCacheService cache = mock(CaptchaCacheService.class);
        when(cache.get("captcha:captcha-token")).thenReturn("ABCD,");
        CaptchaVO request = new CaptchaVO();
        request.setToken("captcha-token");
        request.setPointJson("abcd");
        ResponseModel response;
        try (MockedStatic<CaptchaServiceFactory> factory = mockStatic(CaptchaServiceFactory.class)) {
            factory.when(() -> CaptchaServiceFactory.getCache("test")).thenReturn(cache);
            response = new PictureWordCaptchaServiceImpl().check(request);
        } finally {
            ReflectionTestUtils.setField(AbstractCaptchaService.class, "cacheType", previousCacheType);
            ReflectionTestUtils.setField(AbstractCaptchaService.class, "REDIS_CAPTCHA_KEY", previousCaptchaKey);
            ReflectionTestUtils.setField(
                    AbstractCaptchaService.class, "REDIS_SECOND_CAPTCHA_KEY", previousSecondCaptchaKey);
        }

        assertThat(response.isSuccess()).isTrue();
        assertThat(request.getResult()).isTrue();
        verify(cache).delete("captcha:captcha-token");
        verify(cache).set(eq("second:captcha-token---abcd"), eq("captcha-token"), anyLong());
    }

    @Test
    void verification_masksInternalCacheFailure() {
        Object previousCacheType = ReflectionTestUtils.getField(AbstractCaptchaService.class, "cacheType");
        ReflectionTestUtils.setField(AbstractCaptchaService.class, "cacheType", "test");
        CaptchaCacheService cache = mock(CaptchaCacheService.class);
        when(cache.get(anyString())).thenThrow(new IllegalStateException("redis://user:secret@internal"));
        CaptchaVO request = new CaptchaVO();
        request.setCaptchaVerification("verification-token");
        Logger logger = (Logger) LoggerFactory.getLogger(PictureWordCaptchaServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        boolean previousAdditive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(appender);

        ResponseModel response;
        try (MockedStatic<CaptchaServiceFactory> factory = mockStatic(CaptchaServiceFactory.class)) {
            factory.when(() -> CaptchaServiceFactory.getCache("test")).thenReturn(cache);
            response = new PictureWordCaptchaServiceImpl().verification(request);
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(previousAdditive);
            appender.stop();
            ReflectionTestUtils.setField(AbstractCaptchaService.class, "cacheType", previousCacheType);
        }

        assertThat(response.getRepCode()).isEqualTo(RepCodeEnum.API_CAPTCHA_ERROR.getCode());
        assertThat(response.getRepMsg()).doesNotContain("redis://", "secret", "internal");
        assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .doesNotContain("redis://", "secret", "internal"));
    }
}
