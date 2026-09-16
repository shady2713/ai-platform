package com.basicframework.module.infra.service.config;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.*;
import static com.basicframework.module.infra.enums.LogRecordConstants.*;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.dal.dataobject.config.ConfigDO;
import com.basicframework.module.infra.dal.mysql.config.ConfigMapper;
import com.basicframework.module.infra.enums.config.ConfigTypeEnum;
import com.google.common.annotations.VisibleForTesting;
import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.starter.annotation.LogRecord;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * 参数配置 Service 实现类
 */
@Service
@Validated
@RequiredArgsConstructor
public class ConfigServiceImpl implements ConfigService {

    private final ConfigMapper configMapper;

    @Override
    @LogRecord(
            type = INFRA_CONFIG_TYPE,
            subType = INFRA_CONFIG_CREATE_SUB_TYPE,
            bizNo = "{{#config.id}}",
            success = INFRA_CONFIG_CREATE_SUCCESS)
    public Long createConfig(ConfigDO config) {
        // 校验参数配置 key 的唯一性
        validateConfigKeyUnique(null, config.getConfigKey());

        // 插入参数配置
        config.setType(ConfigTypeEnum.CUSTOM.getType());
        configMapper.insert(config);

        // 记录操作日志上下文
        LogRecordContext.putVariable("config", config);
        return config.getId();
    }

    @Override
    @LogRecord(
            type = INFRA_CONFIG_TYPE,
            subType = INFRA_CONFIG_UPDATE_SUB_TYPE,
            bizNo = "{{#updateObj.id}}",
            success = INFRA_CONFIG_UPDATE_SUCCESS)
    public void updateConfig(ConfigDO updateObj) {
        // 校验自己存在
        ConfigDO config = validateConfigExists(updateObj.getId());
        // 校验参数配置 key 的唯一性
        validateConfigKeyUnique(updateObj.getId(), updateObj.getConfigKey());

        // 更新参数配置
        configMapper.updateById(updateObj);

        // 记录操作日志上下文
        LogRecordContext.putVariable("config", config);
    }

    @Override
    @LogRecord(
            type = INFRA_CONFIG_TYPE,
            subType = INFRA_CONFIG_DELETE_SUB_TYPE,
            bizNo = "{{#id}}",
            success = INFRA_CONFIG_DELETE_SUCCESS)
    public void deleteConfig(Long id) {
        // 校验配置存在
        ConfigDO config = validateConfigExists(id);
        // 内置配置，不允许删除
        if (ConfigTypeEnum.SYSTEM.getType().equals(config.getType())) {
            throw exception(CONFIG_CAN_NOT_DELETE_SYSTEM_TYPE);
        }
        // 删除
        configMapper.deleteById(id);

        // 记录操作日志上下文
        LogRecordContext.putVariable("config", config);
    }

    @Override
    public void deleteConfigList(List<Long> ids) {
        // 校验是否有内置配置
        List<ConfigDO> configs = configMapper.selectByIds(ids);
        configs.forEach(config -> {
            if (ConfigTypeEnum.SYSTEM.getType().equals(config.getType())) {
                throw exception(CONFIG_CAN_NOT_DELETE_SYSTEM_TYPE);
            }
        });

        // 批量删除
        configMapper.deleteByIds(ids);
    }

    @Override
    public ConfigDO getConfig(Long id) {
        return configMapper.selectById(id);
    }

    @Override
    public ConfigDO getConfigByKey(String key) {
        return configMapper.selectByKey(key);
    }

    @Override
    public PageResult<ConfigDO> getConfigPage(
            PageParam pageParam, String name, String key, Integer type, LocalDateTime[] createTime) {
        return configMapper.selectPage(pageParam, name, key, type, createTime);
    }

    @VisibleForTesting
    public ConfigDO validateConfigExists(Long id) {
        if (id == null) {
            return null;
        }
        ConfigDO config = configMapper.selectById(id);
        if (config == null) {
            throw exception(CONFIG_NOT_EXISTS);
        }
        return config;
    }

    @VisibleForTesting
    public void validateConfigKeyUnique(Long id, String key) {
        ConfigDO config = configMapper.selectByKey(key);
        if (config == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的参数配置
        if (id == null) {
            throw exception(CONFIG_KEY_DUPLICATE);
        }
        if (!config.getId().equals(id)) {
            throw exception(CONFIG_KEY_DUPLICATE);
        }
    }
}
