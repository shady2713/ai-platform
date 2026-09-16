package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import com.basicframework.module.infra.dal.mysql.file.FileMapper;
import com.basicframework.module.infra.enums.file.FileAccessTypeEnum;
import com.basicframework.module.infra.framework.file.core.client.FileClient;
import com.basicframework.module.infra.framework.file.core.client.FileClientFactory;
import com.basicframework.module.infra.framework.file.core.client.FileObjectMetadata;
import com.basicframework.module.infra.framework.file.core.enums.FileStorageEnum;
import com.basicframework.module.infra.service.file.FileConfigService;
import com.basicframework.module.infra.service.file.FileDeletionService;
import com.basicframework.module.infra.service.file.FileService;
import com.basicframework.module.infra.service.file.FileUploadPrincipal;
import com.basicframework.module.infra.service.file.dto.FilePresignedUrlDTO;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实服务代理、MySQL 事务与配置锁；仅以可控对象存储替换外部网络边界。 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FilePresignedUploadPersistenceIT extends AbstractPersistenceIntegrationTest {

    private static final byte[] PNG = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    private static final FileUploadPrincipal PRINCIPAL = new FileUploadPrincipal(1L, UserTypeEnum.ADMIN.getValue());

    @Autowired
    private FileService fileService;

    @Autowired
    private FileConfigService fileConfigService;

    @Autowired
    private FileDeletionService deletionService;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private FileClientFactory fileClientFactory;

    private Long configId;
    private Long originalMasterId;
    private MemoryStorage storage;
    private ExecutorService workers;

    @BeforeEach
    void createIsolatedStorageConfiguration() {
        originalMasterId =
                jdbcTemplate
                        .queryForList(
                                "SELECT id FROM infra_file_config WHERE master = b'1' AND deleted = b'0'", Long.class)
                        .stream()
                        .findFirst()
                        .orElse(null);
        configId = fileConfigService.createFileConfig(
                new FileConfigDO().setName("presigned-integration").setStorage(FileStorageEnum.DB.getStorage()),
                Map.of("domain", "https://storage.test"));
        storage = new MemoryStorage(configId);
        when(fileClientFactory.getFileClient(anyLong())).thenReturn(storage);
        fileConfigService.updateFileConfigMaster(configId);
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void removeIsolatedStorageConfiguration() throws InterruptedException {
        if (workers != null) {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        jdbcTemplate.update("DELETE FROM infra_file WHERE config_id = ?", configId);
        if (originalMasterId != null) {
            fileConfigService.updateFileConfigMaster(originalMasterId);
        } else {
            jdbcTemplate.update("UPDATE infra_file_config SET master = b'0' WHERE id = ?", configId);
        }
        fileConfigService.deleteFileConfig(configId);
        jdbcTemplate.update("DELETE FROM infra_file_config WHERE id = ?", configId);
    }

    @Test
    void realServiceIssuesCompletesAndRetriesWithoutCallerTransaction() {
        FilePresignedUrlDTO issued = issue();
        String stagingPath = stage(issued);

        String url = fileService.createPresignedFile(issued.getUploadToken(), PRINCIPAL);

        assertThat(url).contains(issued.getPath());
        assertThat(storage.objects.get(issued.getPath())).containsExactly(PNG);
        assertThat(storage.objects).doesNotContainKey(stagingPath);
        assertThat(uploadStatus(issued)).isEqualTo(FileMapper.UPLOAD_STATUS_COMPLETE);
        assertThat(fileService.createPresignedFile(issued.getUploadToken(), PRINCIPAL))
                .isEqualTo(url);
    }

    @Test
    void failedPublicationKeepsDurableCleanupAfterItsTransactionRollsBack() {
        FilePresignedUrlDTO issued = issue();
        stage(issued);
        storage.failUploadAfterWrite = true;
        storage.failDelete = true;

        assertThatThrownBy(() -> fileService.createPresignedFile(issued.getUploadToken(), PRINCIPAL))
                .hasRootCauseMessage("storage publish failed");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT delete_status FROM infra_file WHERE config_id = ? AND path = ?",
                        Integer.class,
                        configId,
                        issued.getPath()))
                .isEqualTo(FileMapper.DELETE_STATUS_PENDING);
        assertThat(uploadStatus(issued)).isEqualTo(FileMapper.UPLOAD_STATUS_VALIDATING);
        storage.failUploadAfterWrite = false;
        storage.failDelete = false;
        jdbcTemplate.update("UPDATE infra_file SET delete_next_retry_time = NOW() WHERE config_id = ?", configId);
        assertThat(deletionService.retryPendingFiles()).isEqualTo(1);
        assertThat(storage.objects).isEmpty();
        assertThat(fileCount()).isZero();
    }

    @Test
    void expirySweepWaitsForPublishingTransactionAndDoesNotDeleteCompletedFile() throws Exception {
        FilePresignedUrlDTO issued = issue();
        stage(issued);
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch publish = new CountDownLatch(1);
        storage.afterUpload = () -> {
            publishing.countDown();
            awaitSignal(publish);
        };
        Future<String> completion =
                workers.submit(() -> fileService.createPresignedFile(issued.getUploadToken(), PRINCIPAL));
        try {
            assertThat(publishing.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> sweep = workers.submit(() -> fileMapper.markExpiredUploadsDeletePending(
                    LocalDateTime.now().plusDays(1), 100));
            assertThatThrownBy(() -> sweep.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            publish.countDown();
            assertThat(completion.get(10, TimeUnit.SECONDS)).contains(issued.getPath());
            assertThat(sweep.get(10, TimeUnit.SECONDS)).isZero();
            assertThat(uploadStatus(issued)).isEqualTo(FileMapper.UPLOAD_STATUS_COMPLETE);
            assertThat(storage.objects.get(issued.getPath())).containsExactly(PNG);
        } finally {
            publish.countDown();
        }
    }

    @Test
    void cleanupWinningBeforePublishPreventsRecreatingAnUntrackedFinalObject() throws Exception {
        FilePresignedUrlDTO issued = issue();
        stage(issued);
        CountDownLatch configLocked = new CountDownLatch(1);
        CountDownLatch unlock = new CountDownLatch(1);
        Future<?> holder =
                workers.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbcTemplate.queryForObject(
                            "SELECT id FROM infra_file_config WHERE id = ? FOR UPDATE", Long.class, configId);
                    configLocked.countDown();
                    awaitSignal(unlock);
                }));
        try {
            assertThat(configLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> completion =
                    workers.submit(() -> fileService.createPresignedFile(issued.getUploadToken(), PRINCIPAL));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(uploadStatus(issued))
                    .isEqualTo(FileMapper.UPLOAD_STATUS_VALIDATING));
            jdbcTemplate.update(
                    "UPDATE infra_file SET upload_expires_at = NOW() - INTERVAL 1 SECOND WHERE config_id = ?",
                    configId);
            assertThat(deletionService.retryPendingFiles()).isEqualTo(1);
            unlock.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> completion.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(com.basicframework.framework.common.exception.ServiceException.class);
            assertThat(fileCount()).isZero();
            assertThat(storage.objects).isEmpty();
        } finally {
            unlock.countDown();
        }
    }

    private FilePresignedUrlDTO issue() {
        return fileService.presignPutUrl(
                "safe.png", "images", (long) PNG.length, "image/png", PRINCIPAL, FileAccessTypeEnum.PRIVATE);
    }

    private String stage(FilePresignedUrlDTO issued) {
        String staging = jdbcTemplate.queryForObject(
                "SELECT upload_staging_path FROM infra_file WHERE config_id = ? AND path = ?",
                String.class,
                configId,
                issued.getPath());
        storage.objects.put(staging, PNG.clone());
        return staging;
    }

    private Integer uploadStatus(FilePresignedUrlDTO issued) {
        return jdbcTemplate.queryForObject(
                "SELECT upload_status FROM infra_file WHERE config_id = ? AND path = ?",
                Integer.class,
                configId,
                issued.getPath());
    }

    private Integer fileCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM infra_file WHERE config_id = ?", Integer.class, configId);
    }

    private static void awaitSignal(CountDownLatch signal) {
        try {
            if (!signal.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("文件并发测试未收到释放信号");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文件并发测试被中断", exception);
        }
    }

    private static class MemoryStorage implements FileClient {
        private final Long id;
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private volatile boolean failUploadAfterWrite;
        private volatile boolean failDelete;
        private volatile Runnable afterUpload;

        MemoryStorage(Long id) {
            this.id = id;
        }

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public String upload(byte[] content, String path, String type) throws IOException {
            objects.put(path, content.clone());
            if (afterUpload != null) {
                afterUpload.run();
            }
            if (failUploadAfterWrite) {
                throw new IOException("storage publish failed");
            }
            return presignGetUrl(path, null);
        }

        @Override
        public void delete(String path) throws IOException {
            if (failDelete) {
                throw new IOException("storage deletion failed");
            }
            objects.remove(path);
        }

        @Override
        public byte[] getContent(String path) {
            byte[] content = objects.get(path);
            return content == null ? null : content.clone();
        }

        @Override
        public FileObjectMetadata getMetadata(String path) {
            byte[] content = objects.get(path);
            return content == null ? null : new FileObjectMetadata((long) content.length, "image/png");
        }

        @Override
        public String presignPutUrl(String path, long size, String type, Duration expiration) {
            return "https://storage.test/" + path + "?signature=upload";
        }

        @Override
        public String presignGetUrl(String path, Integer expirationSeconds) {
            return "https://storage.test/" + path + "?signature=read";
        }

        @Override
        public boolean supportsPrivatePresignedUpload() {
            return true;
        }
    }
}
