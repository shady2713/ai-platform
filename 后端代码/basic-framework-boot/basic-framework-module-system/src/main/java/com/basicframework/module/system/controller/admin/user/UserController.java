package com.basicframework.module.system.controller.admin.user;

import static com.basicframework.framework.apilog.core.enums.OperateTypeEnum.EXPORT;
import static com.basicframework.framework.common.pojo.CommonResult.success;
import static com.basicframework.framework.common.util.collection.CollectionUtils.convertList;
import static com.basicframework.framework.common.util.collection.CollectionUtils.convertSet;
import static com.basicframework.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

import cn.hutool.core.collection.CollUtil;
import com.basicframework.framework.apilog.core.annotation.ApiAccessLog;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.excel.core.util.ExcelUtils;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.system.controller.admin.user.vo.user.*;
import com.basicframework.module.system.convert.user.UserConvert;
import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import com.basicframework.module.system.dal.dataobject.dept.PostDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.user.AdminUserQuery;
import com.basicframework.module.system.enums.common.SexEnum;
import com.basicframework.module.system.service.auth.AdminAuthService;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.dept.PostService;
import com.basicframework.module.system.service.user.AdminUserService;
import com.basicframework.module.system.service.user.dto.UserImportDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "管理后台 - 用户")
@RestController
@RequestMapping("/system/user")
@Validated
@RequiredArgsConstructor
public class UserController {

    private final AdminUserService userService;

    private final DeptService deptService;

    private final PostService postService;

    private final AdminAuthService authService;

    @PostMapping("/create")
    @Operation(summary = "新增用户")
    @PreAuthorize("@ss.hasPermission('system:user:create')")
    public CommonResult<Long> createUser(@Valid @RequestBody UserSaveReqVO reqVO) {
        Long id = userService.createUser(BeanUtils.toBean(reqVO, AdminUserDO.class));
        return success(id);
    }

    @PutMapping("/update")
    @Operation(summary = "修改用户")
    @PreAuthorize("@ss.hasPermission('system:user:update')")
    public CommonResult<Boolean> updateUser(@Valid @RequestBody UserSaveReqVO reqVO) {
        userService.updateUser(getLoginUserId(), BeanUtils.toBean(reqVO, AdminUserDO.class));
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除用户")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:user:delete')")
    public CommonResult<Boolean> deleteUser(@RequestParam("id") @Positive Long id) {
        userService.deleteUser(getLoginUserId(), id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @Operation(summary = "批量删除用户")
    @PreAuthorize("@ss.hasPermission('system:user:delete')")
    public CommonResult<Boolean> deleteUserList(@RequestParam("ids") List<Long> ids) {
        userService.deleteUserList(getLoginUserId(), ids);
        return success(true);
    }

    @PutMapping("/update-password")
    @Operation(summary = "重置用户密码")
    @PreAuthorize("@ss.hasPermission('system:user:update-password')")
    public CommonResult<Boolean> updateUserPassword(@Valid @RequestBody UserUpdatePasswordReqVO reqVO) {
        userService.updateUserPassword(getLoginUserId(), reqVO.getId(), reqVO.getPassword());
        authService.unlockLogin(getLoginUserId(), reqVO.getId());
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "修改用户状态")
    @PreAuthorize("@ss.hasPermission('system:user:update')")
    public CommonResult<Boolean> updateUserStatus(@Valid @RequestBody UserUpdateStatusReqVO reqVO) {
        userService.updateUserStatus(getLoginUserId(), reqVO.getId(), reqVO.getStatus());
        return success(true);
    }

    @PutMapping("/unlock-login")
    @Operation(summary = "解除用户登录锁定")
    @Parameter(name = "id", description = "用户编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:user:update')")
    public CommonResult<Boolean> unlockLogin(@RequestParam("id") @Positive Long id) {
        authService.unlockLogin(getLoginUserId(), id);
        return success(true);
    }

    @GetMapping("/page")
    @Operation(summary = "获得用户分页列表")
    @PreAuthorize("@ss.hasPermission('system:user:query')")
    public CommonResult<PageResult<UserRespVO>> getUserPage(@Valid UserPageReqVO pageReqVO) {
        // 获得用户分页列表
        PageResult<AdminUserDO> pageResult = userService.getUserPage(
                pageReqVO,
                new AdminUserQuery(
                        pageReqVO.getUsername(),
                        pageReqVO.getMobile(),
                        pageReqVO.getStatus(),
                        pageReqVO.getCreateTime(),
                        null,
                        null),
                pageReqVO.getDeptId(),
                pageReqVO.getRoleId());
        if (CollUtil.isEmpty(pageResult.getList())) {
            return success(new PageResult<>(pageResult.getTotal()));
        }
        // 拼接数据
        Map<Long, DeptDO> deptMap = deptService.getDeptMap(convertList(pageResult.getList(), AdminUserDO::getDeptId));
        return success(new PageResult<>(
                UserConvert.INSTANCE.convertList(pageResult.getList(), deptMap), pageResult.getTotal()));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获取用户精简信息列表", description = "只包含被开启的用户，主要用于前端的下拉选项")
    @AuthenticatedOnly
    public CommonResult<List<UserSimpleRespVO>> getSimpleUserList() {
        List<AdminUserDO> list = userService.getUserListByStatus(CommonStatusEnum.ENABLE.getStatus());
        // 拼接数据
        Map<Long, DeptDO> deptMap = deptService.getDeptMap(convertList(list, AdminUserDO::getDeptId));
        return success(UserConvert.INSTANCE.convertSimpleList(list, deptMap));
    }

    @GetMapping("/get")
    @Operation(summary = "获得用户详情")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:user:query')")
    public CommonResult<UserRespVO> getUser(@RequestParam("id") @Positive Long id) {
        AdminUserDO user = userService.getUser(id);
        if (user == null) {
            return success(null);
        }
        // 拼接数据
        DeptDO dept = deptService.getDept(user.getDeptId());
        UserRespVO userVO = UserConvert.INSTANCE.convert(user, dept);
        // 校验岗位是否有效，过滤已删除的岗位
        if (CollUtil.isNotEmpty(user.getPostIds())) {
            List<PostDO> postList = postService.getPostList(user.getPostIds());
            userVO.setPostIds(convertSet(postList, PostDO::getId));
        }
        return success(userVO);
    }

    @GetMapping("/export-excel")
    @Operation(summary = "导出用户")
    @PreAuthorize("@ss.hasPermission('system:user:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void export(HttpServletResponse response, @Valid UserPageReqVO exportReqVO) throws IOException {
        exportReqVO.setPageSize(PageParam.EXPORT_MAX_PAGE_SIZE);
        List<AdminUserDO> list = userService
                .getUserPage(
                        exportReqVO,
                        new AdminUserQuery(
                                exportReqVO.getUsername(),
                                exportReqVO.getMobile(),
                                exportReqVO.getStatus(),
                                exportReqVO.getCreateTime(),
                                null,
                                null),
                        exportReqVO.getDeptId(),
                        exportReqVO.getRoleId())
                .getList();
        // 输出 Excel
        Map<Long, DeptDO> deptMap = deptService.getDeptMap(convertList(list, AdminUserDO::getDeptId));
        ExcelUtils.write(response, "用户数据.xls", "数据", UserRespVO.class, UserConvert.INSTANCE.convertList(list, deptMap));
    }

    @GetMapping("/get-import-template")
    @Operation(summary = "获得导入用户模板")
    @AuthenticatedOnly
    public void importTemplate(HttpServletResponse response) throws IOException {
        // 手动创建导入模板示例数据
        List<UserImportExcelVO> list = Arrays.asList(
                UserImportExcelVO.builder()
                        .username("user_a")
                        .deptName("研发部门")
                        .email("user_a@example.com")
                        .mobile("13800138000")
                        .nickname("示例用户A")
                        .status(CommonStatusEnum.ENABLE.getStatus())
                        .sex(SexEnum.MALE.getSex())
                        .build(),
                UserImportExcelVO.builder()
                        .username("user_b")
                        .deptName("运维部门")
                        .email("user_b@example.com")
                        .mobile("15601701300")
                        .nickname("示例用户B")
                        .status(CommonStatusEnum.DISABLE.getStatus())
                        .sex(SexEnum.FEMALE.getSex())
                        .build());
        // 输出
        ExcelUtils.write(response, "用户导入模板.xls", "用户列表", UserImportExcelVO.class, list);
    }

    @PostMapping("/import")
    @Operation(summary = "导入用户")
    @Parameters({
        @Parameter(name = "file", description = "Excel 文件", required = true),
        @Parameter(name = "updateSupport", description = "是否支持更新，默认为 false", example = "true")
    })
    @PreAuthorize("@ss.hasPermission('system:user:import')")
    public CommonResult<UserImportRespVO> importExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "updateSupport", required = false, defaultValue = "false") Boolean updateSupport)
            throws Exception {
        List<UserImportExcelVO> list = ExcelUtils.read(file, UserImportExcelVO.class);
        return success(BeanUtils.toBean(
                userService.importUserList(
                        getLoginUserId(), BeanUtils.toBean(list, UserImportDTO.class), updateSupport),
                UserImportRespVO.class));
    }
}
