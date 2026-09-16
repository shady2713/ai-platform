package com.basicframework.module.system.service.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import com.basicframework.module.system.dal.mysql.dept.DeptMapper;
import com.basicframework.module.system.dal.mysql.dict.DictDataMapper;
import com.basicframework.module.system.dal.mysql.permission.MenuMapper;
import com.basicframework.module.system.dal.mysql.permission.RoleMapper;
import com.basicframework.module.system.dal.mysql.sms.SmsTemplateMapper;
import com.basicframework.module.system.dal.mysql.user.AdminUserMapper;
import com.basicframework.module.system.enums.permission.DataScopeEnum;
import com.basicframework.module.system.enums.permission.MenuTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemDataIntegrityServiceTest {

    @InjectMocks
    private SystemDataIntegrityService service;

    @Mock
    private DeptMapper deptMapper;

    @Mock
    private MenuMapper menuMapper;

    @Mock
    private AdminUserMapper userMapper;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private DictDataMapper dictDataMapper;

    @Mock
    private SmsTemplateMapper smsTemplateMapper;

    @Test
    void verifyLogicalReferences_returnsStableSummaryWhenClean() {
        when(deptMapper.selectOrphanLeaderUserCount()).thenReturn(0);
        when(userMapper.selectOrphanDeptCount()).thenReturn(0);

        assertThat(service.verifyLogicalReferences()).isEqualTo("逻辑引用完整性检查通过");
    }

    @Test
    void verifyLogicalReferences_failsWithRelationAndCount() {
        when(deptMapper.selectOrphanLeaderUserCount()).thenReturn(2);

        assertThatThrownBy(service::verifyLogicalReferences)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_dept.leader_user_id 孤儿引用 2 条");
    }

    @Test
    void verifyLogicalReferences_failsForOrphanUserDepartment() {
        when(deptMapper.selectOrphanLeaderUserCount()).thenReturn(0);
        when(userMapper.selectOrphanDeptCount()).thenReturn(2);

        assertThatThrownBy(service::verifyLogicalReferences)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_users.dept_id 孤儿引用 2 条");
    }

    @Test
    void verifyLogicalReferences_failsForOrphanRoleDataScopeDepartment() {
        when(roleMapper.selectOrphanDataScopeDeptCount(DataScopeEnum.DEPT_CUSTOM.getScope()))
                .thenReturn(3);

        assertThatThrownBy(service::verifyLogicalReferences)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_role.data_scope_dept_ids 孤儿引用 3 条");
    }

    @Test
    void verifyLogicalReferences_failsForOrphanSmsTemplateChannel() {
        when(smsTemplateMapper.selectOrphanChannelCount()).thenReturn(2);

        assertThatThrownBy(service::verifyLogicalReferences)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_sms_template.channel_id 孤儿引用 2 条");
    }

    @Test
    void verifyLogicalReferences_failsForOrphanDictDataType() {
        when(dictDataMapper.selectOrphanDictTypeCount()).thenReturn(2);

        assertThatThrownBy(service::verifyLogicalReferences)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_dict_data.dict_type 孤儿引用 2 条");
    }

    @Test
    void verifyLogicalReferences_failsForOrphanDepartmentParent() {
        when(deptMapper.selectListForIntegrityAudit()).thenReturn(java.util.List.of(dept(1L, 99L)));

        assertThatThrownBy(service::verifyLogicalReferences)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_dept.parent_id 孤儿引用 1 条");
    }

    @Test
    void verifyLogicalReferences_failsForCyclicDepartmentParents() {
        when(deptMapper.selectListForIntegrityAudit())
                .thenReturn(java.util.List.of(dept(1L, 2L), dept(2L, 1L), dept(3L, 1L)));

        assertThatThrownBy(service::verifyLogicalReferences)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_dept.parent_id 循环层级影响 3 条");
    }

    @Test
    void verifyLogicalReferences_failsForOrphanMenuParent() {
        when(menuMapper.selectListForIntegrityAudit()).thenReturn(java.util.List.of(menu(1L, 99L, MenuTypeEnum.DIR)));

        assertThatThrownBy(service::verifyLogicalReferences)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_menu.parent_id 孤儿引用 1 条");
    }

    @Test
    void verifyLogicalReferences_failsForInvalidMenuParentType() {
        when(menuMapper.selectListForIntegrityAudit())
                .thenReturn(java.util.List.of(
                        menu(1L, MenuDO.ID_ROOT, MenuTypeEnum.BUTTON), menu(2L, 1L, MenuTypeEnum.MENU)));

        assertThatThrownBy(service::verifyLogicalReferences)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_menu.parent_id 指向非目录/菜单父级 1 条");
    }

    @Test
    void verifyLogicalReferences_failsForCyclicMenuParents() {
        when(menuMapper.selectListForIntegrityAudit())
                .thenReturn(java.util.List.of(
                        menu(1L, 2L, MenuTypeEnum.DIR),
                        menu(2L, 1L, MenuTypeEnum.MENU),
                        menu(3L, 1L, MenuTypeEnum.BUTTON)));

        assertThatThrownBy(service::verifyLogicalReferences)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_menu.parent_id 循环层级影响 3 条");
    }

    private static DeptDO dept(Long id, Long parentId) {
        return new DeptDO().setId(id).setParentId(parentId);
    }

    private static MenuDO menu(Long id, Long parentId, MenuTypeEnum type) {
        return new MenuDO().setId(id).setParentId(parentId).setType(type.getType());
    }
}
