package com.basicframework.module.system.controller.admin.logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.excel.core.util.ExcelUtils;
import com.basicframework.module.system.controller.admin.logger.vo.loginlog.LoginLogPageReqVO;
import com.basicframework.module.system.controller.admin.logger.vo.loginlog.LoginLogRespVO;
import com.basicframework.module.system.dal.dataobject.logger.LoginLogDO;
import com.basicframework.module.system.service.logger.LoginLogService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletResponse;

class LoginLogControllerTest {

    private final LoginLogService loginLogService = mock(LoginLogService.class);
    private final LoginLogController controller = new LoginLogController(loginLogService);

    @Test
    void getMapsTheRequestedAuditRecord() {
        when(loginLogService.getLoginLog(7L)).thenReturn(loginLog());

        LoginLogRespVO result = controller.getLoginLog(7L).getData();

        assertThat(result)
                .extracting(LoginLogRespVO::getId, LoginLogRespVO::getUsername, LoginLogRespVO::getUserIp)
                .containsExactly(7L, "auditor", "192.0.2.7");
        verify(loginLogService).getLoginLog(7L);
    }

    @Test
    void pagePreservesEveryAuditFilter() {
        LoginLogPageReqVO request = pageRequest();
        when(loginLogService.getLoginLogPage(
                        same(request), same("192.0.2"), same("audit"), same(request.getCreateTime()), same(true)))
                .thenReturn(new PageResult<>(List.of(loginLog()), 1L));

        assertThat(controller.getLoginLogPage(request).getData().getTotal()).isEqualTo(1L);
        verify(loginLogService)
                .getLoginLogPage(
                        same(request), same("192.0.2"), same("audit"), same(request.getCreateTime()), same(true));
    }

    @Test
    void exportAppliesHardLimitWritesThePublishedSpreadsheetAndRequiresAuthorization() throws Exception {
        LoginLogPageReqVO request = pageRequest();
        LoginLogDO log = loginLog();
        List<LoginLogRespVO> expectedRows = BeanUtils.toBean(List.of(log), LoginLogRespVO.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(loginLogService.getLoginLogPage(any(), any(), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(log), 1L));

        try (MockedStatic<ExcelUtils> excelUtils = mockStatic(ExcelUtils.class)) {
            controller.export(response, request);

            excelUtils.verify(() -> ExcelUtils.write(response, "登录日志.xls", "数据列表", LoginLogRespVO.class, expectedRows));
        }
        assertThat(request.getPageSize()).isEqualTo(PageParam.EXPORT_MAX_PAGE_SIZE);
        assertThat(LoginLogController.class
                        .getMethod("export", jakarta.servlet.http.HttpServletResponse.class, LoginLogPageReqVO.class)
                        .isAnnotationPresent(org.springframework.security.access.prepost.PreAuthorize.class))
                .isTrue();
    }

    private static LoginLogPageReqVO pageRequest() {
        LoginLogPageReqVO request = new LoginLogPageReqVO();
        request.setUserIp("192.0.2");
        request.setUsername("audit");
        request.setStatus(true);
        request.setCreateTime(new LocalDateTime[] {LocalDateTime.now().minusDays(1), LocalDateTime.now()});
        return request;
    }

    private static LoginLogDO loginLog() {
        return new LoginLogDO()
                .setId(7L)
                .setUsername("auditor")
                .setUserIp("192.0.2.7")
                .setUserAgent("test-agent")
                .setLogType(100)
                .setResult(0);
    }
}
