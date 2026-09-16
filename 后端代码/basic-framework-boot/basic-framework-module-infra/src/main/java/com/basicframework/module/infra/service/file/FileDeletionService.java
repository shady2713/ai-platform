package com.basicframework.module.infra.service.file;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_CLIENT_NOT_EXISTS;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_NOT_EXISTS;

import cn.hutool.core.util.StrUtil;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.dal.mysql.file.FileMapper;
import com.basicframework.module.infra.framework.file.config.FileDeletionProperties;
import com.basicframework.module.infra.framework.file.core.client.FileClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 以持久化待删除状态协调数据库元数据与不可事务化的外部文件存储。 */
@Service
@Slf4j
public class FileDeletionService {

    private final FileConfigService fileConfigService;
    private final FileMapper fileMapper;
    private final FileDeletionProperties properties;
    private final TransactionTemplate transactionTemplate;

    public FileDeletionService(
            FileConfigService fileConfigService,
            FileMapper fileMapper,
            FileDeletionProperties properties,
            PlatformTransactionManager transactionManager) {
        this.fileConfigService = fileConfigService;
        this.fileMapper = fileMapper;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 在事务内将文件切换为不可见的待删除状态，再尽力同步清理；失败项由定时任务继续补偿。
     *
     * @param ids 文件编号
     */
    public void deleteFiles(List<Long> ids) {
        List<Long> normalizedIds = ids == null
                ? List.of()
                : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (normalizedIds.isEmpty()) {
            throw exception(FILE_NOT_EXISTS);
        }
        List<FileDO> files = transactionTemplate.execute(status -> prepareDeletion(normalizedIds));
        if (files == null) {
            throw new IllegalStateException("文件删除事务未返回待清理记录");
        }
        files.forEach(this::cleanupOne);
    }

    /**
     * 重试到期的待删除记录。
     *
     * @return 本轮成功清理的记录数
     */
    public int retryPendingFiles() {
        fileMapper.markExpiredUploadsDeletePending(LocalDateTime.now(), properties.getBatchSize());
        List<FileDO> files = fileMapper.selectPendingDeletion(LocalDateTime.now(), properties.getBatchSize());
        int deleted = 0;
        for (FileDO file : files) {
            if (cleanupOne(file)) {
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * 将校验失败的预签名上传转入同一套持久化删除补偿流程。
     *
     * @param file 未完成上传记录
     */
    public void deleteIncompleteFile(FileDO file) {
        if (file == null || file.getId() == null || fileMapper.markIncompleteUploadDeletePending(file.getId()) != 1) {
            throw new IllegalStateException("未完成上传无法转入删除补偿流程");
        }
        file.setDeleteStatus(FileMapper.DELETE_STATUS_PENDING);
        file.setDeleteAttempts(0);
        cleanupOne(file);
    }

    private List<FileDO> prepareDeletion(List<Long> ids) {
        List<FileDO> files = fileMapper.selectActiveByIdsForUpdate(ids);
        if (files.size() != ids.size() || fileMapper.markDeletePending(ids) != ids.size()) {
            throw exception(FILE_NOT_EXISTS);
        }
        files.forEach(file -> {
            file.setDeleteStatus(FileMapper.DELETE_STATUS_PENDING);
            file.setDeleteAttempts(0);
        });
        return files;
    }

    private boolean cleanupOne(FileDO file) {
        try {
            FileClient client = fileConfigService.getFileClient(file.getConfigId());
            if (client == null) {
                throw exception(FILE_CLIENT_NOT_EXISTS, file.getConfigId());
            }
            if ((file.getUploadStatus() == null || file.getUploadStatus() != FileMapper.UPLOAD_STATUS_COMPLETE)
                    && StrUtil.isNotBlank(file.getUploadStagingPath())) {
                client.delete(file.getUploadStagingPath());
            }
            client.delete(file.getPath());
            fileMapper.deletePendingById(file.getId());
            return true;
        } catch (Exception cleanupFailure) {
            LocalDateTime nextRetryTime = LocalDateTime.now().plus(retryDelay(file.getDeleteAttempts()));
            fileMapper.recordDeleteFailure(
                    file.getId(), nextRetryTime, cleanupFailure.getClass().getSimpleName());
            log.warn(
                    "[cleanupOne][文件外部存储清理失败，已登记重试][fileId={}][configId={}][errorType={}]",
                    file.getId(),
                    file.getConfigId(),
                    cleanupFailure.getClass().getSimpleName());
            return false;
        }
    }

    private Duration retryDelay(Integer attempts) {
        int completedAttempts = attempts == null ? 0 : Math.max(0, attempts);
        int exponent = Math.min(completedAttempts, 30);
        long multiplier = 1L << exponent;
        Duration candidate;
        try {
            candidate = properties.getInitialRetryDelay().multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            return properties.getMaxRetryDelay();
        }
        return candidate.compareTo(properties.getMaxRetryDelay()) > 0 ? properties.getMaxRetryDelay() : candidate;
    }
}
