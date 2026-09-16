package com.basicframework.module.system.controller.admin.sms;

import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import com.basicframework.framework.ratelimiter.core.keyresolver.impl.ClientIpRateLimiterKeyResolver;
import com.basicframework.module.system.framework.sms.config.SmsCallbackAuthenticator;
import com.basicframework.module.system.framework.sms.core.enums.SmsChannelEnum;
import com.basicframework.module.system.service.sms.SmsSendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理后台 - 短信回调")
@RestController
@RequestMapping("/system/sms/callback")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "basic-framework.sms-callback", name = "enabled", havingValue = "true")
public class SmsCallbackController {

    private final SmsSendService smsSendService;
    private final SmsCallbackAuthenticator smsCallbackAuthenticator;

    @PostMapping("/aliyun")
    @PermitAll
    @RateLimiter(time = 60, count = 300, message = "短信回调请求过于频繁", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @Operation(summary = "阿里云短信的回调", description = "参见 https://help.aliyun.com/document_detail/120998.html 文档")
    public AliyunCallbackResponse receiveAliyunSmsStatus(
            @RequestHeader(value = SmsCallbackAuthenticator.TOKEN_HEADER, required = false) String headerToken,
            @RequestParam(value = "callbackToken", required = false) String queryToken,
            @RequestBody String text)
            throws Exception {
        authenticateAndValidate(headerToken, queryToken, text);
        smsSendService.receiveSmsStatus(SmsChannelEnum.ALIYUN.getCode(), text);
        return new AliyunCallbackResponse(0, "success");
    }

    private void authenticateAndValidate(String headerToken, String queryToken, String text) {
        smsCallbackAuthenticator.authenticate(StrUtil.blankToDefault(headerToken, queryToken));
        smsCallbackAuthenticator.validatePayload(text);
    }

    public record AliyunCallbackResponse(int code, String msg) {}
}
