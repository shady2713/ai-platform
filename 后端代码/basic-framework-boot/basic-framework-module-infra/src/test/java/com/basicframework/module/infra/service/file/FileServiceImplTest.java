package com.basicframework.module.infra.service.file;

import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_CLIENT_NOT_EXISTS;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_NOT_EXISTS;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_PATH_INVALID;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_PRIVATE_READ_REQUIRES_PRIVATE_STORAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.hutool.crypto.digest.DigestUtil;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.dal.mysql.file.FileMapper;
import com.basicframework.module.infra.enums.file.FileAccessTypeEnum;
import com.basicframework.module.infra.framework.file.config.FileArchiveSecurityProperties;
import com.basicframework.module.infra.framework.file.core.client.FileClient;
import com.basicframework.module.infra.framework.file.core.utils.FileArchiveValidator;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/** {@link FileServiceImpl} 上传路径边界测试。 */
class FileServiceImplTest {

    private static final FileUploadPrincipal PRINCIPAL = new FileUploadPrincipal(7L, 2);

    private final FileConfigService fileConfigService = mock(FileConfigService.class);
    private final FileMapper fileMapper = mock(FileMapper.class);
    private final FileArchiveValidator fileArchiveValidator =
            new FileArchiveValidator(new FileArchiveSecurityProperties());
    private final FileDeletionService fileDeletionService = mock(FileDeletionService.class);
    private final FilePresignedUploadService filePresignedUploadService = mock(FilePresignedUploadService.class);

    private final FileBusinessAccessProviderRegistry fileBusinessAccessProviderRegistry =
            new FileBusinessAccessProviderRegistry(List.of());

    private final FileServiceImpl fileService = new FileServiceImpl(
            fileConfigService,
            fileMapper,
            fileArchiveValidator,
            fileDeletionService,
            filePresignedUploadService,
            fileBusinessAccessProviderRegistry);

    @Test
    void getFilePage_delegatesAllFiltersToMapper() {
        PageParam pageParam = new PageParam().setPageNo(2).setPageSize(20);
        LocalDateTime[] createTime = {LocalDateTime.now().minusDays(1), LocalDateTime.now()};
        PageResult<FileDO> expected = new PageResult<>(List.of(new FileDO().setId(1L)), 1L);
        when(fileMapper.selectPage(pageParam, "images", "image/png", createTime))
                .thenReturn(expected);

        PageResult<FileDO> result = fileService.getFilePage(pageParam, "images", "image/png", createTime);

        assertThat(result).isSameAs(expected);
        verify(fileMapper).selectPage(pageParam, "images", "image/png", createTime);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "../escape.png",
                "..\\escape.png",
                "nested/file.png",
                "nested\\file.png",
                "C:\\escape.png",
                "line\nbreak.png"
            })
    void generateUploadPath_withInvalidFileName_rejectsBeforeStorage(String fileName) {
        assertPathInvalid(() -> fileService.generateUploadPath(fileName, "images"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../escape",
                "/absolute",
                "\\absolute",
                "nested\\windows",
                "C:\\absolute",
                ".pending",
                ".pending/nested"
            })
    void generateUploadPath_withInvalidDirectory_rejectsBeforeStorage(String directory) {
        assertPathInvalid(() -> fileService.generateUploadPath("safe.png", directory));
    }

    @ParameterizedTest
    @ValueSource(strings = {"images", "customers/contracts"})
    void generateUploadPath_withRelativeDirectory_keepsPathUnderDirectory(String directory) {
        String path = fileService.generateUploadPath("safe.png", directory);

        assertThat(path).startsWith(directory + "/").endsWith(".png");
    }

    @Test
    void generateUploadPath_sameNameUploadsAlwaysOwnDifferentObjectKeys() {
        List<String> paths = java.util.stream.IntStream.range(0, 2000)
                .parallel()
                .mapToObj(index -> fileService.generateUploadPath("same.png", "images"))
                .toList();
        assertThat(paths).doesNotHaveDuplicates();
        assertThat(paths)
                .allSatisfy(path -> assertThat(path).startsWith("images/").endsWith(".png"));
    }

    @Test
    void generateUploadPath_rejectsMetadataThatCannotFitStorageColumns() {
        assertPathInvalid(() -> fileService.generateUploadPath("a".repeat(257), "images"));
        assertPathInvalid(() -> fileService.generateUploadPath("safe.png", "a".repeat(201)));
    }

    @Test
    void createFile_withoutMasterClient_returnsRegisteredBusinessError() {
        when(fileConfigService.getMasterFileClientForReferenceWrite()).thenReturn(null);
        byte[] png = Base64.getDecoder()
                .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

        assertThatThrownBy(() -> fileService.createFile(
                        png, "safe.png", "images", "image/png", PRINCIPAL, FileAccessTypeEnum.PRIVATE))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(FILE_CLIENT_NOT_EXISTS.getCode());
    }

    @Test
    void presignPutUrl_delegatesServerGeneratedPathAndPrincipal() {
        FileUploadPrincipal principal = PRINCIPAL;

        fileService.presignPutUrl("safe.png", "images", 10L, "image/png", principal, FileAccessTypeEnum.PRIVATE);

        verify(filePresignedUploadService)
                .issue(
                        anyString(),
                        anyString(),
                        org.mockito.ArgumentMatchers.eq(10L),
                        org.mockito.ArgumentMatchers.eq("image/png"),
                        org.mockito.ArgumentMatchers.eq(principal),
                        org.mockito.ArgumentMatchers.eq(FileAccessTypeEnum.PRIVATE));
    }

    @Test
    void createPresignedFile_delegatesOnlyTokenAndPrincipal() {
        FileUploadPrincipal principal = PRINCIPAL;
        when(filePresignedUploadService.complete("a".repeat(64), principal))
                .thenReturn("https://storage.test/safe.png");

        assertThat(fileService.createPresignedFile("a".repeat(64), principal))
                .isEqualTo("https://storage.test/safe.png");
    }

    @Test
    void createFile_whenMetadataInsertFails_deletesUploadedObject() throws Exception {
        FileClient fileClient = mock(FileClient.class);
        when(fileClient.getId()).thenReturn(21L);
        when(fileClient.supportsPrivateRead()).thenReturn(true);
        when(fileClient.upload(any(byte[].class), anyString(), anyString()))
                .thenReturn("https://example.test/safe.png");
        when(fileConfigService.getMasterFileClientForReferenceWrite()).thenReturn(fileClient);
        doThrow(new IllegalStateException("database unavailable"))
                .when(fileMapper)
                .insert(any(FileDO.class));
        byte[] png = Base64.getDecoder()
                .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

        assertThatThrownBy(() -> fileService.createFile(
                        png, "safe.png", "images", "image/png", PRINCIPAL, FileAccessTypeEnum.PRIVATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        verify(fileClient).delete(anyString());
    }

    @Test
    void createFile_withoutOptionalMetadataStillOwnsASeparateObjectForEachUpload() throws Exception {
        FileClient client = mock(FileClient.class);
        when(client.getId()).thenReturn(21L);
        when(client.supportsPrivateRead()).thenReturn(true);
        when(client.upload(any(byte[].class), anyString(), anyString()))
                .thenAnswer(invocation -> "https://storage.test/" + invocation.getArgument(1, String.class));
        when(fileConfigService.getMasterFileClientForReferenceWrite()).thenReturn(client);
        byte[] content = png();

        String first = fileService.createFile(content, null, null, null, PRINCIPAL, FileAccessTypeEnum.PRIVATE);
        String second = fileService.createFile(content, null, null, null, PRINCIPAL, FileAccessTypeEnum.PRIVATE);

        assertThat(first).isNotEqualTo(second);
        ArgumentCaptor<FileDO> files = ArgumentCaptor.forClass(FileDO.class);
        verify(fileMapper, times(2)).insert(files.capture());
        assertThat(files.getAllValues()).allSatisfy(file -> {
            assertThat(file.getName()).isEqualTo(DigestUtil.sha256Hex(content) + ".png");
            assertThat(file.getType()).isEqualTo("image/png");
            assertThat(file.getPath()).matches("[0-9]{8}/[a-f0-9]{64}_[a-f0-9-]{36}\\.png");
            assertThat(file.getUrl()).isEqualTo("https://storage.test/" + file.getPath());
        });
        assertThat(files.getAllValues()).extracting(FileDO::getUrl).containsExactly(first, second);
    }

    @Test
    void createFile_cleanupFailurePreservesTheOriginalPersistenceFailure() throws Exception {
        FileClient client = mock(FileClient.class);
        when(client.getId()).thenReturn(21L);
        when(client.supportsPrivateRead()).thenReturn(true);
        when(client.upload(any(byte[].class), anyString(), anyString())).thenReturn("https://storage.test/safe.png");
        when(fileConfigService.getMasterFileClientForReferenceWrite()).thenReturn(client);
        IllegalStateException persistenceFailure = new IllegalStateException("file metadata storage unavailable");
        IOException cleanupFailure = new IOException("object deletion unavailable");
        when(fileMapper.insert(any(FileDO.class))).thenThrow(persistenceFailure);
        doThrow(cleanupFailure).when(client).delete(anyString());

        assertThatThrownBy(() -> fileService.createFile(
                        png(), "safe.png", "images", "image/png", PRINCIPAL, FileAccessTypeEnum.PRIVATE))
                .isSameAs(persistenceFailure);

        assertThat(persistenceFailure.getSuppressed()).containsExactly(cleanupFailure);
        ArgumentCaptor<FileDO> file = ArgumentCaptor.forClass(FileDO.class);
        verify(fileMapper).insert(file.capture());
        verify(client).delete(file.getValue().getPath());
    }

    @Test
    void deleteFileList_delegatesToResilientDeletionWorkflow() throws Exception {
        List<Long> ids = List.of(1L, 2L);

        fileService.deleteFileList(ids);

        verify(fileDeletionService).deleteFiles(ids);
    }

    @Test
    void deleteFile_delegatesToResilientDeletionWorkflow() throws Exception {
        fileService.deleteFile(1L);

        verify(fileDeletionService).deleteFiles(List.of(1L));
    }

    @Test
    void createFile_persistsAccessTypeAndOwner() throws Exception {
        FileClient fileClient = mock(FileClient.class);
        when(fileClient.getId()).thenReturn(21L);
        when(fileClient.supportsPrivateRead()).thenReturn(true);
        when(fileClient.upload(any(byte[].class), anyString(), anyString()))
                .thenReturn("https://storage.test/images/safe.png");
        when(fileConfigService.getMasterFileClientForReferenceWrite()).thenReturn(fileClient);
        byte[] png = png();

        assertThat(fileService.createFile(
                        png, "safe.png", "images", "image/png", PRINCIPAL, FileAccessTypeEnum.PRIVATE))
                .isEqualTo("https://storage.test/images/safe.png");

        ArgumentCaptor<FileDO> file = ArgumentCaptor.forClass(FileDO.class);
        verify(fileMapper).insert(file.capture());
        assertThat(file.getValue())
                .extracting(FileDO::getAccessType, FileDO::getOwnerUserId, FileDO::getOwnerUserType)
                .containsExactly(FileAccessTypeEnum.PRIVATE.getValue(), 7L, 2);
    }

    @Test
    void createFile_rejectsPrivateFileForPublicStorage() {
        FileClient fileClient = mock(FileClient.class);
        when(fileClient.supportsPrivateRead()).thenReturn(false);
        when(fileConfigService.getMasterFileClientForReferenceWrite()).thenReturn(fileClient);

        assertThatThrownBy(() -> fileService.createFile(
                        png(), "safe.png", "images", "image/png", PRINCIPAL, FileAccessTypeEnum.PRIVATE))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(FILE_PRIVATE_READ_REQUIRES_PRIVATE_STORAGE.getCode()));
    }

    @Test
    void getFileContent_readsOnlyPublicPersistedFile() throws Exception {
        FileDO file = completedFile(FileAccessTypeEnum.PUBLIC, 7L, 2);
        FileClient client = mock(FileClient.class);
        when(fileMapper.selectActiveByConfigIdAndPath(21L, "requested/path.png"))
                .thenReturn(file);
        when(fileConfigService.getFileClient(21L)).thenReturn(client);
        when(client.getContent("persisted/path.png")).thenReturn(new byte[] {1, 2});

        assertThat(fileService.getFileContent(21L, "requested/path.png", new FileAccessPrincipal(null, null, false)))
                .containsExactly(1, 2);
        verify(client).getContent("persisted/path.png");
    }

    @Test
    void getFileContent_rejectsUnauthenticatedAndForeignPrivateFileBeforeStorageLookup() {
        when(fileMapper.selectActiveByConfigIdAndPath(21L, "private/path.png"))
                .thenReturn(completedFile(FileAccessTypeEnum.PRIVATE, 7L, 2));

        assertThatThrownBy(() ->
                        fileService.getFileContent(21L, "private/path.png", new FileAccessPrincipal(null, 2, false)))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(FILE_NOT_EXISTS.getCode()));
        assertThatThrownBy(() ->
                        fileService.getFileContent(21L, "private/path.png", new FileAccessPrincipal(8L, 2, false)))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(FILE_NOT_EXISTS.getCode()));
        verifyNoInteractions(fileConfigService);
    }

    @Test
    void getFileContent_allowsPrivateFileOwnerAndFileManager() throws Exception {
        FileDO file = completedFile(FileAccessTypeEnum.PRIVATE, 7L, 2);
        FileClient client = mock(FileClient.class);
        when(fileMapper.selectActiveByConfigIdAndPath(21L, "private/path.png")).thenReturn(file);
        when(fileConfigService.getFileClient(21L)).thenReturn(client);
        when(client.getContent("persisted/path.png")).thenReturn(new byte[] {7});

        assertThat(fileService.getFileContent(21L, "private/path.png", new FileAccessPrincipal(7L, 2, false)))
                .containsExactly(7);
        assertThat(fileService.getFileContent(21L, "private/path.png", new FileAccessPrincipal(8L, 2, true)))
                .containsExactly(7);
    }

    private static FileDO completedFile(FileAccessTypeEnum accessType, Long ownerUserId, Integer ownerUserType) {
        return new FileDO()
                .setConfigId(21L)
                .setName("path.png")
                .setPath("persisted/path.png")
                .setUrl("https://storage.test/persisted/path.png")
                .setSize(1L)
                .setAccessType(accessType.getValue())
                .setOwnerUserId(ownerUserId)
                .setOwnerUserType(ownerUserType)
                .setUploadStatus(FileMapper.UPLOAD_STATUS_COMPLETE);
    }

    private static byte[] png() {
        return Base64.getDecoder()
                .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    }

    private static void assertPathInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(FILE_PATH_INVALID.getCode());
    }
}
