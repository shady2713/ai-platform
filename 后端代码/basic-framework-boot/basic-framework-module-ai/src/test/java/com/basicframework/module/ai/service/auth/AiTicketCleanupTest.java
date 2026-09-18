package com.basicframework.module.ai.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.ai.dal.dataobject.token.AiAccessTicketDO;
import com.basicframework.module.ai.dal.mysql.token.AiAccessTicketMapper;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A06 失效票据清理：只清"已撤销或过期超过保留期"的票据，按批处理且幂等（重复执行不产生额外影响）。
 */
class AiTicketCleanupTest {

    private final AiApplicationService applicationService = mock(AiApplicationService.class);

    private final AiSubjectService subjectService = mock(AiSubjectService.class);

    private final AiAccessTicketMapper ticketMapper = mock(AiAccessTicketMapper.class);

    private final AiTicketServiceImpl service =
            new AiTicketServiceImpl(applicationService, subjectService, ticketMapper, Duration.ofMinutes(10));

    private static AiAccessTicketDO ticket(long id, String status, LocalDateTime expiresTime) {
        return new AiAccessTicketDO()
                .setId(id)
                .setApplicationId(5L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setTokenDigest("d" + id)
                .setScopeSnapshot("{}")
                .setScopeFingerprint("f".repeat(64))
                .setAuthzRevision(1L)
                .setStatus(status)
                .setExpiresTime(expiresTime)
                .setVersion(0);
    }

    @Test
    void cleansInvalidTicketsInBatchesAndStopsWhenNoCandidates() {
        AiAccessTicketDO revoked =
                ticket(1L, AiAccessTicketDO.STATUS_REVOKED, LocalDateTime.now().minusDays(3));
        AiAccessTicketDO expired =
                ticket(2L, AiAccessTicketDO.STATUS_ACTIVE, LocalDateTime.now().minusDays(3));
        when(ticketMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(revoked, expired))
                .thenReturn(List.of());

        int cleaned = service.cleanInvalidTickets(2, 5, Duration.ofHours(24));

        assertThat(cleaned).isEqualTo(2);
        verify(ticketMapper).deleteById(1L);
        verify(ticketMapper).deleteById(2L);
    }

    @Test
    void cleanupIsIdempotentWhenNothingLeft() {
        when(ticketMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of());

        assertThat(service.cleanInvalidTickets(10, 3, Duration.ofHours(24))).isZero();
        assertThat(service.cleanInvalidTickets(10, 3, Duration.ofHours(24))).isZero();
        verify(ticketMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void cleanupRespectsBatchAndMaxBatchesLimits() {
        List<AiAccessTicketDO> fullBatch = List.of(
                ticket(1L, AiAccessTicketDO.STATUS_REVOKED, LocalDateTime.now().minusDays(2)),
                ticket(2L, AiAccessTicketDO.STATUS_REVOKED, LocalDateTime.now().minusDays(2)));
        when(ticketMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(fullBatch);

        int cleaned = service.cleanInvalidTickets(2, 3, Duration.ofHours(24));

        assertThat(cleaned).as("最多 3 批 × 每批 2 条").isEqualTo(6);
        verify(ticketMapper, times(6)).deleteById(any(Long.class));
    }

    @Test
    void nullOrNegativeRetentionFallsBackToImmediateCleanup() {
        when(ticketMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(ticket(3L, AiAccessTicketDO.STATUS_REVOKED, LocalDateTime.now())))
                .thenReturn(List.of());

        assertThat(service.cleanInvalidTickets(1, 1, null)).isEqualTo(1);
        verify(ticketMapper).deleteById(3L);
    }

    @Test
    void revokeTicketsOfApplicationOnlyTouchesActiveOnes() {
        AiAccessTicketDO active =
                ticket(9L, AiAccessTicketDO.STATUS_ACTIVE, LocalDateTime.now().plusMinutes(5));
        AiAccessTicketDO alreadyRevoked = ticket(10L, AiAccessTicketDO.STATUS_REVOKED, LocalDateTime.now());
        when(ticketMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(active, alreadyRevoked));
        when(ticketMapper.updateWithVersion(any(AiAccessTicketDO.class), eq(0))).thenReturn(1);

        service.revokeTicketsOfApplication(5L);

        verify(ticketMapper)
                .updateWithVersion(
                        org.mockito.ArgumentMatchers.argThat(
                                update -> AiAccessTicketDO.STATUS_REVOKED.equals(update.getStatus())),
                        eq(0));
        verify(ticketMapper, times(1)).updateWithVersion(any(AiAccessTicketDO.class), any());
    }
}
