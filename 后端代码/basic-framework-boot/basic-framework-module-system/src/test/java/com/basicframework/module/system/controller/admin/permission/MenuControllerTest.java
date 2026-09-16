package com.basicframework.module.system.controller.admin.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.module.system.controller.admin.permission.vo.menu.MenuListReqVO;
import com.basicframework.module.system.controller.admin.permission.vo.menu.MenuSaveVO;
import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import com.basicframework.module.system.service.permission.MenuService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MenuControllerTest {

    private final MenuService menuService = mock(MenuService.class);
    private final MenuController controller = new MenuController(menuService);

    @Test
    void mutationsMapRequestsAndKeepAuthorization() throws Exception {
        MenuSaveVO request = saveRequest();
        when(menuService.createMenu(any(MenuDO.class))).thenReturn(9L);

        assertThat(controller.createMenu(request).getData()).isEqualTo(9L);
        assertThat(controller.updateMenu(request).getData()).isTrue();
        assertThat(controller.deleteMenu(7L).getData()).isTrue();
        assertThat(controller.deleteMenuList(List.of(7L, 8L)).getData()).isTrue();

        ArgumentCaptor<MenuDO> captor = ArgumentCaptor.forClass(MenuDO.class);
        verify(menuService).createMenu(captor.capture());
        assertThat(captor.getValue())
                .extracting(MenuDO::getId, MenuDO::getName, MenuDO::getParentId, MenuDO::getStatus)
                .containsExactly(7L, "用户管理", 1L, CommonStatusEnum.ENABLE.getStatus());
        verify(menuService).updateMenu(any(MenuDO.class));
        verify(menuService).deleteMenu(7L);
        verify(menuService).deleteMenuList(List.of(7L, 8L));
        assertThat(MenuController.class
                        .getMethod("createMenu", MenuSaveVO.class)
                        .isAnnotationPresent(org.springframework.security.access.prepost.PreAuthorize.class))
                .isTrue();
        assertThat(MenuController.class
                        .getMethod("updateMenu", MenuSaveVO.class)
                        .isAnnotationPresent(org.springframework.security.access.prepost.PreAuthorize.class))
                .isTrue();
    }

    @Test
    void listPreservesFiltersAndSortsByPublishedOrder() {
        MenuListReqVO request = new MenuListReqVO();
        request.setName("用户");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        when(menuService.getMenuList("用户", CommonStatusEnum.ENABLE.getStatus()))
                .thenReturn(new java.util.ArrayList<>(List.of(menu(8L, 20), menu(7L, 10))));

        assertThat(controller.getMenuList(request).getData()).extracting("id").containsExactly(7L, 8L);
        verify(menuService).getMenuList("用户", CommonStatusEnum.ENABLE.getStatus());
    }

    @Test
    void simpleListFiltersDisabledBranchesAndSortsTheResult() {
        List<MenuDO> enabled = List.of(menu(7L, 10), menu(8L, 20));
        List<MenuDO> filtered = new java.util.ArrayList<>(List.of(menu(8L, 20), menu(7L, 10)));
        when(menuService.getMenuList(null, CommonStatusEnum.ENABLE.getStatus())).thenReturn(enabled);
        when(menuService.filterDisableMenus(enabled)).thenReturn(filtered);

        assertThat(controller.getSimpleMenuList().getData()).extracting("id").containsExactly(7L, 8L);
        verify(menuService).filterDisableMenus(enabled);
    }

    @Test
    void getMapsTheRequestedMenu() {
        when(menuService.getMenu(7L)).thenReturn(menu(7L, 10));

        assertThat(controller.getMenu(7L).getData().getName()).isEqualTo("菜单-7");
        verify(menuService).getMenu(7L);
    }

    private static MenuSaveVO saveRequest() {
        MenuSaveVO request = new MenuSaveVO();
        request.setId(7L);
        request.setName("用户管理");
        request.setType(2);
        request.setSort(10);
        request.setParentId(1L);
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return request;
    }

    private static MenuDO menu(long id, int sort) {
        return new MenuDO()
                .setId(id)
                .setName("菜单-" + id)
                .setParentId(1L)
                .setType(2)
                .setSort(sort)
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
    }
}
