package com.basicframework.module.infra.service.file;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import com.basicframework.module.infra.framework.file.core.client.FileClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 文件配置 Service 接口
 *
 */
public interface FileConfigService {

    /**
     * 创建文件配置
     *
     * @param fileConfig 配置信息，config 字段由 configMap 解析填充
     * @param configMap  存储配置，动态参数，按 storage 解析为对应的 {@link com.basicframework.module.infra.framework.file.core.client.FileClientConfig}
     * @return 编号
     */
    Long createFileConfig(FileConfigDO fileConfig, Map<String, Object> configMap);

    /**
     * 更新文件配置
     *
     * @param fileConfig 更新信息，config 字段由 configMap 解析填充
     * @param configMap  存储配置，动态参数，按 storage 解析为对应的 {@link com.basicframework.module.infra.framework.file.core.client.FileClientConfig}
     */
    void updateFileConfig(FileConfigDO fileConfig, Map<String, Object> configMap);

    /**
     * 更新文件配置为 Master
     *
     * @param id 编号
     */
    void updateFileConfigMaster(Long id);

    /**
     * 删除文件配置
     *
     * @param id 编号
     */
    void deleteFileConfig(Long id);

    /**
     * 批量删除文件配置
     *
     * @param ids 编号列表
     */
    void deleteFileConfigList(List<Long> ids);

    /**
     * 获得文件配置
     *
     * @param id 编号
     * @return 文件配置
     */
    FileConfigDO getFileConfig(Long id);

    /**
     * 获得文件配置分页
     *
     * @param pageParam  分页参数
     * @param name       配置名，模糊匹配
     * @param storage    存储器
     * @param createTime 创建时间区间
     * @return 文件配置分页
     */
    PageResult<FileConfigDO> getFileConfigPage(
            PageParam pageParam, String name, Integer storage, LocalDateTime[] createTime);

    /**
     * 测试文件配置是否正确，通过上传文件
     *
     * @param id 编号
     * @return 文件 URL
     */
    String testFileConfig(Long id) throws Exception;

    /**
     * 获得指定编号的文件客户端
     *
     * @param id 配置编号
     * @return 文件客户端；配置不存在时返回 {@code null}
     */
    FileClient getFileClient(Long id);

    /**
     * 获得 Master 文件客户端
     *
     * @return 文件客户端；未配置 Master 时返回 {@code null}
     */
    FileClient getMasterFileClient();

    /**
     * 获得指定配置的文件客户端，并在当前事务内持有配置共享锁。
     *
     * <p>仅供即将持久化该配置引用的写路径使用；调用方必须开启事务并在事务内完成引用写入。
     *
     * @param id 配置编号
     * @return 文件客户端；配置不存在时返回 {@code null}
     */
    FileClient getFileClientForReferenceWrite(Long id);

    /**
     * 获得 Master 文件客户端，并在当前事务内持有配置共享锁。
     *
     * <p>仅供即将持久化该配置引用的写路径使用；调用方必须开启事务并在事务内完成引用写入。
     *
     * @return 文件客户端；未配置 Master 时返回 {@code null}
     */
    FileClient getMasterFileClientForReferenceWrite();
}
