package com.basicframework.module.infra.framework.file.core.client;

import static com.basicframework.framework.common.util.exception.SafeExceptionLogUtils.format;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ReflectUtil;
import com.basicframework.module.infra.dal.mysql.file.FileContentMapper;
import com.basicframework.module.infra.framework.file.core.client.db.DBFileClient;
import com.basicframework.module.infra.framework.file.core.client.db.DBFileClientConfig;
import com.basicframework.module.infra.framework.file.core.enums.FileStorageEnum;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;

/**
 * 文件客户端的工厂实现类
 *
 */
@Slf4j
public class FileClientFactoryImpl implements FileClientFactory, DisposableBean {

    private final FileContentMapper fileContentMapper;

    /**
     * 文件客户端 Map
     * key：配置编号
     */
    private final ConcurrentMap<Long, AbstractFileClient<?>> clients = new ConcurrentHashMap<>();

    public FileClientFactoryImpl(FileContentMapper fileContentMapper) {
        this.fileContentMapper = fileContentMapper;
    }

    @Override
    public FileClient getFileClient(Long configId) {
        AbstractFileClient<?> client = clients.get(configId);
        if (client == null) {
            log.error("[getFileClient][配置编号({}) 找不到客户端]", configId);
        }
        return client;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <Config extends FileClientConfig> void createOrUpdateFileClient(
            Long configId, Integer storage, Config config) {
        Assert.notNull(configId, "文件配置编号不能为空");
        Assert.notNull(config, "文件客户端配置不能为空");
        FileStorageEnum storageEnum = requireStorage(storage);
        clients.compute(configId, (id, existing) -> {
            if (existing != null && storageEnum.getClientClass().isInstance(existing)) {
                ((AbstractFileClient<Config>) existing).refresh(config);
                return existing;
            }
            AbstractFileClient<Config> replacement = createFileClient(id, storageEnum, config);
            replacement.init();
            closeQuietly(existing, "replace");
            return replacement;
        });
    }

    @SuppressWarnings("unchecked")
    private <Config extends FileClientConfig> AbstractFileClient<Config> createFileClient(
            Long configId, FileStorageEnum storageEnum, Config config) {
        if (storageEnum == FileStorageEnum.DB) {
            return (AbstractFileClient<Config>)
                    new DBFileClient(configId, (DBFileClientConfig) config, fileContentMapper);
        }
        return (AbstractFileClient<Config>) ReflectUtil.newInstance(storageEnum.getClientClass(), configId, config);
    }

    private static FileStorageEnum requireStorage(Integer storage) {
        FileStorageEnum storageEnum = FileStorageEnum.getByStorage(storage);
        Assert.notNull(storageEnum, String.format("文件存储类型(%s) 不存在", storage));
        return storageEnum;
    }

    @Override
    public void removeFileClient(Long configId) {
        if (configId == null) {
            return;
        }
        clients.computeIfPresent(configId, (id, client) -> {
            closeQuietly(client, "remove");
            return null;
        });
    }

    @Override
    public void destroy() {
        clients.forEach((id, client) -> closeQuietly(client, "shutdown"));
        clients.clear();
    }

    private static void closeQuietly(FileClient client, String reason) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (RuntimeException exception) {
            log.error(
                    "[closeQuietly][配置编号({}) 原因({}) 关闭文件客户端失败，stackTrace({})]",
                    client.getId(),
                    reason,
                    format(exception));
        }
    }
}
