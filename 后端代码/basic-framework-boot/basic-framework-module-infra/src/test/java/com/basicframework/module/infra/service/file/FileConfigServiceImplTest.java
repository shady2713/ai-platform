package com.basicframework.module.infra.service.file;

import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_CONFIG_DELETE_FAIL_MASTER;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_CONFIG_IN_USE;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_CONFIG_NOT_EXISTS;
import static com.basicframework.module.infra.testutil.ServiceExceptionAssert.assertServiceException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.dal.mysql.file.FileConfigMapper;
import com.basicframework.module.infra.dal.mysql.file.FileContentMapper;
import com.basicframework.module.infra.dal.mysql.file.FileMapper;
import com.basicframework.module.infra.framework.file.core.client.FileClient;
import com.basicframework.module.infra.framework.file.core.client.FileClientFactory;
import com.basicframework.module.infra.framework.file.core.client.db.DBFileClientConfig;
import com.basicframework.module.infra.framework.file.core.client.local.LocalFileClientConfig;
import com.basicframework.module.infra.framework.file.core.client.s3.S3FileClientConfig;
import com.basicframework.module.infra.framework.file.core.enums.FileStorageEnum;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** {@link FileConfigServiceImpl} 文件客户端缓存边界测试。 */
class FileConfigServiceImplTest {

    private final FileClientFactory fileClientFactory = mock(FileClientFactory.class);
    private final FileConfigMapper fileConfigMapper = mock(FileConfigMapper.class);
    private final FileMapper fileMapper = mock(FileMapper.class);
    private final FileContentMapper fileContentMapper = mock(FileContentMapper.class);
    private final CredentialCipher credentialCipher = mock(CredentialCipher.class);
    private final Validator validator = mock(Validator.class);
    private final FileConfigCredentialCodec credentialCodec =
            new FileConfigCredentialCodec(validator, credentialCipher);
    private final FileConfigServiceImpl fileConfigService = new FileConfigServiceImpl(
            fileClientFactory, fileConfigMapper, fileMapper, fileContentMapper, credentialCodec);

    @Test
    void updateFileConfig_withoutId_rejectsBeforeReadingCredentials() {
        FileConfigDO command = new FileConfigDO().setName("missing-id");

        assertThatThrownBy(() -> fileConfigService.updateFileConfig(command, Map.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(FILE_CONFIG_NOT_EXISTS.getCode());

        verifyNoInteractions(fileConfigMapper, credentialCipher);
    }

    @Test
    void updateFileConfig_withoutPersistedConfig_rejectsBeforeReadingCredentials() {
        FileConfigDO command = new FileConfigDO().setId(20L).setName("missing-config");
        when(fileConfigMapper.selectByIdForUpdate(20L)).thenReturn(null);

        assertServiceException(
                FILE_CONFIG_NOT_EXISTS.getCode(), () -> fileConfigService.updateFileConfig(command, Map.of()));

        verifyNoInteractions(credentialCipher);
    }

    @Test
    void createFileConfig_encryptsSerializedConfigAndDoesNotPersistPlaintextObject() {
        FileConfigDO command = new FileConfigDO()
                .setStorage(FileStorageEnum.LOCAL.getStorage())
                .setName("local");
        when(credentialCipher.encrypt(anyString(), eq("file-client:config"))).thenReturn("v1.encrypted.config");

        fileConfigService.createFileConfig(
                command, Map.of("basePath", "data/files", "domain", "https://files.example.test"));

        assertThat(command.getConfig()).isNull();
        assertThat(command.getConfigCiphertext()).isEqualTo("v1.encrypted.config");
        verify(fileConfigMapper).insert(command);
    }

    @Test
    void updateFileConfig_blankS3Secret_preservesExistingSecretBeforeEncryption() {
        FileConfigDO existing = new FileConfigDO()
                .setId(21L)
                .setStorage(FileStorageEnum.S3.getStorage())
                .setMaster(true)
                .setConfigCiphertext("v1.existing.config");
        when(fileConfigMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        when(credentialCipher.decrypt("v1.existing.config", "file-client:config"))
                .thenReturn(s3ConfigJson("existing-secret"));
        when(credentialCipher.encrypt(anyString(), eq("file-client:config"))).thenReturn("v1.updated.config");
        FileConfigDO command = new FileConfigDO().setId(21L).setName("s3");
        fileConfigService.getClientCache().put(0L, Optional.of(mock(FileClient.class)));

        fileConfigService.updateFileConfig(command, s3ConfigMap(""));

        ArgumentCaptor<String> plaintextCaptor = ArgumentCaptor.forClass(String.class);
        verify(credentialCipher).encrypt(plaintextCaptor.capture(), eq("file-client:config"));
        assertThat(plaintextCaptor.getValue()).contains("existing-secret");
        assertThat(command.getStorage()).isEqualTo(FileStorageEnum.S3.getStorage());
        assertThat(command.getConfigCiphertext()).isEqualTo("v1.updated.config");
        assertThat(command.getConfig()).isNull();
        verify(fileConfigMapper).updateById(command);
        verify(fileClientFactory).removeFileClient(21L);
        assertThat(fileConfigService.getClientCache().asMap()).doesNotContainKey(0L);
    }

    @Test
    void getMasterFileClient_withoutMaster_returnsNullWithoutCallingFactory() {
        when(fileConfigMapper.selectByMaster()).thenReturn(null);

        assertThat(fileConfigService.getMasterFileClient()).isNull();

        verifyNoInteractions(fileClientFactory);
    }

    @Test
    void getFileClient_withoutConfig_returnsNullWithoutCallingFactory() {
        when(fileConfigMapper.selectById(42L)).thenReturn(null);

        assertThat(fileConfigService.getFileClient(42L)).isNull();

        verifyNoInteractions(fileClientFactory);
    }

    @Test
    void getMasterFileClient_withConfig_initializesAndCachesClient() {
        LocalFileClientConfig clientConfig = new LocalFileClientConfig();
        Integer storage = FileStorageEnum.LOCAL.getStorage();
        FileConfigDO config = new FileConfigDO().setId(7L).setStorage(storage).setConfig(clientConfig);
        FileClient client = mock(FileClient.class);
        when(fileConfigMapper.selectByMaster()).thenReturn(config);
        when(fileClientFactory.getFileClient(7L)).thenReturn(client);

        assertThat(fileConfigService.getMasterFileClient()).isSameAs(client);
        assertThat(fileConfigService.getMasterFileClient()).isSameAs(client);

        verify(fileClientFactory).createOrUpdateFileClient(7L, storage, clientConfig);
        verify(fileClientFactory).getFileClient(7L);
        verify(fileConfigMapper).selectByMaster();
    }

    @Test
    void updateFileConfigMaster_promotesAndInvalidatesMasterCache() {
        when(fileConfigMapper.selectById(30L))
                .thenReturn(new FileConfigDO().setId(30L).setMaster(false));
        fileConfigService.getClientCache().put(0L, Optional.of(mock(FileClient.class)));

        fileConfigService.updateFileConfigMaster(30L);

        verify(fileConfigMapper).updateBatch(new FileConfigDO().setMaster(false));
        verify(fileConfigMapper).updateById(new FileConfigDO().setId(30L).setMaster(true));
        assertThat(fileConfigService.getClientCache().asMap()).doesNotContainKey(0L);
    }

    @Test
    void getFileClientForReferenceWrite_withConfig_usesLockedConfig() {
        LocalFileClientConfig clientConfig = new LocalFileClientConfig();
        Integer storage = FileStorageEnum.LOCAL.getStorage();
        FileConfigDO config = new FileConfigDO().setId(8L).setStorage(storage).setConfig(clientConfig);
        FileClient client = mock(FileClient.class);
        when(fileConfigMapper.selectByIdForShare(8L)).thenReturn(config);
        when(fileClientFactory.getFileClient(8L)).thenReturn(client);

        assertThat(fileConfigService.getFileClientForReferenceWrite(8L)).isSameAs(client);

        verify(fileClientFactory).createOrUpdateFileClient(8L, storage, clientConfig);
    }

    @Test
    void deleteFileConfig_withoutConfig_failsClosed() {
        when(fileConfigMapper.selectByIdForUpdate(9L)).thenReturn(null);

        assertServiceException(FILE_CONFIG_NOT_EXISTS.getCode(), () -> fileConfigService.deleteFileConfig(9L));
    }

    @Test
    void deleteFileConfig_withMasterConfig_rejectsDeletion() {
        when(fileConfigMapper.selectByIdForUpdate(10L))
                .thenReturn(new FileConfigDO().setId(10L).setMaster(true));

        assertServiceException(FILE_CONFIG_DELETE_FAIL_MASTER.getCode(), () -> fileConfigService.deleteFileConfig(10L));
    }

    @Test
    void deleteFileConfig_withFileReference_rejectsDeletion() {
        when(fileConfigMapper.selectByIdForUpdate(11L))
                .thenReturn(new FileConfigDO().setId(11L).setMaster(false));
        when(fileMapper.selectCountByConfigId(11L)).thenReturn(1L);

        assertServiceException(FILE_CONFIG_IN_USE.getCode(), () -> fileConfigService.deleteFileConfig(11L));
    }

    @Test
    void deleteFileConfig_withContentReference_rejectsDeletion() {
        when(fileConfigMapper.selectByIdForUpdate(12L))
                .thenReturn(new FileConfigDO().setId(12L).setMaster(false));
        when(fileContentMapper.selectCountByConfigId(12L)).thenReturn(1L);

        assertServiceException(FILE_CONFIG_IN_USE.getCode(), () -> fileConfigService.deleteFileConfig(12L));
    }

    @Test
    void deleteFileConfig_withoutReferences_softDeletesAndClearsCache() {
        when(fileConfigMapper.selectByIdForUpdate(13L))
                .thenReturn(new FileConfigDO().setId(13L).setMaster(false));

        fileConfigService.deleteFileConfig(13L);

        verify(fileConfigMapper).deleteById(13L);
        verify(fileClientFactory).removeFileClient(13L);
        assertThat(fileConfigService.getClientCache().asMap()).doesNotContainKey(13L);
    }

    @Test
    void deleteFileConfigList_withDuplicateUnsortedIds_locksAndDeletesDistinctIdsInOrder() {
        when(fileConfigMapper.selectByIdForUpdate(15L))
                .thenReturn(new FileConfigDO().setId(15L).setMaster(false));
        when(fileConfigMapper.selectByIdForUpdate(16L))
                .thenReturn(new FileConfigDO().setId(16L).setMaster(false));

        fileConfigService.deleteFileConfigList(List.of(16L, 15L, 16L));

        InOrder inOrder = org.mockito.Mockito.inOrder(fileConfigMapper);
        inOrder.verify(fileConfigMapper).selectByIdForUpdate(15L);
        inOrder.verify(fileConfigMapper).selectByIdForUpdate(16L);
        inOrder.verify(fileConfigMapper).deleteByIds(List.of(15L, 16L));
        verify(fileClientFactory).removeFileClient(15L);
        verify(fileClientFactory).removeFileClient(16L);
    }

    @Test
    void testFileConfig_withDatabaseClient_recordsUploadedFileForManagedCleanup() throws Exception {
        FileConfigDO config = new FileConfigDO()
                .setId(14L)
                .setStorage(FileStorageEnum.DB.getStorage())
                .setConfig(new DBFileClientConfig());
        FileClient client = mock(FileClient.class);
        when(fileConfigMapper.selectByIdForShare(14L)).thenReturn(config);
        when(fileClientFactory.getFileClient(14L)).thenReturn(client);
        when(client.getId()).thenReturn(14L);
        when(client.upload(any(byte[].class), anyString(), eq("image/jpeg"))).thenReturn("http://localhost/test.jpg");

        assertThat(fileConfigService.testFileConfig(14L)).isEqualTo("http://localhost/test.jpg");

        ArgumentCaptor<FileDO> fileCaptor = ArgumentCaptor.forClass(FileDO.class);
        verify(fileMapper).insert(fileCaptor.capture());
        assertThat(fileCaptor.getValue())
                .extracting(FileDO::getConfigId, FileDO::getName, FileDO::getUrl, FileDO::getType)
                .containsExactly(14L, "erweima.jpg", "http://localhost/test.jpg", "image/jpeg");
        assertThat(fileCaptor.getValue().getSize()).isPositive();
    }

    private static Map<String, Object> s3ConfigMap(String accessSecret) {
        return Map.of(
                "endpoint", "https://s3.example.test",
                "domain", "https://files.example.test",
                "bucket", "bucket",
                "accessKey", "access-key",
                "accessSecret", accessSecret,
                "enablePathStyleAccess", false,
                "enablePublicAccess", false);
    }

    private static String s3ConfigJson(String accessSecret) {
        S3FileClientConfig config = new S3FileClientConfig();
        config.setEndpoint("https://s3.example.test");
        config.setDomain("https://files.example.test");
        config.setBucket("bucket");
        config.setAccessKey("access-key");
        config.setAccessSecret(accessSecret);
        config.setEnablePathStyleAccess(false);
        config.setEnablePublicAccess(false);
        return com.basicframework.framework.common.util.json.JsonUtils.toJsonString(config);
    }
}
