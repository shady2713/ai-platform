package com.basicframework.module.system.convert.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.system.controller.admin.auth.vo.AuthPermissionInfoRespVO;
import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.enums.permission.MenuTypeEnum;
import com.basicframework.module.system.service.auth.dto.AuthSmsSendDTO;
import com.basicframework.module.system.service.sms.dto.SmsCodeSendReqDTO;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthConvertTest {

    @Test
    void convert_buildsSortedMenuTreeWithoutMutatingTheServiceResult() {
        MenuDO systemRoot = menu(1L, MenuDO.ID_ROOT, MenuTypeEnum.DIR, 20, "系统管理", "system:root");
        MenuDO userMenu = menu(2L, 1L, MenuTypeEnum.MENU, 10, "用户管理", "system:user:read");
        MenuDO reportMenu = menu(6L, 1L, MenuTypeEnum.MENU, 11, "报表管理", "system:report:read");
        MenuDO saveButton = menu(3L, 2L, MenuTypeEnum.BUTTON, 1, "保存", "system:user:save");
        MenuDO orphanMenu = menu(4L, 99L, MenuTypeEnum.MENU, 15, "孤立菜单", "system:orphan");
        MenuDO dashboardRoot = menu(5L, MenuDO.ID_ROOT, MenuTypeEnum.DIR, 5, "工作台", "system:dashboard");
        List<MenuDO> serviceMenus =
                new ArrayList<>(List.of(systemRoot, userMenu, reportMenu, saveButton, orphanMenu, dashboardRoot));
        AdminUserDO user = new AdminUserDO().setId(7L).setUsername("admin").setNickname("管理员");
        List<RoleDO> roles = List.of(
                new RoleDO().setId(1L).setCode("admin"), new RoleDO().setId(2L).setCode("auditor"));

        AuthPermissionInfoRespVO result = AuthConvert.INSTANCE.convert(user, roles, serviceMenus);

        assertThat(result.getUser().getUsername()).isEqualTo("admin");
        assertThat(result.getRoles()).containsExactlyInAnyOrder("admin", "auditor");
        assertThat(result.getPermissions())
                .containsExactlyInAnyOrder(
                        "system:root",
                        "system:user:read",
                        "system:report:read",
                        "system:user:save",
                        "system:orphan",
                        "system:dashboard");
        assertThat(result.getMenus())
                .extracting(AuthPermissionInfoRespVO.MenuVO::getName)
                .containsExactly("工作台", "系统管理");
        assertThat(result.getMenus().get(1).getChildren())
                .extracting(AuthPermissionInfoRespVO.MenuVO::getName)
                .containsExactly("用户管理", "报表管理");
        assertThat(serviceMenus)
                .containsExactly(systemRoot, userMenu, reportMenu, saveButton, orphanMenu, dashboardRoot);
    }

    @Test
    void buildMenuTree_returnsEmptyForNullOrEmptyInput() {
        assertThat(AuthConvert.INSTANCE.buildMenuTree(null)).isEmpty();
        assertThat(AuthConvert.INSTANCE.buildMenuTree(List.of())).isEmpty();
    }

    @Test
    void smsConversions_preserveOnlyTheFieldsConsumedBySmsServices() {
        AuthSmsSendDTO send = new AuthSmsSendDTO();
        send.setMobile("13800138000");
        send.setScene(1);
        send.setCaptchaVerification("captcha");
        SmsCodeSendReqDTO sendResult = AuthConvert.INSTANCE.convert(send);

        assertThat(sendResult.getMobile()).isEqualTo("13800138000");
        assertThat(sendResult.getScene()).isEqualTo(1);
        assertThat(sendResult.getCreateIp()).isNull();
    }

    private static MenuDO menu(
            Long id, Long parentId, MenuTypeEnum type, Integer sort, String name, String permission) {
        return new MenuDO()
                .setId(id)
                .setParentId(parentId)
                .setType(type.getType())
                .setSort(sort)
                .setName(name)
                .setPermission(permission);
    }
}
