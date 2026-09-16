package com.basicframework.module.system.controller.admin.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.system.controller.admin.user.vo.profile.UserProfileRespVO;
import com.basicframework.module.system.controller.admin.user.vo.profile.UserProfileUpdatePasswordReqVO;
import com.basicframework.module.system.controller.admin.user.vo.profile.UserProfileUpdateReqVO;
import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import com.basicframework.module.system.dal.dataobject.dept.PostDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.dept.PostService;
import com.basicframework.module.system.service.permission.PermissionService;
import com.basicframework.module.system.service.permission.RoleService;
import com.basicframework.module.system.service.user.AdminUserService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class UserProfileControllerTest {

    private static final Long USER_ID = 23L;
    private static final String CURRENT_PASSWORD = "CurrentPassword1";

    private final AdminUserService userService = mock(AdminUserService.class);
    private final DeptService deptService = mock(DeptService.class);
    private final PostService postService = mock(PostService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final RoleService roleService = mock(RoleService.class);
    private final UserProfileController controller =
            new UserProfileController(userService, deptService, postService, permissionService, roleService);

    @Test
    void getUserProfile_mapsRolesAndOptionalOrganizationData() {
        AdminUserDO userWithOrganization = user();
        userWithOrganization.setDeptId(3L);
        userWithOrganization.setPostIds(Set.of(4L));
        AdminUserDO userWithoutOrganization = user();
        userWithoutOrganization.setUsername("standalone");
        RoleDO role = new RoleDO().setId(1L).setName("管理员").setCode("admin");
        DeptDO dept = new DeptDO().setId(3L).setName("研发部");
        PostDO post = new PostDO().setId(4L).setName("架构师").setCode("architect");
        when(userService.getUser(USER_ID)).thenReturn(userWithOrganization, userWithoutOrganization);
        when(permissionService.getUserRoleIdListByUserId(USER_ID)).thenReturn(Set.of(1L), Set.of());
        when(roleService.getRoleList(any())).thenReturn(List.of(role), List.of());
        when(deptService.getDept(3L)).thenReturn(dept);
        when(postService.getPostList(Set.of(4L))).thenReturn(List.of(post));

        CommonResult<UserProfileRespVO> organizedResult;
        CommonResult<UserProfileRespVO> standaloneResult;
        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            organizedResult = controller.getUserProfile();
            standaloneResult = controller.getUserProfile();
        }

        assertThat(organizedResult.getData().getRoles())
                .singleElement()
                .extracting(roleVO -> roleVO.getName())
                .isEqualTo("管理员");
        assertThat(organizedResult.getData().getDept().getName()).isEqualTo("研发部");
        assertThat(organizedResult.getData().getPosts())
                .singleElement()
                .extracting(postVO -> postVO.getName())
                .isEqualTo("架构师");
        assertThat(standaloneResult.getData().getUsername()).isEqualTo("standalone");
        assertThat(standaloneResult.getData().getDept()).isNull();
        assertThat(standaloneResult.getData().getPosts()).isNull();
    }

    @Test
    void profileUpdateAndPasswordUpdateBindTheCurrentUser() {
        UserProfileUpdateReqVO profileRequest = new UserProfileUpdateReqVO();
        profileRequest.setNickname("新昵称");
        profileRequest.setEmail("admin@example.com");
        profileRequest.setMobile("13800138000");
        profileRequest.setSex(1);
        profileRequest.setAvatar("https://example.com/avatar.png");
        UserProfileUpdatePasswordReqVO passwordRequest = new UserProfileUpdatePasswordReqVO();
        passwordRequest.setOldPassword("OldPassword1");
        passwordRequest.setNewPassword("NewPassword1");

        CommonResult<Boolean> updateResult;
        CommonResult<Boolean> passwordResult;
        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            updateResult = controller.updateUserProfile(profileRequest);
            passwordResult = controller.updateUserProfilePassword(passwordRequest);
        }

        assertThat(updateResult.getData()).isTrue();
        assertThat(passwordResult.getData()).isTrue();
        verify(userService)
                .updateUserProfile(
                        eq(USER_ID),
                        argThat(updated -> "新昵称".equals(updated.getNickname())
                                && "admin@example.com".equals(updated.getEmail())
                                && "13800138000".equals(updated.getMobile())
                                && Integer.valueOf(1).equals(updated.getSex())
                                && "https://example.com/avatar.png".equals(updated.getAvatar())));
        verify(userService).updateUserPassword(USER_ID, "OldPassword1", "NewPassword1");
    }

    private static AdminUserDO user() {
        return new AdminUserDO().setId(USER_ID).setUsername("admin").setPassword("stored-password-hash");
    }

    private static MockedStatic<SecurityFrameworkUtils> mockCurrentUser() {
        MockedStatic<SecurityFrameworkUtils> securityUtils = mockStatic(SecurityFrameworkUtils.class);
        securityUtils.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(USER_ID);
        return securityUtils;
    }
}
