package com.basicframework.module.system.service.auth;

import com.anji.captcha.model.common.ResponseModel;
import com.anji.captcha.model.vo.CaptchaVO;
import com.anji.captcha.service.CaptchaService;
import com.basicframework.framework.common.util.validation.ValidationUtils;
import com.basicframework.module.system.config.CaptchaProperties;
import com.basicframework.module.system.service.auth.dto.CaptchaVerificationDTO;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 验证码开关、参数校验与上游校验服务的唯一衔接点。 */
@Service
@RequiredArgsConstructor
public class CaptchaVerificationService {

    private final Validator validator;
    private final CaptchaService captchaService;
    private final CaptchaProperties captchaProperties;

    /**
     * 校验图形验证码；关闭开关时仅在显式 local/test 配置下通过。
     *
     * @param reqDTO 待校验的客户端验证码参数
     * @return 上游验证码服务的校验结果
     */
    public ResponseModel verify(CaptchaVerificationDTO reqDTO) {
        if (!captchaProperties.isEnable()) {
            return ResponseModel.success();
        }
        ValidationUtils.validate(validator, reqDTO, CaptchaVerificationDTO.CodeEnableGroup.class);
        CaptchaVO captchaVO = new CaptchaVO();
        captchaVO.setCaptchaVerification(reqDTO.getCaptchaVerification());
        return captchaService.verification(captchaVO);
    }
}
