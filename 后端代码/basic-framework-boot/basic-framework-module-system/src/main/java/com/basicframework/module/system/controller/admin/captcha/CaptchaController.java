package com.basicframework.module.system.controller.admin.captcha;

import cn.hutool.core.util.StrUtil;
import com.anji.captcha.model.common.ResponseModel;
import com.anji.captcha.model.vo.CaptchaVO;
import com.anji.captcha.service.CaptchaService;
import com.basicframework.framework.common.util.servlet.ServletUtils;
import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import com.basicframework.framework.ratelimiter.core.keyresolver.impl.ClientIpRateLimiterKeyResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 验证码 Controller，提供图形验证码的生成能力
 *
 * 已批准的协议例外（见 docs/adr/0003）：全仓唯一直接返回 anji-captcha {@link ResponseModel}
 * 而非 CommonResult 的端点。前端 @vben/common-ui 的 Verification 组件直接判定 repCode
 * 协议字段（res.repCode === '0000'），包装为 CommonResult 会打断滑动/点选验证码交互；
 * 待前端验证码组件适配 CommonResult 后再收敛，两端必须在同一变更中切换。
 */
@Tag(name = "管理后台 - 验证码")
@RestController("adminCaptchaController")
@RequestMapping("/system/captcha")
@RequiredArgsConstructor
public class CaptchaController {

    private final CaptchaService captchaService;

    @PostMapping({"/get"})
    @Operation(summary = "获得验证码")
    @PermitAll
    @RateLimiter(time = 60, count = 30, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    public ResponseModel get(@RequestBody CaptchaVO data, HttpServletRequest request) {
        data.setBrowserInfo(getRemoteId(request));
        return captchaService.get(data);
    }

    @PostMapping("/check")
    @Operation(summary = "校验验证码")
    @PermitAll
    @RateLimiter(time = 60, count = 30, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    public ResponseModel check(@RequestBody CaptchaVO data, HttpServletRequest request) {
        data.setBrowserInfo(getRemoteId(request));
        return captchaService.check(data);
    }

    public static String getRemoteId(HttpServletRequest request) {
        String ip = ServletUtils.getClientIP(request);
        String ua = request.getHeader("user-agent");
        if (StrUtil.isNotBlank(ip)) {
            return ip + ua;
        }
        return request.getRemoteAddr() + ua;
    }
}
