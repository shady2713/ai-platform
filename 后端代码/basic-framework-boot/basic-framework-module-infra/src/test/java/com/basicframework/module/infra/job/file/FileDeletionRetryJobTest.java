package com.basicframework.module.infra.job.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.infra.service.file.FileDeletionService;
import org.junit.jupiter.api.Test;

class FileDeletionRetryJobTest {

    @Test
    void execute_returnsObservableCleanupSummary() {
        FileDeletionService service = mock(FileDeletionService.class);
        when(service.retryPendingFiles()).thenReturn(3);

        String result = new FileDeletionRetryJob(service).execute("");

        assertThat(result).isEqualTo("文件待删除记录清理完成: deleted=3");
        verify(service).retryPendingFiles();
    }
}
