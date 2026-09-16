package com.basicframework.module.infra.service.file;

import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_NOT_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.dal.mysql.file.FileMapper;
import com.basicframework.module.infra.framework.file.config.FileDeletionProperties;
import com.basicframework.module.infra.framework.file.core.client.FileClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class FileDeletionServiceTest {

    private final FileConfigService fileConfigService = mock(FileConfigService.class);
    private final FileMapper fileMapper = mock(FileMapper.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final FileDeletionProperties properties = new FileDeletionProperties();
    private final FileDeletionService service =
            new FileDeletionService(fileConfigService, fileMapper, properties, transactionManager);

    @BeforeEach
    void setUpTransaction() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
    }

    @Test
    void deleteFiles_marksPendingBeforeDeletingExternalObjects() throws Exception {
        FileDO first = pendingCandidate(1L, "images/a.png");
        FileDO second = pendingCandidate(2L, "images/b.png");
        FileClient client = mock(FileClient.class);
        when(fileMapper.selectActiveByIdsForUpdate(List.of(1L, 2L))).thenReturn(List.of(first, second));
        when(fileMapper.markDeletePending(List.of(1L, 2L))).thenReturn(2);
        when(fileConfigService.getFileClient(21L)).thenReturn(client);

        service.deleteFiles(List.of(1L, 2L));

        verify(client).delete("images/a.png");
        verify(client).delete("images/b.png");
        verify(fileMapper).deletePendingById(1L);
        verify(fileMapper).deletePendingById(2L);
    }

    @Test
    void deleteFiles_storageFailurePersistsRetryAndContinuesBatch() throws Exception {
        FileDO first = pendingCandidate(1L, "images/a.png");
        FileDO second = pendingCandidate(2L, "images/b.png");
        FileClient client = mock(FileClient.class);
        when(fileMapper.selectActiveByIdsForUpdate(List.of(1L, 2L))).thenReturn(List.of(first, second));
        when(fileMapper.markDeletePending(List.of(1L, 2L))).thenReturn(2);
        when(fileConfigService.getFileClient(21L)).thenReturn(client);
        doThrow(new IllegalStateException("storage unavailable")).when(client).delete("images/a.png");

        service.deleteFiles(List.of(1L, 2L));

        ArgumentCaptor<LocalDateTime> retryTime = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(fileMapper).recordDeleteFailure(eq(1L), retryTime.capture(), eq("IllegalStateException"));
        assertThat(retryTime.getValue()).isAfter(LocalDateTime.now());
        verify(fileMapper, never()).deletePendingById(1L);
        verify(fileMapper).deletePendingById(2L);
    }

    @Test
    void deleteFiles_missingOrAlreadyPendingTargetRollsBackPreparation() {
        when(fileMapper.selectActiveByIdsForUpdate(List.of(1L, 2L)))
                .thenReturn(List.of(pendingCandidate(1L, "images/a.png")));

        assertThatThrownBy(() -> service.deleteFiles(List.of(1L, 2L)))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(FILE_NOT_EXISTS.getCode()));

        verify(fileMapper, never()).markDeletePending(any());
        verify(transactionManager).rollback(any(TransactionStatus.class));
    }

    @Test
    void retryPendingFiles_usesConfiguredBatchAndCapsBackoff() {
        properties.setBatchSize(25);
        properties.setInitialRetryDelay(Duration.ofMinutes(1));
        properties.setMaxRetryDelay(Duration.ofMinutes(10));
        FileDO pending = pendingCandidate(7L, "images/pending.png").setDeleteAttempts(20);
        when(fileMapper.selectPendingDeletion(any(LocalDateTime.class), eq(25))).thenReturn(List.of(pending));
        when(fileConfigService.getFileClient(21L)).thenReturn(null);

        assertThat(service.retryPendingFiles()).isZero();

        verify(fileMapper).markExpiredUploadsDeletePending(any(LocalDateTime.class), eq(25));
        ArgumentCaptor<LocalDateTime> retryTime = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(fileMapper).recordDeleteFailure(eq(7L), retryTime.capture(), eq("ServiceException"));
        assertThat(retryTime.getValue()).isBefore(LocalDateTime.now().plusMinutes(11));
    }

    @Test
    void deleteIncompleteFile_removesStagingAndPublishedPaths() throws Exception {
        FileDO incomplete = pendingCandidate(8L, "images/final.png")
                .setUploadStatus(FileMapper.UPLOAD_STATUS_VALIDATING)
                .setUploadStagingPath(".pending/staging-object");
        FileClient client = mock(FileClient.class);
        when(fileMapper.markIncompleteUploadDeletePending(8L)).thenReturn(1);
        when(fileConfigService.getFileClient(21L)).thenReturn(client);

        service.deleteIncompleteFile(incomplete);

        verify(client).delete(".pending/staging-object");
        verify(client).delete("images/final.png");
        verify(fileMapper).deletePendingById(8L);
    }

    private static FileDO pendingCandidate(Long id, String path) {
        return new FileDO().setId(id).setConfigId(21L).setPath(path).setDeleteAttempts(0);
    }
}
