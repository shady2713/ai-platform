package com.basicframework.module.system.controller.admin.logger;

import static com.basicframework.framework.apilog.core.enums.OperateTypeEnum.EXPORT;
import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.apilog.core.annotation.ApiAccessLog;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.datapermission.core.util.DataPermissionUtils;
import com.basicframework.framework.excel.core.util.ExcelUtils;
import com.basicframework.module.system.controller.admin.logger.vo.operatelog.OperateLogPageReqVO;
import com.basicframework.module.system.controller.admin.logger.vo.operatelog.OperateLogRespVO;
import com.basicframework.module.system.dal.dataobject.logger.OperateLogDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.logger.OperateLogQuery;
import com.basicframework.module.system.service.logger.OperateLogService;
import com.basicframework.module.system.service.user.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 操作日志 Controller，提供操作日志的分页查询能力 */
@Tag(name = "管理后台 - 操作日志")
@RestController
@RequestMapping("/system/operate-log")
@Validated
@RequiredArgsConstructor
public class OperateLogController {

    private final OperateLogService operateLogService;

    private final AdminUserService adminUserService;

    @GetMapping("/get")
    @Operation(summary = "查看操作日志")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:operate-log:query')")
    public CommonResult<OperateLogRespVO> getOperateLog(@RequestParam("id") @Positive Long id) {
        OperateLogDO operateLog = operateLogService.getOperateLog(id);
        return success(toRespVO(operateLog));
    }

    @GetMapping("/page")
    @Operation(summary = "查看操作日志分页列表")
    @PreAuthorize("@ss.hasPermission('system:operate-log:query')")
    public CommonResult<PageResult<OperateLogRespVO>> pageOperateLog(@Valid OperateLogPageReqVO pageReqVO) {
        PageResult<OperateLogDO> pageResult = operateLogService.getOperateLogPage(
                pageReqVO,
                new OperateLogQuery(
                        pageReqVO.getUserId(),
                        pageReqVO.getBizId(),
                        pageReqVO.getType(),
                        pageReqVO.getSubType(),
                        pageReqVO.getAction(),
                        pageReqVO.getCreateTime()));
        PageResult<OperateLogRespVO> response = BeanUtils.toBean(pageResult, OperateLogRespVO.class);
        fillUserNames(response.getList());
        return success(response);
    }

    @Operation(summary = "导出操作日志")
    @GetMapping("/export-excel")
    @PreAuthorize("@ss.hasPermission('system:operate-log:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void export(HttpServletResponse response, @Valid OperateLogPageReqVO exportReqVO) throws IOException {
        exportReqVO.setPageSize(PageParam.EXPORT_MAX_PAGE_SIZE);
        List<OperateLogDO> list = operateLogService
                .getOperateLogPage(
                        exportReqVO,
                        new OperateLogQuery(
                                exportReqVO.getUserId(),
                                exportReqVO.getBizId(),
                                exportReqVO.getType(),
                                exportReqVO.getSubType(),
                                exportReqVO.getAction(),
                                exportReqVO.getCreateTime()))
                .getList();
        ExcelUtils.write(response, "操作日志.xls", "数据列表", OperateLogRespVO.class, toRespVOList(list));
    }

    private OperateLogRespVO toRespVO(OperateLogDO log) {
        OperateLogRespVO response = BeanUtils.toBean(log, OperateLogRespVO.class);
        fillUserNames(List.of(response));
        return response;
    }

    private List<OperateLogRespVO> toRespVOList(List<OperateLogDO> logs) {
        List<OperateLogRespVO> response = BeanUtils.toBean(logs, OperateLogRespVO.class);
        fillUserNames(response);
        return response;
    }

    private void fillUserNames(List<OperateLogRespVO> logs) {
        Set<Long> userIds = logs.stream()
                .map(OperateLogRespVO::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, AdminUserDO> userMap = DataPermissionUtils.executeIgnore(() -> adminUserService.getUserMap(userIds));
        logs.forEach(log -> {
            Long userId = log.getUserId();
            if (userId == null) {
                return;
            }
            AdminUserDO user = userMap.get(userId);
            if (user != null) {
                log.setUserName(user.getNickname());
            }
        });
    }
}
