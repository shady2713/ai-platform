package com.basicframework.module.infra.framework.file.core.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.basicframework.module.infra.dal.dataobject.file.FileContentDO;
import com.basicframework.module.infra.dal.mysql.file.FileContentMapper;
import com.basicframework.module.infra.framework.file.core.client.db.DBFileClientConfig;
import com.basicframework.module.infra.framework.file.core.client.local.LocalFileClient;
import com.basicframework.module.infra.framework.file.core.client.local.LocalFileClientConfig;
import com.basicframework.module.infra.framework.file.core.enums.FileStorageEnum;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class FileClientFactoryImplTest {

    @TempDir
    private Path tempDir;

    @Test
    void databaseClient_usesFactoryMapper() throws Exception {
        FileContentMapper fileContentMapper = mock(FileContentMapper.class);
        FileClientFactory factory = new FileClientFactoryImpl(fileContentMapper);
        long configId = 42L;
        factory.createOrUpdateFileClient(
                configId,
                FileStorageEnum.DB.getStorage(),
                new DBFileClientConfig().setDomain("http://localhost/files"));

        FileClient client = factory.getFileClient(configId);
        String url = client.upload(new byte[] {1, 2, 3}, "integration/test.txt", "text/plain");

        assertThat(url).isEqualTo("http://localhost/files/admin-api/infra/file/42/get/integration/test.txt");
        ArgumentCaptor<FileContentDO> captor = ArgumentCaptor.forClass(FileContentDO.class);
        verify(fileContentMapper).insert(captor.capture());
        assertThat(captor.getValue().getConfigId()).isEqualTo(configId);
        assertThat(captor.getValue().getPath()).isEqualTo("integration/test.txt");
        assertThat(captor.getValue().getContent()).containsExactly(1, 2, 3);
    }

    @Test
    void storageTypeChange_replacesTheOldClientClass() {
        FileClientFactory factory = new FileClientFactoryImpl(mock(FileContentMapper.class));
        factory.createOrUpdateFileClient(
                7L,
                FileStorageEnum.LOCAL.getStorage(),
                new LocalFileClientConfig().setBasePath(tempDir.toString()).setDomain("https://files.example.com"));
        FileClient localClient = factory.getFileClient(7L);

        factory.createOrUpdateFileClient(
                7L,
                FileStorageEnum.DB.getStorage(),
                new DBFileClientConfig().setDomain("https://db-files.example.com"));

        assertThat(localClient).isInstanceOf(LocalFileClient.class);
        assertThat(factory.getFileClient(7L)).isNotSameAs(localClient);
    }

    @Test
    void removeAndDestroy_closeRegisteredClients() {
        FileClientFactoryImpl factory = new FileClientFactoryImpl(mock(FileContentMapper.class));
        TrackingFileClient first = new TrackingFileClient(1L, config("https://one.example.com"));
        TrackingFileClient second = new TrackingFileClient(2L, config("https://two.example.com"));
        registeredClients(factory).put(first.getId(), first);
        registeredClients(factory).put(second.getId(), second);

        factory.removeFileClient(first.getId());
        factory.destroy();

        assertThat(first.closed).isTrue();
        assertThat(second.closed).isTrue();
        assertThat(factory.getFileClient(first.getId())).isNull();
        assertThat(factory.getFileClient(second.getId())).isNull();
    }

    @Test
    void failedRefresh_keepsThePreviousUsableConfiguration() {
        TrackingFileClient client = new TrackingFileClient(3L, config("https://stable.example.com"));
        client.init();
        client.failInitialization = true;

        assertThatThrownBy(() -> client.refresh(config("https://broken.example.com")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("initialization failed");

        assertThat(client.currentDomain()).isEqualTo("https://stable.example.com");
        assertThat(client.initializationCount).isEqualTo(2);
    }

    @Test
    void sameStorageUpdate_refreshesExistingClientInPlace() {
        FileClientFactory factory = new FileClientFactoryImpl(mock(FileContentMapper.class));
        factory.createOrUpdateFileClient(
                7L,
                FileStorageEnum.LOCAL.getStorage(),
                new LocalFileClientConfig().setBasePath(tempDir.toString()).setDomain("https://one.example.com"));
        FileClient first = factory.getFileClient(7L);

        factory.createOrUpdateFileClient(
                7L,
                FileStorageEnum.LOCAL.getStorage(),
                new LocalFileClientConfig().setBasePath(tempDir.toString()).setDomain("https://two.example.com"));

        assertThat(factory.getFileClient(7L)).isSameAs(first);
    }

    @Test
    void removeFileClient_withNullId_isNoop() {
        FileClientFactoryImpl factory = new FileClientFactoryImpl(mock(FileContentMapper.class));

        factory.removeFileClient(null);

        assertThat(registeredClients(factory)).isEmpty();
    }

    @Test
    void closeFailures_areSwallowedAndClientsRemoved() {
        FileClientFactoryImpl factory = new FileClientFactoryImpl(mock(FileContentMapper.class));
        TrackingFileClient failing = new TrackingFileClient(5L, config("https://fail.example.com"));
        failing.closeFailure = new IllegalStateException("close failed");
        registeredClients(factory).put(failing.getId(), failing);

        factory.destroy();

        assertThat(failing.closed).isTrue();
        assertThat(registeredClients(factory)).isEmpty();
    }

    @Test
    void invalidFactoryArguments_failBeforeRegistration() {
        FileClientFactory factory = new FileClientFactoryImpl(mock(FileContentMapper.class));

        assertThatThrownBy(() -> factory.createOrUpdateFileClient(
                        1L, 999, new DBFileClientConfig().setDomain("https://files.example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
        assertThatThrownBy(() -> factory.createOrUpdateFileClient(
                        null,
                        FileStorageEnum.DB.getStorage(),
                        new DBFileClientConfig().setDomain("https://files.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<Long, AbstractFileClient<?>> registeredClients(FileClientFactoryImpl factory) {
        return (ConcurrentMap<Long, AbstractFileClient<?>>) ReflectionTestUtils.getField(factory, "clients");
    }

    private static DBFileClientConfig config(String domain) {
        return new DBFileClientConfig().setDomain(domain);
    }

    private static final class TrackingFileClient extends AbstractFileClient<DBFileClientConfig> {

        private int initializationCount;
        private boolean failInitialization;
        private boolean closed;
        private RuntimeException closeFailure;

        private TrackingFileClient(Long id, DBFileClientConfig config) {
            super(id, config);
        }

        @Override
        protected void doInit() {
            initializationCount++;
            if (failInitialization) {
                throw new IllegalStateException("initialization failed");
            }
        }

        private String currentDomain() {
            return config.getDomain();
        }

        @Override
        public String upload(byte[] content, String path, String type) {
            return currentDomain() + "/" + path;
        }

        @Override
        public void delete(String path) {}

        @Override
        public byte[] getContent(String path) {
            return new byte[0];
        }

        @Override
        public void close() {
            closed = true;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
