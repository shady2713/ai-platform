package com.basicframework.module.system.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.anji.captcha.model.common.ResponseModel;
import com.anji.captcha.model.vo.CaptchaVO;
import com.anji.captcha.service.CaptchaService;
import com.basicframework.module.system.config.CaptchaProperties;
import com.basicframework.module.system.service.auth.dto.CaptchaVerificationDTO;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CaptchaVerificationServiceTest {

    @InjectMocks
    private CaptchaVerificationService captchaVerificationService;

    @Mock
    private Validator validator;

    @Mock
    private CaptchaService captchaService;

    @Spy
    private CaptchaProperties captchaProperties = new CaptchaProperties();

    @Test
    void verify_returnsSuccessWithoutTouchingUpstreamWhenExplicitlyDisabled() {
        captchaProperties.setEnable(false);

        ResponseModel response = captchaVerificationService.verify(new CaptchaVerificationDTO());

        assertThat(response.isSuccess()).isTrue();
        verifyNoInteractions(validator, captchaService);
    }

    @Test
    void verify_validatesAndDelegatesWhenEnabled() {
        CaptchaVerificationDTO request = new CaptchaVerificationDTO();
        request.setCaptchaVerification("captcha-payload");
        when(validator.validate(any(), any(Class[].class))).thenReturn(Set.of());
        when(captchaService.verification(any(CaptchaVO.class))).thenReturn(ResponseModel.success());

        ResponseModel response = captchaVerificationService.verify(request);

        assertThat(response.isSuccess()).isTrue();
        ArgumentCaptor<CaptchaVO> captcha = ArgumentCaptor.forClass(CaptchaVO.class);
        verify(captchaService).verification(captcha.capture());
        assertThat(captcha.getValue().getCaptchaVerification()).isEqualTo("captcha-payload");
    }
}
