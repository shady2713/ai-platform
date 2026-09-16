package com.basicframework.module.system.controller.admin.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.excel.core.util.ExcelUtils;
import com.basicframework.module.system.controller.admin.sms.vo.template.SmsTemplatePageReqVO;
import com.basicframework.module.system.controller.admin.sms.vo.template.SmsTemplateRespVO;
import com.basicframework.module.system.controller.admin.sms.vo.template.SmsTemplateSaveReqVO;
import com.basicframework.module.system.controller.admin.sms.vo.template.SmsTemplateSendReqVO;
import com.basicframework.module.system.dal.dataobject.sms.SmsTemplateDO;
import com.basicframework.module.system.dal.mysql.sms.SmsTemplateQuery;
import com.basicframework.module.system.service.sms.SmsSendService;
import com.basicframework.module.system.service.sms.SmsTemplateService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletResponse;

class SmsTemplateControllerTest {

    private final SmsTemplateService templateService = mock(SmsTemplateService.class);
    private final SmsSendService sendService = mock(SmsSendService.class);
    private final SmsTemplateController controller = new SmsTemplateController(templateService, sendService);

    @Test
    void mutationEndpointsMapRequestsAndDelegateExactTargets() {
        SmsTemplateSaveReqVO request = saveRequest();
        when(templateService.createSmsTemplate(any(SmsTemplateDO.class))).thenReturn(9L);

        assertThat(controller.createSmsTemplate(request).getData()).isEqualTo(9L);
        assertThat(controller.updateSmsTemplate(request).getData()).isTrue();
        assertThat(controller.deleteSmsTemplate(7L).getData()).isTrue();
        assertThat(controller.deleteSmsTemplateList(List.of(7L, 8L)).getData()).isTrue();

        ArgumentCaptor<SmsTemplateDO> templateCaptor = ArgumentCaptor.forClass(SmsTemplateDO.class);
        verify(templateService).createSmsTemplate(templateCaptor.capture());
        assertThat(templateCaptor.getValue())
                .extracting(SmsTemplateDO::getId, SmsTemplateDO::getCode, SmsTemplateDO::getChannelId)
                .containsExactly(7L, "LOGIN", 3L);
        verify(templateService).updateSmsTemplate(any(SmsTemplateDO.class));
        verify(templateService).deleteSmsTemplate(7L);
        verify(templateService).deleteSmsTemplateList(List.of(7L, 8L));
    }

    @Test
    void getAndPageReturnMappedDataAndPreserveEveryFilter() {
        SmsTemplateDO template = template();
        SmsTemplatePageReqVO request = pageRequest();
        when(templateService.getSmsTemplate(7L)).thenReturn(template);
        when(templateService.getSmsTemplatePage(any(), any())).thenReturn(new PageResult<>(List.of(template), 1L));

        SmsTemplateRespVO detail = controller.getSmsTemplate(7L).getData();
        PageResult<SmsTemplateRespVO> page =
                controller.getSmsTemplatePage(request).getData();

        assertThat(detail.getCode()).isEqualTo("LOGIN");
        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getList()).extracting(SmsTemplateRespVO::getId).containsExactly(7L);
        ArgumentCaptor<SmsTemplateQuery> queryCaptor = ArgumentCaptor.forClass(SmsTemplateQuery.class);
        verify(templateService).getSmsTemplatePage(same(request), queryCaptor.capture());
        assertQueryMatchesRequest(queryCaptor.getValue(), request);
    }

    @Test
    void exportAppliesHardLimitAndWritesThePublishedSpreadsheet() throws Exception {
        SmsTemplatePageReqVO request = pageRequest();
        SmsTemplateDO template = template();
        List<SmsTemplateRespVO> expectedRows = BeanUtils.toBean(List.of(template), SmsTemplateRespVO.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(templateService.getSmsTemplatePage(any(), any())).thenReturn(new PageResult<>(List.of(template), 1L));

        try (MockedStatic<ExcelUtils> excelUtils = mockStatic(ExcelUtils.class)) {
            controller.export(response, request);

            excelUtils.verify(
                    () -> ExcelUtils.write(response, "短信模板.xls", "数据", SmsTemplateRespVO.class, expectedRows));
        }

        assertThat(request.getPageSize()).isEqualTo(PageParam.EXPORT_MAX_PAGE_SIZE);
        ArgumentCaptor<SmsTemplateQuery> queryCaptor = ArgumentCaptor.forClass(SmsTemplateQuery.class);
        verify(templateService).getSmsTemplatePage(same(request), queryCaptor.capture());
        assertQueryMatchesRequest(queryCaptor.getValue(), request);
    }

    @Test
    void sendSmsUsesTheAdminChannelWithoutInventingAUserId() {
        SmsTemplateSendReqVO request = new SmsTemplateSendReqVO();
        request.setMobile("13812345678");
        request.setTemplateCode("LOGIN");
        request.setTemplateParams(Map.of("code", "123456"));
        when(sendService.sendSingleSmsToAdmin("13812345678", null, "LOGIN", request.getTemplateParams()))
                .thenReturn(21L);

        assertThat(controller.sendSms(request).getData()).isEqualTo(21L);

        verify(sendService).sendSingleSmsToAdmin("13812345678", null, "LOGIN", request.getTemplateParams());
    }

    private static SmsTemplateSaveReqVO saveRequest() {
        SmsTemplateSaveReqVO request = new SmsTemplateSaveReqVO();
        request.setId(7L);
        request.setType(1);
        request.setStatus(0);
        request.setCode("LOGIN");
        request.setName("登录验证码");
        request.setContent("验证码 {code}");
        request.setApiTemplateId("SMS_100");
        request.setChannelId(3L);
        return request;
    }

    private static SmsTemplateDO template() {
        return new SmsTemplateDO()
                .setId(7L)
                .setType(1)
                .setStatus(0)
                .setCode("LOGIN")
                .setName("登录验证码")
                .setContent("验证码 {code}")
                .setApiTemplateId("SMS_100")
                .setChannelId(3L);
    }

    private static SmsTemplatePageReqVO pageRequest() {
        SmsTemplatePageReqVO request = new SmsTemplatePageReqVO();
        request.setType(1);
        request.setStatus(0);
        request.setCode("LOGIN");
        request.setContent("验证码");
        request.setApiTemplateId("SMS_100");
        request.setChannelId(3L);
        request.setCreateTime(new LocalDateTime[] {LocalDateTime.now().minusDays(1), LocalDateTime.now()});
        return request;
    }

    private static void assertQueryMatchesRequest(SmsTemplateQuery query, SmsTemplatePageReqVO request) {
        assertThat(query)
                .extracting(
                        SmsTemplateQuery::getType,
                        SmsTemplateQuery::getStatus,
                        SmsTemplateQuery::getCode,
                        SmsTemplateQuery::getContent,
                        SmsTemplateQuery::getApiTemplateId,
                        SmsTemplateQuery::getChannelId)
                .containsExactly(1, 0, "LOGIN", "验证码", "SMS_100", 3L);
        assertThat(query.getCreateTime()).containsExactly(request.getCreateTime());
    }
}
