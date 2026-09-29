package com.basicframework.module.ai.controller.app.v1.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportShareCreateReqVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportShareCreateRespVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportSharePageReqVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportShareReadRespVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportShareRevokeReqVO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportShareAccessDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportShareDO;
import com.basicframework.module.ai.service.report.share.AiReportShareService;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareCreateDTO;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareCreateResultDTO;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareReadDTO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 报表受控分享协议层契约（X11）：VO 只做映射、请求体不携带归属字段、明文令牌只经创建响应返回一次。
 */
class AiReportShareControllerTest {

    private final AiReportShareService shareService = mock(AiReportShareService.class);

    private final AiReportShareController controller = new AiReportShareController(shareService);

    @Test
    void createMapsTheRequestWithoutOwnershipFieldsAndReturnsTheTokenOnce() {
        when(shareService.create(any(AiReportShareCreateDTO.class)))
                .thenReturn(new AiReportShareCreateResultDTO()
                        .setShareId(501L)
                        .setToken("plain-token")
                        .setVersionNo(2)
                        .setGranteeExternalUserId("bob")
                        .setGranteeDisplayName("Bob")
                        .setExpiresTime(LocalDateTime.of(2026, 10, 1, 0, 0)));

        CommonResult<AiReportShareCreateRespVO> result = controller.create(
                new AiReportShareCreateReqVO().setReportId(100L).setGranteeExternalUserId("bob"));

        ArgumentCaptor<AiReportShareCreateDTO> captor = ArgumentCaptor.forClass(AiReportShareCreateDTO.class);
        verify(shareService).create(captor.capture());
        // 请求体只描述"分享给谁"，归属完全来自服务端会话
        assertThat(captor.getValue().getReportId()).isEqualTo(100L);
        assertThat(captor.getValue().getGranteeExternalUserId()).isEqualTo("bob");
        assertThat(result.getData().getToken()).isEqualTo("plain-token");
        assertThat(result.getData().getShareId()).isEqualTo(501L);
    }

    @Test
    void revokeDelegatesToTheServiceCas() {
        CommonResult<Boolean> result = controller.revoke(
                new AiReportShareRevokeReqVO().setShareId(501L).setVersion(3));

        verify(shareService).revoke(501L, 3);
        assertThat(result.getData()).isTrue();
    }

    @Test
    void pageExposesTheGranteeScopeButNeverTheTokenDigest() {
        AiReportShareDO share = new AiReportShareDO()
                .setId(501L)
                .setReportId(100L)
                .setVersionNo(2)
                .setStatus(AiReportShareDO.STATUS_ACTIVE)
                .setGranteeExternalUserId("bob")
                .setGranteeDisplayName("Bob")
                .setTokenHash("digest-should-not-appear");
        when(shareService.getSharePage(any(PageParam.class))).thenReturn(new PageResult<>(List.of(share), 1L));

        CommonResult<PageResult<com.basicframework.module.ai.controller.app.v1.report.vo.AiReportShareRespVO>> page =
                controller.page(new AiReportSharePageReqVO());

        assertThat(page.getData().getTotal()).isEqualTo(1L);
        assertThat(page.getData().getList().get(0).getGranteeExternalUserId()).isEqualTo("bob");
        assertThat(page.getData().getList().get(0).getGranteeDisplayName()).isEqualTo("Bob");
        // 凭据摘要是对内字段，不属于接收范围协议
        assertThat(page.getData().getList().get(0).toString()).doesNotContain("digest-should-not-appear");
    }

    @Test
    void readMapsContentAndDegradedStateVerbatim() {
        when(shareService.readByToken("plain-token"))
                .thenReturn(new AiReportShareReadDTO()
                        .setShareId(501L)
                        .setReportId(100L)
                        .setReportName("Q3 汇总报表")
                        .setVersionNo(2)
                        .setMode("SNAPSHOT")
                        .setContentAuthorized(false)
                        .setReasonCode(AiReportShareAccessDO.REASON_SCOPE_UNCOVERED));

        CommonResult<AiReportShareReadRespVO> result = controller.read("plain-token");

        assertThat(result.getData().getContentAuthorized()).isFalse();
        assertThat(result.getData().getReasonCode()).isEqualTo(AiReportShareAccessDO.REASON_SCOPE_UNCOVERED);
        assertThat(result.getData().getSpecJson()).isNull();
    }

    @Test
    void accessListMapsAuditRowsWithoutContentBodies() {
        AiReportShareAccessDO access = new AiReportShareAccessDO()
                .setId(9L)
                .setShareId(501L)
                .setSubjectType("USER")
                .setExternalUserId("bob")
                .setOutcome(AiReportShareAccessDO.OUTCOME_DENIED)
                .setReasonCode(AiReportShareAccessDO.REASON_REVOKED)
                .setContentAuthorized(false);
        when(shareService.getAccessRecords(501L)).thenReturn(List.of(access));

        var result = controller.accessList(501L);

        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getOutcome()).isEqualTo("DENIED");
        assertThat(result.getData().get(0).getReasonCode()).isEqualTo("revoked");
        verify(shareService).getAccessRecords(501L);
    }
}
