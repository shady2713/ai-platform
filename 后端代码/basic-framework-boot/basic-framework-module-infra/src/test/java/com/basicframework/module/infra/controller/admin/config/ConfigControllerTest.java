package com.basicframework.module.infra.controller.admin.config;

import static com.basicframework.module.infra.enums.ErrorCodeConstants.CONFIG_GET_VALUE_ERROR_IF_VISIBLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.controller.admin.config.vo.ConfigPageReqVO;
import com.basicframework.module.infra.controller.admin.config.vo.ConfigSaveReqVO;
import com.basicframework.module.infra.dal.dataobject.config.ConfigDO;
import com.basicframework.module.infra.service.config.ConfigService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConfigControllerTest {

    @Mock
    private ConfigService configService;

    private ConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new ConfigController(configService);
    }

    @Test
    void mutationEndpoints_mapRequestsAndDelegateToService() {
        ConfigSaveReqVO request = request();
        when(configService.createConfig(any(ConfigDO.class))).thenReturn(9L);

        assertThat(controller.createConfig(request).getData()).isEqualTo(9L);
        assertThat(controller.updateConfig(request).getData()).isTrue();
        assertThat(controller.deleteConfig(9L).getData()).isTrue();
        assertThat(controller.deleteConfigList(List.of(9L, 10L)).getData()).isTrue();

        ArgumentCaptor<ConfigDO> createCaptor = ArgumentCaptor.forClass(ConfigDO.class);
        verify(configService).createConfig(createCaptor.capture());
        assertThat(createCaptor.getValue().getConfigKey()).isEqualTo("feature.enabled");
        verify(configService).updateConfig(any(ConfigDO.class));
        verify(configService).deleteConfig(9L);
        verify(configService).deleteConfigList(List.of(9L, 10L));
    }

    @Test
    void readEndpoints_redactInvisibleValuesAcrossDetailAndPage() {
        ConfigDO invisible = config(11L, false, "private-value");
        ConfigDO visible = config(12L, true, "public-value");
        ConfigPageReqVO pageRequest = new ConfigPageReqVO();
        when(configService.getConfig(11L)).thenReturn(invisible);
        when(configService.getConfigPage(pageRequest, null, null, null, null))
                .thenReturn(new PageResult<>(List.of(invisible, visible), 2L));

        assertThat(controller.getConfig(11L).getData().getValue()).isNull();
        assertThat(controller.getConfigPage(pageRequest).getData().getList())
                .extracting(response -> response.getValue())
                .containsExactly(null, "public-value");
    }

    @Test
    void getByKey_returnsOnlyExplicitlyVisibleValues() {
        when(configService.getConfigByKey("missing")).thenReturn(null);
        when(configService.getConfigByKey("private")).thenReturn(config(13L, false, "private-value"));
        when(configService.getConfigByKey("invalid-visible")).thenReturn(config(15L, null, "must-not-leak"));
        when(configService.getConfigByKey("public")).thenReturn(config(14L, true, "public-value"));

        assertThat(controller.getConfigKey("missing").getData()).isNull();
        assertThatThrownBy(() -> controller.getConfigKey("private"))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(CONFIG_GET_VALUE_ERROR_IF_VISIBLE.getCode());
        assertThatThrownBy(() -> controller.getConfigKey("invalid-visible"))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(CONFIG_GET_VALUE_ERROR_IF_VISIBLE.getCode());
        assertThat(controller.getConfigKey("public").getData()).isEqualTo("public-value");
    }

    private static ConfigSaveReqVO request() {
        ConfigSaveReqVO request = new ConfigSaveReqVO();
        request.setId(9L);
        request.setCategory("business");
        request.setName("feature flag");
        request.setKey("feature.enabled");
        request.setValue("true");
        request.setVisible(true);
        return request;
    }

    private static ConfigDO config(Long id, Boolean visible, String value) {
        return new ConfigDO()
                .setId(id)
                .setConfigKey("config." + id)
                .setName("config " + id)
                .setVisible(visible)
                .setValue(value);
    }
}
