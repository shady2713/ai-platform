package com.basicframework.module.infra.service.file;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_CLIENT_NOT_EXISTS;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_METADATA_INVALID;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_PRESIGNED_UPLOAD_REQUIRES_PRIVATE_STORAGE;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_PRIVATE_READ_REQUIRES_PRIVATE_STORAGE;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_TYPE_NOT_ALLOWED;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_UPLOAD_OBJECT_INVALID;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_UPLOAD_TOKEN_INVALID;
import static com.basicframework.module.infra.framework.file.core.utils.FileMetadataLimits.MAX_TYPE_LENGTH;
import static com.basicframework.module.infra.framework.file.core.utils.FileMetadataLimits.UPLOAD_STAGING_DIRECTORY;

import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.basicframework.framework.common.util.http.HttpUtils;
import com.basicframework.framework.common.util.validation.ValidationUtils;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.dal.mysql.file.FileMapper;
import com.basicframework.module.infra.enums.file.FileAccessTypeEnum;
import com.basicframework.module.infra.framework.file.config.FilePresignedUploadProperties;
import com.basicframework.module.infra.framework.file.core.client.FileClient;
import com.basicframework.module.infra.framework.file.core.client.FileObjectMetadata;
import com.basicframework.module.infra.framework.file.core.utils.FileArchiveValidator;
import com.basicframework.module.infra.framework.file.core.utils.FileTypeUtils;
import com.basicframework.module.infra.service.file.dto.FilePresignedUrlDTO;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class FilePresignedUploadService {

    private static final int TOKEN_BYTES = 32;
    private static final int TOKEN_HEX_LENGTH = TOKEN_BYTES * 2;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final FileConfigService fileConfigService;
    private final FileMapper fileMapper;
    private final FileArchiveValidator fileArchiveValidator;
    private final FileDeletionService fileDeletionService;
    private final FilePresignedUploadProperties properties;
    private final PlatformTransactionManager transactionManager;

    @Transactional(rollbackFor = Exception.class)
    public FilePresignedUrlDTO issue(
            String name,
            String path,
            Long expectedSize,
            String declaredType,
            FileUploadPrincipal principal,
            FileAccessTypeEnum accessType) {
        validatePrincipal(principal);
        validateAccessType(accessType);
        validateDeclaredMetadata(expectedSize, declaredType);
        FileClient client = fileConfigService.getMasterFileClientForReferenceWrite();
        if (client == null) {
            throw exception(FILE_CLIENT_NOT_EXISTS, "master");
        }
        if (!client.supportsPrivatePresignedUpload()) {
            throw exception(FILE_PRESIGNED_UPLOAD_REQUIRES_PRIVATE_STORAGE);
        }
        if (FileAccessTypeEnum.PRIVATE == accessType && !client.supportsPrivateRead()) {
            throw exception(FILE_PRIVATE_READ_REQUIRES_PRIVATE_STORAGE);
        }

        String uploadToken = generateToken();
        String stagingPath = UPLOAD_STAGING_DIRECTORY + "/" + generateToken();
        String uploadUrl = client.presignPutUrl(stagingPath, expectedSize, declaredType, properties.getTtl());
        String visitUrl = HttpUtils.removeUrlQuery(client.presignGetUrl(path, null));
        FileDO file = new FileDO()
                .setConfigId(client.getId())
                .setName(name)
                .setPath(path)
                .setUrl(visitUrl)
                .setType(StrUtil.blankToDefault(declaredType, null))
                .setSize(expectedSize)
                .setUploadStatus(FileMapper.UPLOAD_STATUS_PENDING)
                .setUploadStagingPath(stagingPath)
                .setUploadTokenHash(DigestUtil.sha256Hex(uploadToken))
                .setUploadExpiresAt(LocalDateTime.now().plus(properties.getTtl()))
                .setUploadUserId(principal.userId())
                .setUploadUserType(principal.userType())
                .setAccessType(accessType.getValue())
                .setOwnerUserId(principal.userId())
                .setOwnerUserType(principal.userType());
        fileMapper.insert(file);
        return new FilePresignedUrlDTO(client.getId(), uploadUrl, visitUrl, path, uploadToken);
    }

    @SneakyThrows
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String complete(String uploadToken, FileUploadPrincipal principal) {
        validatePrincipal(principal);
        if (uploadToken == null || !uploadToken.matches("[0-9a-fA-F]{" + TOKEN_HEX_LENGTH + "}")) {
            throw exception(FILE_UPLOAD_TOKEN_INVALID);
        }
        String tokenHash = DigestUtil.sha256Hex(uploadToken);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        FileDO file = transaction.execute(status -> claimUpload(tokenHash, principal));
        if (FileMapper.UPLOAD_STATUS_COMPLETE == file.getUploadStatus()) {
            return transaction.execute(status -> createReadUrl(file));
        }
        try {
            return transaction.execute(status -> publishClaimedUpload(file));
        } catch (Exception failure) {
            // 发布事务先回滚，认领记录仍已提交；补偿状态不会被发布异常一同回滚。
            try {
                fileDeletionService.deleteIncompleteFile(file);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private FileDO claimUpload(String tokenHash, FileUploadPrincipal principal) {
        if (fileMapper.claimPendingUpload(tokenHash, principal.userId(), principal.userType(), LocalDateTime.now())
                != 1) {
            FileDO completed = fileMapper.selectCompletedUpload(tokenHash, principal.userId(), principal.userType());
            if (completed != null) {
                return completed;
            }
            throw exception(FILE_UPLOAD_TOKEN_INVALID);
        }
        FileDO file = fileMapper.selectClaimedUpload(tokenHash);
        if (file == null) {
            throw new IllegalStateException("已认领的预签名上传记录不存在");
        }
        return file;
    }

    @SneakyThrows
    private String publishClaimedUpload(FileDO claimed) {
        FileClient client = fileConfigService.getFileClientForReferenceWrite(claimed.getConfigId());
        if (client == null) {
            throw exception(FILE_CLIENT_NOT_EXISTS, claimed.getConfigId());
        }
        // 与过期清理串行：清理若先获锁则拒绝发布，否则它必须等完成提交后重新检查状态。
        FileDO file = fileMapper.selectValidatingUploadForUpdate(claimed.getId());
        if (file == null) {
            throw exception(FILE_UPLOAD_TOKEN_INVALID);
        }
        validateAndComplete(file, client);
        return client.presignGetUrl(file.getPath(), null);
    }

    private void validateAndComplete(FileDO file, FileClient client) throws Exception {
        String stagingPath = file.getUploadStagingPath();
        if (StrUtil.isBlank(stagingPath)) {
            throw exception(FILE_UPLOAD_OBJECT_INVALID);
        }
        FileObjectMetadata metadata = client.getMetadata(stagingPath);
        if (metadata == null
                || metadata.size() <= 0
                || metadata.size() != file.getSize()
                || metadata.size() > properties.getMaxSize().toBytes()) {
            throw exception(FILE_UPLOAD_OBJECT_INVALID);
        }
        byte[] content = client.getContent(stagingPath);
        if (content == null || content.length != metadata.size()) {
            throw exception(FILE_UPLOAD_OBJECT_INVALID);
        }
        if (!FileTypeUtils.isAllowedUploadType(content, file.getName())) {
            throw exception(FILE_TYPE_NOT_ALLOWED);
        }
        fileArchiveValidator.validate(content, file.getName());
        String expectedUrl = HttpUtils.removeUrlQuery(client.presignGetUrl(file.getPath(), null));
        if (!StrUtil.equals(file.getUrl(), expectedUrl)) {
            throw exception(FILE_UPLOAD_OBJECT_INVALID);
        }
        String detectedType = FileTypeUtils.getMineType(content, file.getName());
        // 临时 PUT 签名在过期前仍可复用，只发布刚刚完成校验的字节，避免复制并发替换后的对象。
        client.upload(content, file.getPath(), detectedType);
        FileObjectMetadata published = client.getMetadata(file.getPath());
        if (published == null || published.size() != metadata.size()) {
            throw exception(FILE_UPLOAD_OBJECT_INVALID);
        }
        client.delete(stagingPath);
        if (fileMapper.completeUpload(file.getId(), detectedType, metadata.size()) != 1) {
            throw new IllegalStateException("预签名上传状态发生并发变更");
        }
    }

    private String createReadUrl(FileDO file) {
        FileClient client = fileConfigService.getFileClientForReferenceWrite(file.getConfigId());
        if (client == null) {
            throw exception(FILE_CLIENT_NOT_EXISTS, file.getConfigId());
        }
        return client.presignGetUrl(file.getPath(), null);
    }

    private void validateDeclaredMetadata(Long expectedSize, String declaredType) {
        if (expectedSize == null
                || expectedSize <= 0
                || expectedSize > properties.getMaxSize().toBytes()
                || declaredType != null && ValidationUtils.codePointLength(declaredType) > MAX_TYPE_LENGTH) {
            throw exception(FILE_METADATA_INVALID);
        }
    }

    private static void validatePrincipal(FileUploadPrincipal principal) {
        if (principal == null || !principal.isAuthenticatedApplicationUser()) {
            throw exception(FILE_UPLOAD_TOKEN_INVALID);
        }
    }

    private static void validateAccessType(FileAccessTypeEnum accessType) {
        if (accessType == null) {
            throw exception(FILE_METADATA_INVALID);
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return HexUtil.encodeHexStr(bytes);
    }
}
