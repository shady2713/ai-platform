package com.basicframework.module.infra.service.config;

import static com.basicframework.module.infra.enums.ErrorCodeConstants.CONFIG_CAN_NOT_DELETE_SYSTEM_TYPE;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.CONFIG_KEY_DUPLICATE;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.CONFIG_NOT_EXISTS;
import static com.basicframework.module.infra.testutil.ServiceExceptionAssert.assertServiceException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.dal.dataobject.config.ConfigDO;
import com.basicframework.module.infra.dal.mysql.config.ConfigMapper;
import com.basicframework.module.infra.enums.config.ConfigTypeEnum;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConfigServiceImplTest {

    @Mock
    private ConfigMapper configMapper;

    private ConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ConfigServiceImpl(configMapper);
    }

    @Test
    void createConfig_assignsCustomTypeAndReturnsGeneratedId() {
        ConfigDO config = config(null, "feature.enabled", null);
        when(configMapper.selectByKey("feature.enabled")).thenReturn(null);
        when(configMapper.insert(config)).thenAnswer(invocation -> {
            config.setId(7L);
            return 1;
        });

        assertThat(service.createConfig(config)).isEqualTo(7L);
        assertThat(config.getType()).isEqualTo(ConfigTypeEnum.CUSTOM.getType());
    }

    @Test
    void createAndUpdate_rejectDuplicateKeysButAllowTheSameRecord() {
        ConfigDO existing = config(8L, "duplicate.key", ConfigTypeEnum.CUSTOM.getType());
        when(configMapper.selectByKey("duplicate.key")).thenReturn(existing);
        when(configMapper.selectById(9L)).thenReturn(config(9L, "old.key", ConfigTypeEnum.CUSTOM.getType()));

        assertServiceException(
                CONFIG_KEY_DUPLICATE.getCode(), () -> service.createConfig(config(null, "duplicate.key", null)));
        assertServiceException(
                CONFIG_KEY_DUPLICATE.getCode(), () -> service.updateConfig(config(9L, "duplicate.key", null)));

        when(configMapper.selectById(8L)).thenReturn(existing);
        service.updateConfig(config(8L, "duplicate.key", null));
        verify(configMapper).updateById(config(8L, "duplicate.key", null));
    }

    @Test
    void updateAndDelete_missingConfigFailClosed() {
        when(configMapper.selectById(10L)).thenReturn(null);

        assertServiceException(CONFIG_NOT_EXISTS.getCode(), () -> service.updateConfig(config(10L, "missing", null)));
        assertServiceException(CONFIG_NOT_EXISTS.getCode(), () -> service.deleteConfig(10L));
        verify(configMapper, never()).deleteById(10L);
    }

    @Test
    void delete_rejectsSystemConfigAndDeletesCustomConfig() {
        when(configMapper.selectById(11L)).thenReturn(config(11L, "system", ConfigTypeEnum.SYSTEM.getType()));
        when(configMapper.selectById(12L)).thenReturn(config(12L, "custom", ConfigTypeEnum.CUSTOM.getType()));

        assertServiceException(CONFIG_CAN_NOT_DELETE_SYSTEM_TYPE.getCode(), () -> service.deleteConfig(11L));
        service.deleteConfig(12L);

        verify(configMapper).deleteById(12L);
    }

    @Test
    void batchDelete_rejectsAnySystemConfigBeforeDeletion() {
        when(configMapper.selectByIds(List.of(13L, 14L)))
                .thenReturn(List.of(
                        config(13L, "custom", ConfigTypeEnum.CUSTOM.getType()),
                        config(14L, "system", ConfigTypeEnum.SYSTEM.getType())));

        assertServiceException(
                CONFIG_CAN_NOT_DELETE_SYSTEM_TYPE.getCode(), () -> service.deleteConfigList(List.of(13L, 14L)));
        verify(configMapper, never()).deleteByIds(List.of(13L, 14L));
    }

    @Test
    void batchDelete_allCustomConfigs_deletesAllInOneCall() {
        when(configMapper.selectByIds(List.of(16L, 17L)))
                .thenReturn(List.of(
                        config(16L, "custom.a", ConfigTypeEnum.CUSTOM.getType()),
                        config(17L, "custom.b", ConfigTypeEnum.CUSTOM.getType())));

        service.deleteConfigList(List.of(16L, 17L));

        verify(configMapper).deleteByIds(List.of(16L, 17L));
    }

    @Test
    void reads_delegateWithoutChangingQueryContracts() {
        ConfigDO config = config(15L, "read.key", ConfigTypeEnum.CUSTOM.getType());
        PageParam pageParam = new PageParam();
        LocalDateTime[] createTime = {LocalDateTime.now().minusDays(1), LocalDateTime.now()};
        PageResult<ConfigDO> page = new PageResult<>(List.of(config), 1L);
        when(configMapper.selectById(15L)).thenReturn(config);
        when(configMapper.selectByKey("read.key")).thenReturn(config);
        when(configMapper.selectPage(pageParam, "read", "read.key", 2, createTime))
                .thenReturn(page);

        assertThat(service.getConfig(15L)).isSameAs(config);
        assertThat(service.getConfigByKey("read.key")).isSameAs(config);
        assertThat(service.getConfigPage(pageParam, "read", "read.key", 2, createTime))
                .isSameAs(page);
        assertThat(service.validateConfigExists(null)).isNull();
    }

    private static ConfigDO config(Long id, String key, Integer type) {
        return new ConfigDO().setId(id).setConfigKey(key).setType(type);
    }
}
