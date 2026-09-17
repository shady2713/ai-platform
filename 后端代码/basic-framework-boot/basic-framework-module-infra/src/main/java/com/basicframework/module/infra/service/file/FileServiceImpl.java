package com.basicframework.module.infra.service.file;

import static cn.hutool.core.date.DatePattern.PURE_DATE_PATTERN;
import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_BUSINESS_DELETE_REQUIRES_AUTHORIZATION;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_CLIENT_NOT_EXISTS;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_IS_EMPTY;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_METADATA_INVALID;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_NOT_EXISTS;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_PATH_INVALID;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_PRIVATE_READ_REQUIRES_PRIVATE_STORAGE;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_TYPE_NOT_ALLOWED;
import static com.basicframework.module.infra.framework.file.core.utils.FileMetadataLimits.*;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.http.HttpUtils;
import com.basicframework.framework.common.util.validation.ValidationUtils;
import com.basicframework.module.infra.api.file.dto.FileBusinessAccessContext;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.dal.mysql.file.FileMapper;
import com.basicframework.module.infra.enums.file.FileAccessTypeEnum;
import com.basicframework.module.infra.framework.file.core.client.FileClient;
import com.basicframework.module.infra.framework.file.core.utils.FileArchiveValidator;
import com.basicframework.module.infra.framework.file.core.utils.FileTypeUtils;
import com.basicframework.module.infra.service.file.dto.FilePresignedUrlDTO;
import com.google.common.annotations.VisibleForTesting;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件 Service 实现类
 */
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final FileConfigService fileConfigService;

    private final FileMapper fileMapper;

    private final FileArchiveValidator fileArchiveValidator;

    private final FileDeletionService fileDeletionService;

    private final FilePresignedUploadService filePresignedUploadService;

    private final FileBusinessAccessProviderRegistry fileBusinessAccessProviderRegistry;

    @Override
    public PageResult<FileDO> getFilePage(PageParam pageParam, String path, String type, LocalDateTime[] createTime) {
        return fileMapper.selectPage(pageParam, path, type, createTime);
    }

    @Override
    @SneakyThrows
    @Transactional(rollbackFor = Exception.class)
    public String createFile(
            byte[] content,
            String name,
            String directory,
            String type,
            FileUploadPrincipal principal,
            FileAccessTypeEnum accessType) {
        return persistFile(content, name, directory, type, principal, accessType, null, null)
                .url();
    }

    @Override
    @SneakyThrows
    @Transactional(rollbackFor = Exception.class)
    public Long createBusinessFile(
            byte[] content,
            String name,
            String type,
            String businessType,
            Long businessId,
            FileUploadPrincipal principal) {
        // 未注册授权实现的业务类型不允许创建：否则会产生永远不可读的文件
        fileBusinessAccessProviderRegistry.requireRegistered(businessType);
        return persistFile(content, name, null, type, principal, FileAccessTypeEnum.PRIVATE, businessType, businessId)
                .file()
                .getId();
    }

    /** 上传结果：保留原始访问地址（含查询参数）与持久化后的文件记录。 */
    private record PersistedFile(FileDO file, String url) {}

    @SneakyThrows
    private PersistedFile persistFile(
            byte[] content,
            String name,
            String directory,
            String type,
            FileUploadPrincipal principal,
            FileAccessTypeEnum accessType,
            String businessType,
            Long businessId) {
        validateUploadPrincipal(principal);
        validateAccessType(accessType);
        // 先拦截空内容，避免后续 MIME 识别、摘要计算和长度访问时出现空指针。
        if (content == null || content.length == 0) {
            throw exception(FILE_IS_EMPTY);
        }
        // 先探测真实 MIME，后续既要用于补齐后缀，也要用于类型白名单校验。
        String detectedType = FileTypeUtils.getMineType(content, name);
        // 调用方未显式传入类型时，回退为探测结果。
        if (StrUtil.isEmpty(type)) {
            type = detectedType;
        }
        // 未传入文件名时，使用内容摘要生成稳定名称。
        if (StrUtil.isEmpty(name)) {
            name = DigestUtil.sha256Hex(content);
        }
        if (StrUtil.isEmpty(FileUtil.extName(name))) {
            // 文件名缺少后缀时，优先根据探测结果补齐，避免白名单校验误判。
            String extension = FileTypeUtils.getExtension(StrUtil.emptyToDefault(detectedType, type));
            if (StrUtil.isNotEmpty(extension)) {
                name = name + extension;
            }
        }
        // 只有后缀和探测类型都在允许范围内时才允许上传，防止伪装可执行文件。
        if (!FileTypeUtils.isAllowedUploadType(content, name)) {
            throw exception(FILE_TYPE_NOT_ALLOWED);
        }
        // ZIP 在进入持久化存储前校验内部路径和展开规模，避免转交下游时携带压缩包攻击载荷。
        fileArchiveValidator.validate(content, name);
        // 重新结合最终文件名计算 MIME，确保上传到存储客户端的类型与文件名一致。
        type = FileTypeUtils.getMineType(content, name);

        // 生成唯一上传路径，避免不同目录/同名文件互相覆盖。
        String path = generateUploadPath(name, directory);
        FileClient client = requireMasterFileClientForReferenceWrite();
        validatePrivateReadStorage(client, accessType);
        String url = client.upload(content, path, type);

        // 上传成功后持久化文件元数据；校验或入库失败时补偿删除外部对象，避免孤儿文件。
        FileDO file = new FileDO()
                .setConfigId(client.getId())
                .setName(name)
                .setPath(path)
                .setUrl(HttpUtils.removeUrlQuery(url))
                .setType(type)
                .setSize((long) content.length)
                .setAccessType(accessType.getValue())
                .setOwnerUserId(principal.userId())
                .setOwnerUserType(principal.userType())
                .setBusinessType(businessType)
                .setBusinessId(businessId)
                .setUploadStatus(FileMapper.UPLOAD_STATUS_COMPLETE);
        try {
            validateFileMetadata(file);
            fileMapper.insert(file);
        } catch (RuntimeException exception) {
            try {
                client.delete(path);
            } catch (Exception cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
        return new PersistedFile(file, url);
    }

    @VisibleForTesting
    String generateUploadPath(String name, String directory) {
        validateUploadPath(name, directory);
        // 每次上传独占对象键；并发同名上传及失败补偿不得覆盖或删除其他上传。
        String suffix = UUID.randomUUID().toString();
        String ext = FileUtil.extName(name);
        name = StrUtil.isNotEmpty(ext) ? FileUtil.mainName(name) + "_" + suffix + "." + ext : name + "_" + suffix;
        name = LocalDateTimeUtil.format(LocalDateTimeUtil.now(), PURE_DATE_PATTERN) + "/" + name;
        // 最后拼接业务目录，保持调用方传入的目录层级。
        if (StrUtil.isNotEmpty(directory)) {
            name = directory + StrUtil.SLASH + name;
        }
        if (ValidationUtils.codePointLength(name) > MAX_PATH_LENGTH) {
            throw exception(FILE_PATH_INVALID);
        }
        return name;
    }

    private static void validateUploadPath(String name, String directory) {
        if (StrUtil.isEmpty(name)
                || ValidationUtils.codePointLength(name) > MAX_NAME_LENGTH
                || StrUtil.contains(name, "..")
                || StrUtil.containsAny(name, "/", "\\", ":")
                || containsControlCharacter(name)) {
            throw exception(FILE_PATH_INVALID);
        }
        if (StrUtil.isNotEmpty(directory)
                && (ValidationUtils.codePointLength(directory) > MAX_DIRECTORY_LENGTH
                        || directory.equals(UPLOAD_STAGING_DIRECTORY)
                        || directory.startsWith(UPLOAD_STAGING_DIRECTORY + "/")
                        || StrUtil.contains(directory, "..")
                        || StrUtil.startWithAny(directory, "/", "\\")
                        || StrUtil.containsAny(directory, "\\", ":")
                        || containsControlCharacter(directory))) {
            throw exception(FILE_PATH_INVALID);
        }
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    @Override
    public FilePresignedUrlDTO presignPutUrl(
            String name,
            String directory,
            Long size,
            String type,
            FileUploadPrincipal principal,
            FileAccessTypeEnum accessType) {
        // 预签名上传同样复用统一的路径生成规则，避免和直接上传路径不一致。
        String path = generateUploadPath(name, directory);
        return filePresignedUploadService.issue(name, path, size, type, principal, accessType);
    }

    @Override
    public String presignGetUrl(String url, Integer expirationSeconds) {
        FileClient fileClient = requireMasterFileClient();
        return fileClient.presignGetUrl(url, expirationSeconds);
    }

    private FileClient requireMasterFileClient() {
        FileClient fileClient = fileConfigService.getMasterFileClient();
        if (fileClient == null) {
            throw exception(FILE_CLIENT_NOT_EXISTS, "master");
        }
        return fileClient;
    }

    private FileClient requireMasterFileClientForReferenceWrite() {
        FileClient fileClient = fileConfigService.getMasterFileClientForReferenceWrite();
        if (fileClient == null) {
            throw exception(FILE_CLIENT_NOT_EXISTS, "master");
        }
        return fileClient;
    }

    @Override
    public String createPresignedFile(String uploadToken, FileUploadPrincipal principal) {
        return filePresignedUploadService.complete(uploadToken, principal);
    }

    private static void validateFileMetadata(FileDO file) {
        if (file == null
                || file.getConfigId() == null
                || file.getConfigId() <= 0
                || StrUtil.isBlank(file.getName())
                || StrUtil.isBlank(file.getPath())
                || StrUtil.isBlank(file.getUrl())
                || file.getSize() == null
                || file.getSize() < 0
                || file.getSize() > MAX_SIZE
                || !FileAccessTypeEnum.isValid(file.getAccessType())
                || file.getOwnerUserId() == null
                || file.getOwnerUserType() == null
                || ValidationUtils.codePointLength(file.getName()) > MAX_NAME_LENGTH
                || ValidationUtils.codePointLength(file.getPath()) > MAX_PATH_LENGTH
                || ValidationUtils.codePointLength(file.getUrl()) > MAX_URL_LENGTH
                || (file.getType() != null && ValidationUtils.codePointLength(file.getType()) > MAX_TYPE_LENGTH)) {
            throw exception(FILE_METADATA_INVALID);
        }
        validateUploadPath(file.getName(), null);
        if (StrUtil.contains(file.getPath(), "..")
                || StrUtil.startWithAny(file.getPath(), "/", "\\")
                || StrUtil.containsAny(file.getPath(), "\\", ":")
                || containsControlCharacter(file.getPath())) {
            throw exception(FILE_PATH_INVALID);
        }
    }

    @Override
    public FileDO getFile(Long id) {
        return validateFileExists(id);
    }

    @Override
    public void deleteFile(Long id) throws Exception {
        rejectBusinessBoundDeletion(List.of(id));
        fileDeletionService.deleteFiles(List.of(id));
    }

    @Override
    public void deleteFileList(List<Long> ids) {
        rejectBusinessBoundDeletion(ids);
        fileDeletionService.deleteFiles(ids);
    }

    private FileDO validateFileExists(Long id) {
        FileDO fileDO = fileMapper.selectActiveById(id);
        if (fileDO == null) {
            throw exception(FILE_NOT_EXISTS);
        }
        return fileDO;
    }

    @Override
    public byte[] getFileContent(Long configId, String path, FileAccessPrincipal principal) throws Exception {
        FileDO file = fileMapper.selectActiveByConfigIdAndPath(configId, path);
        if (file == null || !canRead(file, principal)) {
            // 未授权时与不存在保持相同语义，避免利用路径枚举文件元数据。
            throw exception(FILE_NOT_EXISTS);
        }
        FileClient client = fileConfigService.getFileClient(configId);
        if (client == null) {
            throw exception(FILE_CLIENT_NOT_EXISTS, configId);
        }
        return client.getContent(file.getPath());
    }

    private static void validateUploadPrincipal(FileUploadPrincipal principal) {
        if (principal == null || !principal.isAuthenticatedApplicationUser()) {
            throw exception(FILE_METADATA_INVALID);
        }
    }

    private static void validateAccessType(FileAccessTypeEnum accessType) {
        if (accessType == null) {
            throw exception(FILE_METADATA_INVALID);
        }
    }

    private static void validatePrivateReadStorage(FileClient client, FileAccessTypeEnum accessType) {
        if (FileAccessTypeEnum.PRIVATE == accessType && !client.supportsPrivateRead()) {
            throw exception(FILE_PRIVATE_READ_REQUIRES_PRIVATE_STORAGE);
        }
    }

    private boolean canRead(FileDO file, FileAccessPrincipal principal) {
        // 业务绑定文件：读取授权只由业务 Provider 决定，管理权限与所有者都不构成豁免
        if (StrUtil.isNotEmpty(file.getBusinessType())) {
            return principal != null
                    && fileBusinessAccessProviderRegistry.canRead(buildBusinessContext(file, principal));
        }
        return FileAccessTypeEnum.isPublic(file.getAccessType())
                || principal != null
                        && (principal.canManageFiles()
                                || principal.owns(file.getOwnerUserId(), file.getOwnerUserType()));
    }

    private FileBusinessAccessContext buildBusinessContext(FileDO file, FileAccessPrincipal principal) {
        return FileBusinessAccessProviderRegistry.buildContext(
                file.getId(), file.getBusinessType(), file.getBusinessId(), principal);
    }

    @Override
    public FileDO getAuthorizedFile(Long id, FileAccessPrincipal principal) {
        FileDO file = fileMapper.selectActiveById(id);
        if (file == null || !canRead(file, principal)) {
            // 未授权与不存在保持同一语义，避免借编号枚举文件。
            throw exception(FILE_NOT_EXISTS);
        }
        return file;
    }

    @Override
    public void deleteBusinessFile(Long id, FileAccessPrincipal principal) throws Exception {
        FileDO file = fileMapper.selectActiveById(id);
        if (file == null
                || StrUtil.isEmpty(file.getBusinessType())
                || principal == null
                || !fileBusinessAccessProviderRegistry.canDelete(buildBusinessContext(file, principal))) {
            // 未授权、非业务绑定或不存在都按同一语义返回，避免探测文件是否受管控。
            throw exception(FILE_NOT_EXISTS);
        }
        fileDeletionService.deleteFiles(List.of(id));
    }

    private void rejectBusinessBoundDeletion(List<Long> ids) {
        for (Long id : ids) {
            FileDO file = fileMapper.selectActiveById(id);
            if (file != null && StrUtil.isNotEmpty(file.getBusinessType())) {
                // 业务绑定文件的删除必须走业务模块授权（deleteBusinessFile），管理端存储管理不得代替业务授权
                throw exception(FILE_BUSINESS_DELETE_REQUIRES_AUTHORIZATION);
            }
        }
    }
}
