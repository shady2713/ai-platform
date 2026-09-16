package com.basicframework.module.system.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.service.integrity.SystemDataIntegrityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link SystemDataIntegrityAuditJob} 单元测试
 *
 */
@ExtendWith(MockitoExtension.class)
class SystemDataIntegrityAuditJobTest {

    @InjectMocks
    private SystemDataIntegrityAuditJob job;

    @Mock
    private SystemDataIntegrityService integrityService;

    @Test
    void execute_returnsIntegritySummaryFromService() {
        when(integrityService.verifyLogicalReferences()).thenReturn("system 引用完整");

        assertThat(job.execute("system-audit")).isEqualTo("system 引用完整");
        verify(integrityService).verifyLogicalReferences();
    }
}
