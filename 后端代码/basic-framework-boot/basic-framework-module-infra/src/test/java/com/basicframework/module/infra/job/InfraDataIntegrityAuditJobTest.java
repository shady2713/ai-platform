package com.basicframework.module.infra.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.infra.service.integrity.InfraDataIntegrityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link InfraDataIntegrityAuditJob} 单元测试
 *
 */
@ExtendWith(MockitoExtension.class)
class InfraDataIntegrityAuditJobTest {

    @InjectMocks
    private InfraDataIntegrityAuditJob job;

    @Mock
    private InfraDataIntegrityService integrityService;

    @Test
    void execute_returnsIntegritySummaryFromService() {
        when(integrityService.verifyLogicalReferences()).thenReturn("infra 引用完整");

        assertThat(job.execute("infra-audit")).isEqualTo("infra 引用完整");
        verify(integrityService).verifyLogicalReferences();
    }
}
