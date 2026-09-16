package com.basicframework.module.infra.service.config;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.dal.dataobject.config.ConfigDO;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 参数配置 Service 接口
 *
 */
public interface ConfigService {

    /**
     * 创建参数配置
     *
     * @param config 创建信息
     * @return 配置编号
     */
    Long createConfig(ConfigDO config);

    /**
     * 更新参数配置
     *
     * @param config 更新信息
     */
    void updateConfig(ConfigDO config);

    /**
     * 删除参数配置
     *
     * @param id 配置编号
     */
    void deleteConfig(Long id);

    /**
     * 批量删除参数配置
     *
     * @param ids 配置编号列表
     */
    void deleteConfigList(List<Long> ids);

    /**
     * 获得参数配置
     *
     * @param id 配置编号
     * @return 参数配置
     */
    ConfigDO getConfig(Long id);

    /**
     * 根据参数键，获得参数配置
     *
     * @param key 配置键
     * @return 参数配置
     */
    ConfigDO getConfigByKey(String key);

    /**
     * 获得参数配置分页列表
     *
     * @param pageParam  分页参数
     * @param name       数据源名称，模糊匹配
     * @param key        参数键名，模糊匹配
     * @param type       参数类型
     * @param createTime 创建时间区间
     * @return 分页列表
     */
    PageResult<ConfigDO> getConfigPage(
            PageParam pageParam, String name, String key, Integer type, LocalDateTime[] createTime);
}
