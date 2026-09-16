package com.basicframework.module.system.controller.admin.logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.excel.core.util.ExcelUtils;
import com.basicframework.module.system.controller.admin.logger.vo.operatelog.OperateLogPageReqVO;
import com.basicframework.module.system.controller.admin.logger.vo.operatelog.OperateLogRespVO;
import com.basicframework.module.system.dal.dataobject.logger.OperateLogDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.logger.OperateLogQuery;
import com.basicframework.module.system.service.logger.OperateLogService;
import com.basicframework.module.system.service.user.AdminUserService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class OperateLogControllerTest {

    @InjectMocks
    private OperateLogController controller;

    @Mock
    private OperateLogService operateLogService;

    @Mock
    private AdminUserService adminUserService;

    @Test
    void getOperateLog_resolvesUserName() {
        OperateLogDO operateLog = new OperateLogDO().setId(1L).setUserId(11L);
        when(operateLogService.getOperateLog(1L)).thenReturn(operateLog);
        when(adminUserService.getUserMap(Set.of(11L))).thenReturn(Map.of(11L, new AdminUserDO().setNickname("甲用户")));

        CommonResult<OperateLogRespVO> result = controller.getOperateLog(1L);

        assertThat(result.getData().getId()).isEqualTo(1L);
        assertThat(result.getData().getUserName()).isEqualTo("甲用户");
        verify(adminUserService).getUserMap(Set.of(11L));
    }

    @Test
    void pageOperateLog_resolvesUserNamesWithOneBatchQuery() {
        OperateLogPageReqVO request = pageRequest();
        OperateLogDO first = new OperateLogDO().setUserId(11L);
        OperateLogDO second = new OperateLogDO().setUserId(null);
        OperateLogDO third = new OperateLogDO().setUserId(22L);
        when(operateLogService.getOperateLogPage(same(request), any(OperateLogQuery.class)))
                .thenReturn(new PageResult<>(List.of(first, second, third), 3L));
        when(adminUserService.getUserMap(Set.of(11L, 22L)))
                .thenReturn(Map.of(11L, new AdminUserDO().setNickname("甲用户")));

        CommonResult<PageResult<OperateLogRespVO>> result = controller.pageOperateLog(request);

        assertThat(result.getData().getList())
                .extracting(OperateLogRespVO::getUserName)
                .containsExactly("甲用户", null, null);
        verify(adminUserService).getUserMap(Set.of(11L, 22L));
        verify(operateLogService)
                .getOperateLogPage(
                        same(request),
                        org.mockito.ArgumentMatchers.argThat(
                                query -> query.getUserId().equals(11L)
                                        && query.getBizId().equals(22L)
                                        && query.getType().equals("SYSTEM_USER")
                                        && query.getSubType().equals("UPDATE")
                                        && query.getAction().equals("修改用户")
                                        && query.getCreateTime() == request.getCreateTime()));
    }

    @Test
    void exportAppliesHardLimitWritesOnlyPublishedColumnsAndRequiresAuthorization() throws Exception {
        OperateLogPageReqVO request = pageRequest();
        OperateLogDO log = new OperateLogDO().setId(1L).setUserId(11L);
        OperateLogRespVO row = BeanUtils.toBean(log, OperateLogRespVO.class);
        row.setUserName("甲用户");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(operateLogService.getOperateLogPage(same(request), any(OperateLogQuery.class)))
                .thenReturn(new PageResult<>(List.of(log), 1L));
        when(adminUserService.getUserMap(Set.of(11L))).thenReturn(Map.of(11L, new AdminUserDO().setNickname("甲用户")));

        try (MockedStatic<ExcelUtils> excelUtils = mockStatic(ExcelUtils.class)) {
            controller.export(response, request);

            excelUtils.verify(
                    () -> ExcelUtils.write(response, "操作日志.xls", "数据列表", OperateLogRespVO.class, List.of(row)));
        }
        assertThat(request.getPageSize()).isEqualTo(PageParam.EXPORT_MAX_PAGE_SIZE);
        assertThat(OperateLogController.class
                        .getMethod("export", jakarta.servlet.http.HttpServletResponse.class, OperateLogPageReqVO.class)
                        .isAnnotationPresent(org.springframework.security.access.prepost.PreAuthorize.class))
                .isTrue();
    }

    private static OperateLogPageReqVO pageRequest() {
        OperateLogPageReqVO request = new OperateLogPageReqVO();
        request.setUserId(11L);
        request.setBizId(22L);
        request.setType("SYSTEM_USER");
        request.setSubType("UPDATE");
        request.setAction("修改用户");
        request.setCreateTime(new LocalDateTime[] {LocalDateTime.now().minusDays(1), LocalDateTime.now()});
        return request;
    }
}
