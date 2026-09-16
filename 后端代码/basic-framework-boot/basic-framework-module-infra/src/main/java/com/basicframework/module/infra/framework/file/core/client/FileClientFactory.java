package com.basicframework.module.infra.framework.file.core.client;

import com.basicframework.module.infra.framework.file.core.enums.FileStorageEnum;

/** 文件客户端的工厂接口，用于获取和创建文件客户端 */
public interface FileClientFactory {

    /**
     * 获得文件客户端
     *
     * @param configId 配置编号
     * @return 文件客户端
     */
    FileClient getFileClient(Long configId);

    /**
     * 创建文件客户端
     *
     * @param configId 配置编号
     * @param storage 存储器的枚举 {@link FileStorageEnum}
     * @param config 文件配置
     */
    <Config extends FileClientConfig> void createOrUpdateFileClient(Long configId, Integer storage, Config config);

    /**
     * 移除并关闭指定客户端。
     *
     * @param configId 配置编号
     */
    void removeFileClient(Long configId);
}
