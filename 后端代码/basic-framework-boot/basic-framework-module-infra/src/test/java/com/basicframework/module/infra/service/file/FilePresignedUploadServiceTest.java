package com.basicframework.module.infra.service.file;

import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_PRESIGNED_UPLOAD_REQUIRES_PRIVATE_STORAGE;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_PRIVATE_READ_REQUIRES_PRIVATE_STORAGE;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_UPLOAD_OBJECT_INVALID;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_UPLOAD_TOKEN_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.hutool.crypto.digest.DigestUtil;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.dal.mysql.file.FileMapper;
import com.basicframework.module.infra.enums.file.FileAccessTypeEnum;
import com.basicframework.module.infra.framework.file.config.FilePresignedUploadProperties;
import com.basicframework.module.infra.framework.file.core.client.FileClient;
import com.basicframework.module.infra.framework.file.core.client.FileObjectMetadata;
import com.basicframework.module.infra.framework.file.core.utils.FileArchiveValidator;
import com.basicframework.module.infra.service.file.dto.FilePresignedUrlDTO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

class FilePresignedUploadServiceTest {

    private static final FileUploadPrincipal PRINCIPAL = new FileUploadPrincipal(7L, 2);
    private static final byte[] PNG = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    private final FileConfigService fileConfigService = mock(FileConfigService.class);
    private final FileMapper fileMapper = mock(FileMapper.class);
    private final FileArchiveValidator fileArchiveValidator = mock(FileArchiveValidator.class);
    private final FileDeletionService fileDeletionService = mock(FileDeletionService.class);
    private final FilePresignedUploadProperties properties = new FilePresignedUploadProperties();
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final FilePresignedUploadService service = new FilePresignedUploadService(
            fileConfigService, fileMapper, fileArchiveValidator, fileDeletionService, properties, transactionManager);

    @Test
    void issuePersistsOnlyTokenDigestAndServerOwnedMetadata() {
        FileClient client = mock(FileClient.class);
        when(client.getId()).thenReturn(21L);
        when(client.supportsPrivatePresignedUpload()).thenReturn(true);
        when(client.supportsPrivateRead()).thenReturn(true);
        when(client.presignPutUrl(anyString(), eq((long) PNG.length), eq("image/png"), eq(Duration.ofMinutes(15))))
                .thenReturn("https://storage.test/upload");
        when(client.presignGetUrl("images/safe.png", null))
                .thenReturn("https://storage.test/safe.png?signature=validation");
        when(fileConfigService.getMasterFileClientForReferenceWrite()).thenReturn(client);

        FilePresignedUrlDTO result = service.issue(
                "safe.png", "images/safe.png", (long) PNG.length, "image/png", PRINCIPAL, FileAccessTypeEnum.PRIVATE);

        assertThat(result.getUploadToken()).matches("[0-9a-f]{64}");
        ArgumentCaptor<FileDO> file = ArgumentCaptor.forClass(FileDO.class);
        verify(fileMapper).insert(file.capture());
        assertThat(file.getValue().getUploadTokenHash())
                .isEqualTo(DigestUtil.sha256Hex(result.getUploadToken()))
                .isNotEqualTo(result.getUploadToken());
        assertThat(file.getValue().getUploadStatus()).isEqualTo(FileMapper.UPLOAD_STATUS_PENDING);
        assertThat(file.getValue().getUploadStagingPath())
                .startsWith(".pending/")
                .doesNotContain(result.getUploadToken());
        assertThat(file.getValue().getUploadUserId()).isEqualTo(7L);
        assertThat(file.getValue().getUploadUserType()).isEqualTo(2);
        assertThat(file.getValue().getAccessType()).isEqualTo(FileAccessTypeEnum.PRIVATE.getValue());
        assertThat(file.getValue().getOwnerUserId()).isEqualTo(7L);
        assertThat(file.getValue().getOwnerUserType()).isEqualTo(2);
    }

    @Test
    void completeReadsAndValidatesObjectBeforePublishingMetadata() throws Exception {
        String token = "a".repeat(64);
        String tokenHash = DigestUtil.sha256Hex(token);
        FileDO pending = pendingFile(tokenHash);
        FileClient client = mock(FileClient.class);
        when(fileMapper.claimPendingUpload(eq(tokenHash), eq(7L), eq(2), any(LocalDateTime.class)))
                .thenReturn(1);
        when(fileMapper.selectClaimedUpload(tokenHash)).thenReturn(pending);
        when(fileMapper.selectValidatingUploadForUpdate(99L)).thenReturn(pending);
        when(fileConfigService.getFileClientForReferenceWrite(21L)).thenReturn(client);
        when(client.getMetadata(".pending/staging-object"))
                .thenReturn(new FileObjectMetadata((long) PNG.length, "image/png"));
        when(client.getContent(".pending/staging-object")).thenReturn(PNG);
        when(client.presignGetUrl("images/safe.png", null))
                .thenReturn(
                        "https://storage.test/safe.png?signature=validation",
                        "https://storage.test/safe.png?signature=read");
        when(client.getMetadata("images/safe.png")).thenReturn(new FileObjectMetadata((long) PNG.length, "image/png"));
        when(fileMapper.completeUpload(99L, "image/png", (long) PNG.length)).thenReturn(1);

        assertThat(service.complete(token, PRINCIPAL)).isEqualTo("https://storage.test/safe.png?signature=read");

        verify(fileArchiveValidator).validate(PNG, "safe.png");
        verify(client).upload(PNG, "images/safe.png", "image/png");
        verify(client).delete(".pending/staging-object");
        verify(fileMapper).completeUpload(99L, "image/png", (long) PNG.length);
    }

    @Test
    void concurrentStagingOverwriteCannotReplaceAlreadyValidatedBytes() {
        String token = "a".repeat(64);
        String tokenHash = DigestUtil.sha256Hex(token);
        Map<String, byte[]> objects = new HashMap<>();
        objects.put(".pending/staging-object", PNG.clone());
        FileClient client = new FileClient() {
            @Override
            public Long getId() {
                return 21L;
            }

            @Override
            public String upload(byte[] content, String path, String type) {
                objects.put(path, content.clone());
                return presignGetUrl(path, null);
            }

            @Override
            public void delete(String path) {
                objects.remove(path);
            }

            @Override
            public byte[] getContent(String path) {
                byte[] checkedSnapshot = objects.get(path).clone();
                // 原 PUT 签名仍有效：上传者在验证完成前用同长度非法内容替换临时对象。
                objects.put(path, new byte[checkedSnapshot.length]);
                return checkedSnapshot;
            }

            @Override
            public FileObjectMetadata getMetadata(String path) {
                byte[] content = objects.get(path);
                return content == null ? null : new FileObjectMetadata((long) content.length, "image/png");
            }

            @Override
            public String presignGetUrl(String path, Integer expirationSeconds) {
                return "https://storage.test/safe.png";
            }
        };
        when(fileMapper.claimPendingUpload(eq(tokenHash), eq(7L), eq(2), any(LocalDateTime.class)))
                .thenReturn(1);
        when(fileMapper.selectClaimedUpload(tokenHash)).thenReturn(pendingFile(tokenHash));
        when(fileMapper.selectValidatingUploadForUpdate(99L)).thenReturn(pendingFile(tokenHash));
        when(fileConfigService.getFileClientForReferenceWrite(21L)).thenReturn(client);
        when(fileMapper.completeUpload(99L, "image/png", (long) PNG.length)).thenReturn(1);

        assertThat(service.complete(token, PRINCIPAL)).isEqualTo("https://storage.test/safe.png");

        assertThat(objects.get("images/safe.png")).containsExactly(PNG);
        assertThat(objects).doesNotContainKey(".pending/staging-object");
        verifyNoInteractions(fileDeletionService);
    }

    @Test
    void completeReturnsValidatedUrlForSameOwnerRetry() {
        String token = "b".repeat(64);
        String tokenHash = DigestUtil.sha256Hex(token);
        FileDO completed = pendingFile(tokenHash).setUploadStatus(FileMapper.UPLOAD_STATUS_COMPLETE);
        FileClient client = mock(FileClient.class);
        when(fileMapper.claimPendingUpload(eq(tokenHash), eq(7L), eq(2), any(LocalDateTime.class)))
                .thenReturn(0);
        when(fileMapper.selectCompletedUpload(tokenHash, 7L, 2)).thenReturn(completed);
        when(fileConfigService.getFileClientForReferenceWrite(21L)).thenReturn(client);
        when(client.presignGetUrl("images/safe.png", null)).thenReturn("https://storage.test/safe.png?signature=retry");

        assertThat(service.complete(token, PRINCIPAL)).isEqualTo("https://storage.test/safe.png?signature=retry");
    }

    @Test
    void completeRejectsForeignTokenBeforeStorageAccess() {
        String token = "c".repeat(64);
        when(fileMapper.claimPendingUpload(eq(DigestUtil.sha256Hex(token)), eq(7L), eq(2), any(LocalDateTime.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.complete(token, PRINCIPAL))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(FILE_UPLOAD_TOKEN_INVALID.getCode()));
    }

    @Test
    void issueRejectsPublicStorageBeforeCreatingUploadState() {
        FileClient client = mock(FileClient.class);
        when(fileConfigService.getMasterFileClientForReferenceWrite()).thenReturn(client);

        assertThatThrownBy(() -> service.issue(
                        "safe.png",
                        "images/safe.png",
                        (long) PNG.length,
                        "image/png",
                        PRINCIPAL,
                        FileAccessTypeEnum.PRIVATE))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(FILE_PRESIGNED_UPLOAD_REQUIRES_PRIVATE_STORAGE.getCode()));
    }

    @Test
    void issueRejectsMissingClientAndInvalidPrincipal() {
        assertThatThrownBy(() -> service.issue(
                        "safe.png", "images/safe.png", 1L, "image/png", PRINCIPAL, FileAccessTypeEnum.PRIVATE))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.issue(
                        "safe.png",
                        "images/safe.png",
                        1L,
                        "image/png",
                        new FileUploadPrincipal(7L, 0),
                        FileAccessTypeEnum.PRIVATE))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(FILE_UPLOAD_TOKEN_INVALID.getCode()));
    }

    @Test
    void completeInvalidObjectEntersDurableCleanup() throws Exception {
        String token = "d".repeat(64);
        String tokenHash = DigestUtil.sha256Hex(token);
        FileDO pending = pendingFile(tokenHash);
        FileClient client = mock(FileClient.class);
        when(fileMapper.claimPendingUpload(eq(tokenHash), eq(7L), eq(2), any(LocalDateTime.class)))
                .thenReturn(1);
        when(fileMapper.selectClaimedUpload(tokenHash)).thenReturn(pending);
        when(fileMapper.selectValidatingUploadForUpdate(99L)).thenReturn(pending);
        when(fileConfigService.getFileClientForReferenceWrite(21L)).thenReturn(client);
        when(client.getMetadata(".pending/staging-object"))
                .thenReturn(new FileObjectMetadata((long) PNG.length + 1, "image/png"));

        assertThatThrownBy(() -> service.complete(token, PRINCIPAL)).isInstanceOf(ServiceException.class);

        verify(fileDeletionService).deleteIncompleteFile(pending);
    }

    @Test
    void issueRejectsPrivateAccessWhenBucketCannotBlockPublicReads() {
        FileClient client = mock(FileClient.class);
        when(fileConfigService.getMasterFileClientForReferenceWrite()).thenReturn(client);
        when(client.supportsPrivatePresignedUpload()).thenReturn(true);

        assertThatThrownBy(() -> service.issue(
                        "safe.png",
                        "images/safe.png",
                        (long) PNG.length,
                        "image/png",
                        PRINCIPAL,
                        FileAccessTypeEnum.PRIVATE))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(FILE_PRIVATE_READ_REQUIRES_PRIVATE_STORAGE.getCode()));
    }

    @Test
    void completeRejectsMalformedTokenBeforeAnyStorageAccess() {
        assertThatThrownBy(() -> service.complete("not-a-hex-token", PRINCIPAL))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(FILE_UPLOAD_TOKEN_INVALID.getCode()));

        verifyNoInteractions(fileMapper, fileDeletionService);
    }

    @Test
    void cleanupWinningBeforePublicationRejectsWithoutWritingFinalObject() {
        String token = "9".repeat(64);
        String tokenHash = DigestUtil.sha256Hex(token);
        FileDO pending = pendingFile(tokenHash);
        FileClient client = mock(FileClient.class);
        when(fileMapper.claimPendingUpload(eq(tokenHash), eq(7L), eq(2), any(LocalDateTime.class)))
                .thenReturn(1);
        when(fileMapper.selectClaimedUpload(tokenHash)).thenReturn(pending);
        when(fileConfigService.getFileClientForReferenceWrite(21L)).thenReturn(client);
        when(fileMapper.selectValidatingUploadForUpdate(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.complete(token, PRINCIPAL))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(FILE_UPLOAD_TOKEN_INVALID.getCode()));

        verifyNoInteractions(client);
    }

    @Test
    void completeMissingClaimedRecord_failsFast() {
        String token = "e".repeat(64);
        String tokenHash = DigestUtil.sha256Hex(token);
        when(fileMapper.claimPendingUpload(eq(tokenHash), eq(7L), eq(2), any(LocalDateTime.class)))
                .thenReturn(1);
        when(fileMapper.selectClaimedUpload(tokenHash)).thenReturn(null);

        assertThatThrownBy(() -> service.complete(token, PRINCIPAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已认领");
    }

    @Test
    void completeBlankStagingPath_rejectsBeforeObjectMetadataRead() {
        String token = "f".repeat(64);
        String tokenHash = DigestUtil.sha256Hex(token);
        FileDO pending = pendingFile(tokenHash).setUploadStagingPath(null);
        when(fileMapper.claimPendingUpload(eq(tokenHash), eq(7L), eq(2), any(LocalDateTime.class)))
                .thenReturn(1);
        when(fileMapper.selectClaimedUpload(tokenHash)).thenReturn(pending);
        when(fileMapper.selectValidatingUploadForUpdate(99L)).thenReturn(pending);
        when(fileConfigService.getFileClientForReferenceWrite(21L)).thenReturn(mock(FileClient.class));

        assertThatThrownBy(() -> service.complete(token, PRINCIPAL))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(FILE_UPLOAD_OBJECT_INVALID.getCode()));
    }

    private static FileDO pendingFile(String tokenHash) {
        return new FileDO()
                .setId(99L)
                .setConfigId(21L)
                .setName("safe.png")
                .setPath("images/safe.png")
                .setUrl("https://storage.test/safe.png")
                .setType("image/png")
                .setSize((long) PNG.length)
                .setUploadStatus(FileMapper.UPLOAD_STATUS_VALIDATING)
                .setUploadStagingPath(".pending/staging-object")
                .setUploadTokenHash(tokenHash)
                .setUploadExpiresAt(LocalDateTime.now().plusMinutes(10))
                .setUploadUserId(7L)
                .setUploadUserType(2)
                .setAccessType(FileAccessTypeEnum.PRIVATE.getValue())
                .setOwnerUserId(7L)
                .setOwnerUserType(2);
    }
}
