package com.basicframework.module.system.controller.admin.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.controller.admin.sms.vo.channel.SmsChannelPageReqVO;
import com.basicframework.module.system.controller.admin.sms.vo.channel.SmsChannelRespVO;
import com.basicframework.module.system.controller.admin.sms.vo.channel.SmsChannelSaveReqVO;
import com.basicframework.module.system.dal.dataobject.sms.SmsChannelDO;
import com.basicframework.module.system.service.sms.SmsChannelService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmsChannelControllerTest {

    private final SmsChannelService service = mock(SmsChannelService.class);
    private final SmsChannelController controller = new SmsChannelController(service);

    @Test
    void mutationsMapRequestsWithoutPersistingRequestSecret() {
        SmsChannelSaveReqVO request = saveRequest();
        when(service.createSmsChannel(any(SmsChannelDO.class))).thenReturn(9L);

        assertThat(controller.createSmsChannel(request).getData()).isEqualTo(9L);
        assertThat(controller.updateSmsChannel(request).getData()).isTrue();
        assertThat(controller.deleteSmsChannel(7L).getData()).isTrue();
        assertThat(controller.deleteSmsChannelList(List.of(7L, 8L)).getData()).isTrue();

        verify(service).updateSmsChannel(any(SmsChannelDO.class));
        verify(service).deleteSmsChannel(7L);
        verify(service).deleteSmsChannelList(List.of(7L, 8L));
    }

    @Test
    void getSmsChannel_returnsOnlySecretConfigurationState() {
        when(service.getSmsChannel(1L))
                .thenReturn(new SmsChannelDO()
                        .setId(1L)
                        .setApiKeyCiphertext("v1.encrypted.key")
                        .setApiSecretCiphertext("v1.encrypted.value"));

        SmsChannelRespVO response = controller.getSmsChannel(1L).getData();

        assertThat(response.getApiKeyConfigured()).isTrue();
        assertThat(response.getApiSecretConfigured()).isTrue();
        assertThat(response.toString()).doesNotContain("provider-account", "encrypted");
        assertThat(controller.getSmsChannel(2L).getData()).isNull();
    }

    @Test
    void pageAndSimpleListPreserveFiltersAndSortChannelsById() {
        SmsChannelPageReqVO request = new SmsChannelPageReqVO();
        request.setSignature("框架");
        request.setCode("ALIYUN");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        request.setCreateTime(new LocalDateTime[] {LocalDateTime.now().minusDays(1), LocalDateTime.now()});
        SmsChannelDO channel = new SmsChannelDO().setId(1L).setApiKeyCiphertext("v1.encrypted.key");
        when(service.getSmsChannelPage(
                        same(request), same("框架"), same("ALIYUN"), same(0), same(request.getCreateTime())))
                .thenReturn(new PageResult<>(List.of(channel), 1L));
        when(service.getSmsChannelList())
                .thenReturn(new ArrayList<>(List.of(new SmsChannelDO().setId(2L), new SmsChannelDO().setId(1L))));

        assertThat(controller.getSmsChannelPage(request).getData().getList())
                .singleElement()
                .extracting(SmsChannelRespVO::getApiKeyConfigured)
                .isEqualTo(true);
        assertThat(controller.getSimpleSmsChannelList().getData())
                .extracting("id")
                .containsExactly(1L, 2L);
        verify(service)
                .getSmsChannelPage(same(request), same("框架"), same("ALIYUN"), same(0), same(request.getCreateTime()));
    }

    private static SmsChannelSaveReqVO saveRequest() {
        SmsChannelSaveReqVO request = new SmsChannelSaveReqVO();
        request.setId(7L);
        request.setSignature("基础框架");
        request.setCode("ALIYUN");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        request.setApiKey("provider-account");
        request.setApiSecret("provider-secret");
        return request;
    }
}
