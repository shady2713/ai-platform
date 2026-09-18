package com.basicframework.module.ai.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.ai.service.auth.AiTicketService;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** A06 清理任务：把配置的批大小/批次数/保留期原样交给服务，并给出可读结果。 */
class AiTicketCleanupJobTest {

    private final AiTicketService ticketService = mock(AiTicketService.class);

    @Test
    void executeDelegatesConfiguredBoundsAndReportsCount() {
        when(ticketService.cleanInvalidTickets(50, 4, Duration.ofHours(12))).thenReturn(7);
        AiTicketCleanupJob job = new AiTicketCleanupJob(ticketService, 50, 4, Duration.ofHours(12));

        String result = job.execute(null);

        verify(ticketService).cleanInvalidTickets(50, 4, Duration.ofHours(12));
        assertThat(result).isEqualTo("清理失效访问票据 7 条");
    }

    @Test
    void executeRemainsIdempotentWhenNothingToClean() {
        when(ticketService.cleanInvalidTickets(200, 10, Duration.ofHours(24))).thenReturn(0);
        AiTicketCleanupJob job = new AiTicketCleanupJob(ticketService, 200, 10, Duration.ofHours(24));

        assertThat(job.execute("ignored")).isEqualTo("清理失效访问票据 0 条");
        assertThat(job.execute("ignored")).isEqualTo("清理失效访问票据 0 条");
    }
}
