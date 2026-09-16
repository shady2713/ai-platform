package com.basicframework.module.system.controller.admin.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.excel.core.util.ExcelUtils;
import com.basicframework.module.system.controller.admin.user.vo.user.UserImportExcelVO;
import com.basicframework.module.system.controller.admin.user.vo.user.UserImportRespVO;
import com.basicframework.module.system.controller.admin.user.vo.user.UserPageReqVO;
import com.basicframework.module.system.controller.admin.user.vo.user.UserRespVO;
import com.basicframework.module.system.controller.admin.user.vo.user.UserSaveReqVO;
import com.basicframework.module.system.controller.admin.user.vo.user.UserSimpleRespVO;
import com.basicframework.module.system.controller.admin.user.vo.user.UserUpdatePasswordReqVO;
import com.basicframework.module.system.controller.admin.user.vo.user.UserUpdateStatusReqVO;
import com.basicframework.module.system.convert.user.UserConvert;
import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import com.basicframework.module.system.dal.dataobject.dept.PostDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.enums.common.SexEnum;
import com.basicframework.module.system.service.auth.AdminAuthService;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.dept.PostService;
import com.basicframework.module.system.service.user.AdminUserService;
import com.basicframework.module.system.service.user.dto.UserImportResultDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

class UserControllerTest {

    private final AdminUserService userService = mock(AdminUserService.class);
    private final DeptService deptService = mock(DeptService.class);
    private final PostService postService = mock(PostService.class);
    private final AdminAuthService authService = mock(AdminAuthService.class);
    private final UserController controller = new UserController(userService, deptService, postService, authService);

    @Test
    void mutationEndpoints_mapRequestsAndUnlockUsersAfterAnAdminPasswordReset() {
        UserSaveReqVO saveRequest = saveRequest(7L);
        UserUpdatePasswordReqVO passwordRequest = new UserUpdatePasswordReqVO();
        passwordRequest.setId(7L);
        passwordRequest.setPassword("NewPassword1");
        UserUpdateStatusReqVO statusRequest = new UserUpdateStatusReqVO();
        statusRequest.setId(7L);
        statusRequest.setStatus(CommonStatusEnum.DISABLE.getStatus());
        when(userService.createUser(any(AdminUserDO.class))).thenReturn(9L);

        assertThat(controller.createUser(saveRequest).getData()).isEqualTo(9L);
        assertThat(controller.updateUser(saveRequest).getData()).isTrue();
        assertThat(controller.deleteUser(7L).getData()).isTrue();
        assertThat(controller.deleteUserList(List.of(7L, 8L)).getData()).isTrue();
        assertThat(controller.updateUserPassword(passwordRequest).getData()).isTrue();
        assertThat(controller.updateUserStatus(statusRequest).getData()).isTrue();
        assertThat(controller.unlockLogin(8L).getData()).isTrue();

        verify(userService)
                .createUser(argThat(user -> "new-admin".equals(user.getUsername())
                        && "新管理员".equals(user.getNickname())
                        && Set.of(3L).equals(user.getPostIds())));
        verify(userService)
                .updateUser(eq(null), argThat(user -> Long.valueOf(7L).equals(user.getId())));
        verify(userService).deleteUser(null, 7L);
        verify(userService).deleteUserList(null, List.of(7L, 8L));
        verify(userService).updateUserPassword(null, 7L, "NewPassword1");
        verify(userService).updateUserStatus(null, 7L, CommonStatusEnum.DISABLE.getStatus());
        verify(authService).unlockLogin(null, 7L);
        verify(authService).unlockLogin(null, 8L);
    }

    @Test
    void getUserPage_preservesTotalsForEmptyResultsAndEnrichesNonEmptyResults() {
        UserPageReqVO emptyRequest = pageRequest();
        UserPageReqVO populatedRequest = pageRequest();
        AdminUserDO user = user(2L, 3L, null);
        DeptDO dept = new DeptDO().setId(3L).setName("研发部");
        when(userService.getUserPage(any(), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(), 5L), new PageResult<>(List.of(user), 1L));
        when(deptService.getDeptMap(List.of(3L))).thenReturn(Map.of(3L, dept));

        CommonResult<PageResult<UserRespVO>> emptyResult = controller.getUserPage(emptyRequest);
        CommonResult<PageResult<UserRespVO>> populatedResult = controller.getUserPage(populatedRequest);

        assertThat(emptyResult.getData().getTotal()).isEqualTo(5L);
        assertThat(emptyResult.getData().getList()).isEmpty();
        assertThat(populatedResult.getData().getList())
                .singleElement()
                .satisfies(userVO -> assertThat(userVO.getDeptName()).isEqualTo("研发部"));
    }

    @Test
    void simpleAndDetailEndpoints_handleMissingUsersAndFilterDeletedPosts() {
        AdminUserDO enabledUser = user(2L, 3L, Set.of(4L));
        AdminUserDO userWithoutPosts = user(3L, null, null);
        DeptDO dept = new DeptDO().setId(3L).setName("研发部");
        PostDO post = new PostDO().setId(4L).setName("架构师");
        when(userService.getUserListByStatus(CommonStatusEnum.ENABLE.getStatus()))
                .thenReturn(List.of(enabledUser));
        when(deptService.getDeptMap(List.of(3L))).thenReturn(Map.of(3L, dept));
        when(userService.getUser(99L)).thenReturn(null);
        when(userService.getUser(2L)).thenReturn(enabledUser);
        when(userService.getUser(3L)).thenReturn(userWithoutPosts);
        when(deptService.getDept(3L)).thenReturn(dept);
        when(postService.getPostList(Set.of(4L))).thenReturn(List.of(post));

        CommonResult<List<UserSimpleRespVO>> simpleResult = controller.getSimpleUserList();
        CommonResult<UserRespVO> missingResult = controller.getUser(99L);
        CommonResult<UserRespVO> detailResult = controller.getUser(2L);
        CommonResult<UserRespVO> noPostDetailResult = controller.getUser(3L);

        assertThat(simpleResult.getData()).singleElement().satisfies(userVO -> {
            assertThat(userVO.getNickname()).isEqualTo("管理员 2");
            assertThat(userVO.getDeptName()).isEqualTo("研发部");
        });
        assertThat(missingResult.getData()).isNull();
        assertThat(detailResult.getData().getDeptName()).isEqualTo("研发部");
        assertThat(detailResult.getData().getPostIds()).containsExactly(4L);
        assertThat(noPostDetailResult.getData().getDeptId()).isNull();
        assertThat(noPostDetailResult.getData().getPostIds()).isNull();
    }

    @Test
    void export_appliesTheHardExportLimitAndWritesEnrichedRows() throws Exception {
        UserPageReqVO request = pageRequest();
        AdminUserDO user = user(2L, 3L, null);
        DeptDO dept = new DeptDO().setId(3L).setName("研发部");
        List<AdminUserDO> users = List.of(user);
        Map<Long, DeptDO> deptMap = Map.of(3L, dept);
        List<UserRespVO> expectedRows = UserConvert.INSTANCE.convertList(users, deptMap);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(userService.getUserPage(any(), any(), any(), any())).thenReturn(new PageResult<>(users, 1L));
        when(deptService.getDeptMap(List.of(3L))).thenReturn(deptMap);

        try (MockedStatic<ExcelUtils> excelUtils = mockStatic(ExcelUtils.class)) {
            controller.export(response, request);

            excelUtils.verify(() -> ExcelUtils.write(response, "用户数据.xls", "数据", UserRespVO.class, expectedRows));
        }

        assertThat(request.getPageSize()).isEqualTo(PageParam.EXPORT_MAX_PAGE_SIZE);
    }

    @Test
    void importTemplateAndExcelImport_keepThePublishedSpreadsheetContracts() throws Exception {
        MockHttpServletResponse templateResponse = new MockHttpServletResponse();
        List<UserImportExcelVO> templateRows = List.of(
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
        MockMultipartFile importFile =
                new MockMultipartFile("file", "users.xlsx", "application/vnd.ms-excel", new byte[] {1});
        List<UserImportExcelVO> importedRows = List.of(UserImportExcelVO.builder()
                .username("imported-user")
                .nickname("导入用户")
                .deptName("研发部门")
                .mobile("13800138000")
                .build());
        UserImportResultDTO serviceResult =
                new UserImportResultDTO(List.of("imported-user"), List.of(), Map.of("bad-user", "手机号无效"));
        when(userService.importUserList(eq(null), any(), eq(true))).thenReturn(serviceResult);

        try (MockedStatic<ExcelUtils> excelUtils = mockStatic(ExcelUtils.class)) {
            controller.importTemplate(templateResponse);
            excelUtils.verify(() ->
                    ExcelUtils.write(templateResponse, "用户导入模板.xls", "用户列表", UserImportExcelVO.class, templateRows));
            excelUtils
                    .when(() -> ExcelUtils.read(importFile, UserImportExcelVO.class))
                    .thenReturn(importedRows);

            CommonResult<UserImportRespVO> result = controller.importExcel(importFile, true);

            assertThat(result.getData().getCreateUsernames()).containsExactly("imported-user");
            assertThat(result.getData().getFailureUsernames()).containsEntry("bad-user", "手机号无效");
        }

        verify(userService)
                .importUserList(
                        eq(null),
                        argThat(users -> users.size() == 1
                                && "imported-user".equals(users.get(0).getUsername())
                                && "研发部门".equals(users.get(0).getDeptName())),
                        eq(true));
    }

    private static UserSaveReqVO saveRequest(Long id) {
        UserSaveReqVO request = new UserSaveReqVO();
        request.setId(id);
        request.setUsername("new-admin");
        request.setNickname("新管理员");
        request.setPostIds(Set.of(3L));
        request.setEmail("new-admin@example.com");
        request.setMobile("13800138000");
        request.setPassword("NewPassword1");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return request;
    }

    private static UserPageReqVO pageRequest() {
        UserPageReqVO request = new UserPageReqVO();
        request.setUsername("admin");
        request.setMobile("13800138000");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        request.setCreateTime(
                new LocalDateTime[] {LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 31, 0, 0)});
        request.setDeptId(3L);
        request.setRoleId(1L);
        return request;
    }

    private static AdminUserDO user(Long id, Long deptId, Set<Long> postIds) {
        return new AdminUserDO()
                .setId(id)
                .setUsername("admin-" + id)
                .setNickname("管理员 " + id)
                .setDeptId(deptId)
                .setPostIds(postIds)
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
    }
}
