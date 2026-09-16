package com.basicframework.module.system.controller.admin.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anji.captcha.model.common.ResponseModel;
import com.anji.captcha.model.vo.CaptchaVO;
import com.anji.captcha.service.CaptchaService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@link CaptchaController} 协议例外钉住测试。
 *
 * 该端点是 ADR 0003 批准的例外：直接透传 anji-captcha 的 {@link ResponseModel}（repCode 协议），
 * 不包装为 CommonResult；前端 Verification 组件依赖该协议字段。收敛前此测试不得删除。
 */
class CaptchaControllerTest {

    private final CaptchaService captchaService = mock(CaptchaService.class);
    private final CaptchaController controller = new CaptchaController(captchaService);

    @Test
    void get_passesThroughRawRepCodeResponseModel() {
        CaptchaVO request = new CaptchaVO();
        request.setCaptchaType("blockPuzzle");
        ResponseModel expected = ResponseModel.successData(Map.of("originalImage", Map.of("base64", "a")));
        when(captchaService.get(request)).thenReturn(expected);
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("127.0.0.1");

        ResponseModel actual = controller.get(request, httpRequest);

        assertThat(actual).isSameAs(expected);
        assertThat(actual.getRepCode()).isEqualTo("0000");
        verify(captchaService).get(request);
    }

    @Test
    void check_passesThroughRawRepCodeResponseModel() {
        CaptchaVO request = new CaptchaVO();
        request.setCaptchaType("blockPuzzle");
        ResponseModel expected = new ResponseModel();
        expected.setRepCode("6110");
        expected.setRepMsg("验证失败");
        when(captchaService.check(request)).thenReturn(expected);
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("127.0.0.1");

        ResponseModel actual = controller.check(request, httpRequest);

        assertThat(actual).isSameAs(expected);
        assertThat(actual.getRepCode()).isEqualTo("6110");
        verify(captchaService).check(request);
    }

    @Test
    void getRemoteId_fallsBackToRemoteAddrWhenClientIpUnresolvable() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("user-agent", "MockUA");
        assertThat(CaptchaController.getRemoteId(request)).isEqualTo("127.0.0.1MockUA");

        request.setRemoteAddr("");
        assertThat(CaptchaController.getRemoteId(request)).isEqualTo("MockUA");
    }
}
