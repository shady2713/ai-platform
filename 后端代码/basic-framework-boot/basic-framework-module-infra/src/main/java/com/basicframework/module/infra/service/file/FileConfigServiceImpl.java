package com.basicframework.module.infra.service.file;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.util.cache.CacheUtils.buildAsyncReloadingCache;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_CONFIG_DELETE_FAIL_MASTER;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_CONFIG_IN_USE;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_CONFIG_NOT_EXISTS;
import static com.basicframework.module.infra.enums.LogRecordConstants.*;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.IdUtil;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.dal.mysql.file.FileConfigMapper;
import com.basicframework.module.infra.dal.mysql.file.FileContentMapper;
import com.basicframework.module.infra.dal.mysql.file.FileMapper;
import com.basicframework.module.infra.framework.file.core.client.FileClient;
import com.basicframework.module.infra.framework.file.core.client.FileClientConfig;
import com.basicframework.module.infra.framework.file.core.client.FileClientFactory;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.starter.annotation.LogRecord;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/**
 * 文件配置 Service 实现类
 *
 */
@Service
@Validated
@RequiredArgsConstructor
public class FileConfigServiceImpl implements FileConfigService {

    private static final Long CACHE_MASTER_ID = 0L;

    /**
     * {@link FileClient} 缓存，通过它异步刷新 fileClientFactory
     */
    @Getter
    private final LoadingCache<Long, Optional<FileClient>> clientCache =
            buildAsyncReloadingCache(Duration.ofSeconds(10L), new CacheLoader<Long, Optional<FileClient>>() {

                @Override
                public Optional<FileClient> load(Long id) {
                    FileConfigDO config = Objects.equals(CACHE_MASTER_ID, id)
                            ? fileConfigMapper.selectByMaster()
                            : fileConfigMapper.selectById(id);
                    if (config == null) {
                        return Optional.empty();
                    }
                    return Optional.ofNullable(createOrUpdateFileClient(config));
                }
            });

    private final FileClientFactory fileClientFactory;

    private final FileConfigMapper fileConfigMapper;

    private final FileMapper fileMapper;

    private final FileContentMapper fileContentMapper;

    private final FileConfigCredentialCodec credentialCodec;

    @Override
    @LogRecord(
            type = INFRA_FILE_CONFIG_TYPE,
            subType = INFRA_FILE_CONFIG_CREATE_SUB_TYPE,
            bizNo = "{{#fileConfig.id}}",
            success = INFRA_FILE_CONFIG_CREATE_SUCCESS)
    public Long createFileConfig(FileConfigDO fileConfig, Map<String, Object> configMap) {
        FileClientConfig clientConfig = credentialCodec.parse(fileConfig.getStorage(), configMap, null);
        fileConfig
                .setConfigCiphertext(credentialCodec.encrypt(clientConfig))
                .setConfig(null)
                .setMaster(false);
        fileConfigMapper.insert(fileConfig);

        // 记录操作日志上下文
        LogRecordContext.putVariable("fileConfig", fileConfig);
        return fileConfig.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = INFRA_FILE_CONFIG_TYPE,
            subType = INFRA_FILE_CONFIG_UPDATE_SUB_TYPE,
            bizNo = "{{#updateObj.id}}",
            success = INFRA_FILE_CONFIG_UPDATE_SUCCESS)
    public void updateFileConfig(FileConfigDO updateObj, Map<String, Object> configMap) {
        if (updateObj.getId() == null) {
            throw exception(FILE_CONFIG_NOT_EXISTS);
        }
        FileConfigDO config = fileConfigMapper.selectByIdForUpdate(updateObj.getId());
        if (config == null) {
            throw exception(FILE_CONFIG_NOT_EXISTS);
        }
        FileClientConfig existingClientConfig = credentialCodec.decrypt(config);
        FileClientConfig clientConfig = credentialCodec.parse(config.getStorage(), configMap, existingClientConfig);
        updateObj
                .setStorage(config.getStorage())
                .setConfigCiphertext(credentialCodec.encrypt(clientConfig))
                .setConfig(null);
        fileConfigMapper.updateById(updateObj);

        // 清空缓存
        clearCache(config.getId(), config.getMaster());

        // 记录操作日志上下文
        LogRecordContext.putVariable("fileConfig", config);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = INFRA_FILE_CONFIG_TYPE,
            subType = INFRA_FILE_CONFIG_UPDATE_MASTER_SUB_TYPE,
            bizNo = "{{#id}}",
            success = INFRA_FILE_CONFIG_UPDATE_MASTER_SUCCESS)
    public void updateFileConfigMaster(Long id) {
        // 校验存在
        FileConfigDO config = validateFileConfigExists(id);
        // 更新其它为非 master
        fileConfigMapper.updateBatch(new FileConfigDO().setMaster(false));
        // 更新
        fileConfigMapper.updateById(new FileConfigDO().setId(id).setMaster(true));

        // 清空缓存
        clearCache(null, true);

        // 记录操作日志上下文
        LogRecordContext.putVariable("fileConfig", config);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = INFRA_FILE_CONFIG_TYPE,
            subType = INFRA_FILE_CONFIG_DELETE_SUB_TYPE,
            bizNo = "{{#id}}",
            success = INFRA_FILE_CONFIG_DELETE_SUCCESS)
    public void deleteFileConfig(Long id) {
        FileConfigDO config = validateFileConfigForDelete(id);
        // 删除
        fileConfigMapper.deleteById(id);

        // 清空缓存
        clearCache(id, null);

        // 记录操作日志上下文
        LogRecordContext.putVariable("fileConfig", config);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFileConfigList(List<Long> ids) {
        List<Long> configIds = ids.stream().distinct().sorted().toList();
        configIds.forEach(this::validateFileConfigForDelete);

        // 批量删除
        fileConfigMapper.deleteByIds(configIds);

        // 清空缓存
        configIds.forEach(id -> clearCache(id, null));
    }

    /**
     * 清空指定文件配置
     *
     * @param id     配置编号
     * @param master 是否主配置
     */
    private void clearCache(Long id, Boolean master) {
        if (id != null) {
            clientCache.invalidate(id);
            fileClientFactory.removeFileClient(id);
        }
        if (Boolean.TRUE.equals(master)) {
            clientCache.invalidate(CACHE_MASTER_ID);
        }
    }

    private FileConfigDO validateFileConfigExists(Long id) {
        FileConfigDO config = fileConfigMapper.selectById(id);
        if (config == null) {
            throw exception(FILE_CONFIG_NOT_EXISTS);
        }
        return config;
    }

    private FileConfigDO validateFileConfigForDelete(Long id) {
        FileConfigDO config = fileConfigMapper.selectByIdForUpdate(id);
        if (config == null) {
            throw exception(FILE_CONFIG_NOT_EXISTS);
        }
        if (Boolean.TRUE.equals(config.getMaster())) {
            throw exception(FILE_CONFIG_DELETE_FAIL_MASTER);
        }
        if (fileMapper.selectCountByConfigId(id) > 0 || fileContentMapper.selectCountByConfigId(id) > 0) {
            throw exception(FILE_CONFIG_IN_USE, id);
        }
        return config;
    }

    @Override
    public FileConfigDO getFileConfig(Long id) {
        return credentialCodec.hydrate(fileConfigMapper.selectById(id));
    }

    @Override
    public PageResult<FileConfigDO> getFileConfigPage(
            PageParam pageParam, String name, Integer storage, LocalDateTime[] createTime) {
        PageResult<FileConfigDO> page = fileConfigMapper.selectPage(pageParam, name, storage, createTime);
        page.getList().forEach(credentialCodec::hydrate);
        return page;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String testFileConfig(Long id) throws Exception {
        FileClient fileClient = getFileClientForReferenceWrite(id);
        if (fileClient == null) {
            throw exception(FILE_CONFIG_NOT_EXISTS);
        }
        byte[] content = ResourceUtil.readBytes("file/erweima.jpg");
        String path = IdUtil.fastSimpleUUID() + ".jpg";
        String url = fileClient.upload(content, path, "image/jpeg");
        fileMapper.insert(new FileDO()
                .setConfigId(fileClient.getId())
                .setName("erweima.jpg")
                .setPath(path)
                .setUrl(url)
                .setType("image/jpeg")
                .setSize((long) content.length));
        return url;
    }

    @Override
    public FileClient getFileClient(Long id) {
        return clientCache.getUnchecked(id).orElse(null);
    }

    @Override
    public FileClient getMasterFileClient() {
        return clientCache.getUnchecked(CACHE_MASTER_ID).orElse(null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FileClient getFileClientForReferenceWrite(Long id) {
        FileConfigDO config = fileConfigMapper.selectByIdForShare(id);
        return config == null ? null : createOrUpdateFileClient(config);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FileClient getMasterFileClientForReferenceWrite() {
        FileConfigDO config = fileConfigMapper.selectByMasterForShare();
        return config == null ? null : createOrUpdateFileClient(config);
    }

    private FileClient createOrUpdateFileClient(FileConfigDO config) {
        fileClientFactory.createOrUpdateFileClient(
                config.getId(), config.getStorage(), credentialCodec.resolve(config));
        return fileClientFactory.getFileClient(config.getId());
    }
}
