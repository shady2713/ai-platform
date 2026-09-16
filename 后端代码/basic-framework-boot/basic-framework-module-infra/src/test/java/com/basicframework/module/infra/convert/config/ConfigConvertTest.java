package com.basicframework.module.infra.convert.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.controller.admin.config.vo.ConfigRespVO;
import com.basicframework.module.infra.controller.admin.config.vo.ConfigSaveReqVO;
import com.basicframework.module.infra.dal.dataobject.config.ConfigDO;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigConvertTest {

    @Test
    void invisibleValues_areRedactedAcrossSingleListAndPageResponses() {
        ConfigDO invisible = config(1L, false, "private-value");
        ConfigDO visible = config(2L, true, "public-value");

        ConfigRespVO single = ConfigConvert.INSTANCE.convert(invisible);
        List<ConfigRespVO> list = ConfigConvert.INSTANCE.convertList(List.of(invisible, visible));
        PageResult<ConfigRespVO> page =
                ConfigConvert.INSTANCE.convertPage(new PageResult<>(List.of(invisible, visible), 2L));

        assertThat(single.getValue()).isNull();
        assertThat(list).extracting(ConfigRespVO::getValue).containsExactly(null, "public-value");
        assertThat(page.getTotal()).isEqualTo(2L);
        assertThat(page.getList()).extracting(ConfigRespVO::getValue).containsExactly(null, "public-value");
    }

    @Test
    void saveRequest_mapsExternalKeyToPersistenceConfigKey() {
        ConfigSaveReqVO request = new ConfigSaveReqVO();
        request.setId(3L);
        request.setCategory("business");
        request.setName("feature flag");
        request.setKey("feature.enabled");
        request.setValue("true");
        request.setVisible(true);

        ConfigDO config = ConfigConvert.INSTANCE.convert(request);

        assertThat(config)
                .extracting(
                        ConfigDO::getId,
                        ConfigDO::getCategory,
                        ConfigDO::getName,
                        ConfigDO::getConfigKey,
                        ConfigDO::getValue,
                        ConfigDO::getVisible)
                .containsExactly(3L, "business", "feature flag", "feature.enabled", "true", true);
        assertThat(config.getType()).isNull();
    }

    @Test
    void nullInputs_remainNull() {
        assertThat(ConfigConvert.INSTANCE.convert((ConfigDO) null)).isNull();
        assertThat(ConfigConvert.INSTANCE.convert((ConfigSaveReqVO) null)).isNull();
        assertThat(ConfigConvert.INSTANCE.convertList(null)).isNull();
        assertThat(ConfigConvert.INSTANCE.convertPage(null)).isNull();
    }

    private static ConfigDO config(Long id, boolean visible, String value) {
        return new ConfigDO()
                .setId(id)
                .setConfigKey("config." + id)
                .setName("config " + id)
                .setValue(value)
                .setVisible(visible);
    }
}
