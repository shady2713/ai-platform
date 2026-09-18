package com.basicframework.module.ai.service.authorization;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.grant.AiResourceGrantDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A06 撤销命令：应用撤销与主体撤销都要覆盖"停用 + 撤销全部授权 + 撤销全部票据"。
 */
class AiRevocationServiceImplTest {

    private AiApplicationService applicationService;

    private AiSubjectService subjectService;

    private AiResourceGrantService grantService;

    private AiTicketService ticketService;

    private AiRevocationServiceImpl service;

    @BeforeEach
    void setUp() {
        applicationService = mock(AiApplicationService.class);
        subjectService = mock(AiSubjectService.class);
        grantService = mock(AiResourceGrantService.class);
        ticketService = mock(AiTicketService.class);
        service = new AiRevocationServiceImpl(applicationService, subjectService, grantService, ticketService);
    }

    private static AiResourceGrantDO grant(long id, int version) {
        return new AiResourceGrantDO()
                .setId(id)
                .setApplicationId(5L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setResourceType("REPORT")
                .setResourceKey("report-1")
                .setActions("READ")
                .setStatus(AiResourceGrantDO.STATUS_ACTIVE)
                .setAuthzRevision(1L)
                .setVersion(version);
    }

    @Test
    void revokeApplicationDisablesRevokesGrantsAndTickets() {
        when(grantService.getGrantPage(any(), eq(5L), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(grant(1L, 0)), 1L));

        service.revokeApplication(5L, 3);

        verify(applicationService).updateStatus(5L, 3, false);
        verify(grantService).revokeGrant(1L, 0);
        verify(ticketService).revokeTicketsOfApplication(5L);
    }

    @Test
    void revokeApplicationWalksAllPagesUntilTotalReached() {
        // 首页满页（100 条）→ 必须继续翻页，直到 total 覆盖
        List<AiResourceGrantDO> firstPage = new java.util.ArrayList<>();
        for (int index = 0; index < 100; index++) {
            firstPage.add(grant(index + 1L, 0));
        }
        when(grantService.getGrantPage(any(), eq(5L), any(), any(), any()))
                .thenReturn(new PageResult<>(firstPage, 101L))
                .thenReturn(new PageResult<>(List.of(grant(101L, 0)), 101L));

        service.revokeApplication(5L, 1);

        verify(grantService, org.mockito.Mockito.times(101)).revokeGrant(any(), eq(0));
    }

    @Test
    void revokeSubjectUsesNormalizedExternalUserIdAndRevokesItsOwnTickets() {
        when(grantService.getGrantPage(any(), eq(5L), eq("USER"), eq("alice"), any()))
                .thenReturn(new PageResult<>(List.of(grant(2L, 7)), 1L));

        service.revokeSubject(5L, AiSubjectType.USER, "alice", 4);

        verify(subjectService).disableSubject(5L, AiSubjectType.USER, "alice");
        verify(grantService).revokeGrant(2L, 7);
        verify(ticketService).revokeTickets(5L, AiSubjectType.USER, "alice");
        verify(applicationService, never()).updateStatus(any(), any(), any());
    }

    @Test
    void alreadyRevokedGrantsAreSkippedAndInvalidParamsRejected() {
        AiResourceGrantDO revoked = grant(3L, 2).setStatus(AiResourceGrantDO.STATUS_REVOKED);
        when(grantService.getGrantPage(any(), eq(5L), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(revoked), 1L));

        service.revokeApplication(5L, 1);
        verify(grantService, never()).revokeGrant(eq(3L), any());

        assertThatThrownBy(() -> service.revokeApplication(null, 1))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertThatThrownBy(() -> service.revokeSubject(5L, null, "alice", 1)).isInstanceOf(ServiceException.class);
    }
}
