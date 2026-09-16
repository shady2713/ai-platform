package com.basicframework.module.system.framework.sms.core.client.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.basicframework.framework.common.core.KeyValue;
import com.basicframework.module.system.framework.sms.core.client.dto.SmsReceiveRespDTO;
import com.basicframework.module.system.framework.sms.core.client.dto.SmsSendRespDTO;
import com.basicframework.module.system.framework.sms.core.client.dto.SmsTemplateRespDTO;
import com.basicframework.module.system.framework.sms.core.enums.SmsTemplateAuditStatusEnum;
import com.basicframework.module.system.framework.sms.core.property.SmsChannelProperties;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AliyunSmsClientTest {

    @Test
    void constructorRejectsIncompleteCredentials() {
        SmsChannelProperties properties = properties();
        properties.setApiKey("");

        assertThatIllegalArgumentException().isThrownBy(() -> new AliyunSmsClient(properties));

        properties.setApiKey("access-key");
        properties.setApiSecret("");
        assertThatIllegalArgumentException().isThrownBy(() -> new AliyunSmsClient(properties));
    }

    @Test
    void sendSmsBuildsSignedRequestAndMapsProviderResponse() {
        AtomicReference<HttpRequest> request = new AtomicReference<>();
        AliyunSmsClient client = new AliyunSmsClient(properties(), (url, headers, body) -> {
            request.set(new HttpRequest(url, headers, body));
            return """
                    {"Code":"OK","BizId":"biz-1","RequestId":"request-1","Message":"sent"}
                    """;
        });

        SmsSendRespDTO response = client.sendSms(12L, "13900000000", "SMS_100", List.of(new KeyValue<>("name", "张三")));

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getSerialNo()).isEqualTo("biz-1");
        assertThat(response.getApiRequestId()).isEqualTo("request-1");
        assertThat(response.getApiCode()).isEqualTo("OK");
        assertThat(response.getApiMsg()).isEqualTo("sent");
        assertThat(request.get().url())
                .startsWith("https://dysmsapi.aliyuncs.com?")
                .contains("OutId=12", "PhoneNumbers=13900000000", "TemplateCode=SMS_100")
                .contains("TemplateParam=%7B%22name%22%3A%22%E5%BC%A0%E4%B8%89%22%7D");
        assertThat(request.get().headers())
                .containsEntry("host", "dysmsapi.aliyuncs.com")
                .containsEntry("x-acs-action", "SendSms")
                .containsKey("Authorization");
        assertThat(request.get().headers().get("Authorization"))
                .startsWith("ACS3-HMAC-SHA256 Credential=access-key")
                .doesNotContain("access-secret");
        assertThat(request.get().body()).isEmpty();
    }

    @Test
    void getSmsTemplateMapsSuccessAndRejectsProviderFailure() {
        AliyunSmsClient successClient = new AliyunSmsClient(
                properties(),
                (url, headers, body) ->
                        """
                {"Code":"OK","TemplateCode":"SMS_100","TemplateContent":"hello ${name}",
                 "TemplateStatus":1,"Reason":"approved","RequestId":"request-1"}
                """);

        SmsTemplateRespDTO template = successClient.getSmsTemplate("SMS_100");

        assertThat(template.getId()).isEqualTo("SMS_100");
        assertThat(template.getContent()).isEqualTo("hello ${name}");
        assertThat(template.getAuditStatus()).isEqualTo(SmsTemplateAuditStatusEnum.SUCCESS.getStatus());
        assertThat(template.getAuditReason()).isEqualTo("approved");

        AliyunSmsClient failureClient = new AliyunSmsClient(
                properties(),
                (url, headers, body) ->
                        """
                {"Code":"InvalidTemplate","TemplateStatus":2,"RequestId":"request-2"}
                """);
        assertThat(failureClient.getSmsTemplate("SMS_404")).isNull();
    }

    @Test
    void parseReceiveStatusMapsProviderCorrelationFields() {
        AliyunSmsClient client = new AliyunSmsClient(properties(), (url, headers, body) -> "{}");

        List<SmsReceiveRespDTO> receipts = client.parseSmsReceiveStatus(
                """
                [{"success":true,"err_code":"DELIVRD","err_msg":"delivered",
                  "phone_number":"13900000000","report_time":"2026-08-30 10:00:00",
                  "biz_id":"biz-1","out_id":12}]
                """);

        assertThat(receipts).singleElement().satisfies(receipt -> {
            assertThat(receipt.getSuccess()).isTrue();
            assertThat(receipt.getErrorCode()).isEqualTo("DELIVRD");
            assertThat(receipt.getErrorMsg()).isEqualTo("delivered");
            assertThat(receipt.getMobile()).isEqualTo("13900000000");
            assertThat(receipt.getReceiveTime()).isNotNull();
            assertThat(receipt.getSerialNo()).isEqualTo("biz-1");
            assertThat(receipt.getLogId()).isEqualTo(12L);
        });
    }

    @Test
    void auditStatusConversionIsClosedForUnknownProviderValues() {
        AliyunSmsClient client = new AliyunSmsClient(properties(), (url, headers, body) -> "{}");

        assertThat(client.convertSmsTemplateAuditStatus(0)).isEqualTo(SmsTemplateAuditStatusEnum.CHECKING.getStatus());
        assertThat(client.convertSmsTemplateAuditStatus(1)).isEqualTo(SmsTemplateAuditStatusEnum.SUCCESS.getStatus());
        assertThat(client.convertSmsTemplateAuditStatus(2)).isEqualTo(SmsTemplateAuditStatusEnum.FAIL.getStatus());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> client.convertSmsTemplateAuditStatus(3))
                .withMessageContaining("未知审核状态");
    }

    private static SmsChannelProperties properties() {
        SmsChannelProperties properties = new SmsChannelProperties();
        properties.setId(1L);
        properties.setCode("aliyun");
        properties.setSignature("signature");
        properties.setApiKey("access-key");
        properties.setApiSecret("access-secret");
        return properties;
    }

    private record HttpRequest(String url, Map<String, String> headers, String body) {}
}
