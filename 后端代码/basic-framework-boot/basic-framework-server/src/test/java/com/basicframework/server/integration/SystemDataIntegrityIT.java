package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import com.basicframework.module.system.dal.dataobject.dict.DictTypeDO;
import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.mysql.dept.DeptMapper;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import com.basicframework.module.system.enums.permission.DataScopeEnum;
import com.basicframework.module.system.enums.permission.MenuTypeEnum;
import com.basicframework.module.system.enums.permission.RoleTypeEnum;
import com.basicframework.module.system.job.SystemDataIntegrityAuditJob;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.dict.DictTypeService;
import com.basicframework.module.system.service.permission.MenuService;
import com.basicframework.module.system.service.permission.PermissionService;
import com.basicframework.module.system.service.permission.RoleService;
import com.basicframework.module.system.service.sms.SmsChannelService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** 使用真实 MySQL 验证 system 模块逻辑引用与物理外键完整性。 */
class SystemDataIntegrityIT extends AbstractPersistenceIntegrationTest {

    @Autowired
    private RoleService roleService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private DeptMapper deptMapper;

    @Autowired
    private DeptService deptService;

    @Autowired
    private MenuService menuService;

    @Autowired
    private SmsChannelService smsChannelService;

    @Autowired
    private DictTypeService dictTypeService;

    @Autowired
    private SystemDataIntegrityAuditJob systemDataIntegrityAuditJob;

    @Test
    void systemReferencesAndForeignKeys_succeedAgainstRealServices() {
        verifyMenuParentReferenceIntegrity();
        verifyDictDataTypeReferenceIntegrity();
        verifySmsTemplateChannelReferenceIntegrity();
        verifyDepartmentReferenceIntegrity();
        verifyRoleDataScopeDefault();
        verifyRoleDataScopeDepartmentIntegrity();
        verifySystemRelationForeignKeys();
    }

    private void verifyRoleDataScopeDefault() {
        assertThat(jdbcTemplate.queryForObject(
                        """
                        SELECT COLUMN_DEFAULT
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME = 'system_role'
                          AND COLUMN_NAME = 'data_scope'
                        """,
                        String.class))
                .isEqualTo(DataScopeEnum.SELF.getScope().toString());
    }

    private void verifyDepartmentReferenceIntegrity() {
        Long leaderlessDeptId = deptService.createDept(new DeptDO()
                .setName("负责人引用集成部门")
                .setParentId(100L)
                .setSort(96)
                .setStatus(CommonStatusEnum.ENABLE.getStatus()));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT leader_user_id FROM system_dept WHERE id = ?", Long.class, leaderlessDeptId))
                .isNull();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.statistics "
                                + "WHERE table_schema = DATABASE() AND table_name = 'system_dept' "
                                + "AND index_name = 'idx_leader_user_id' AND column_name = 'leader_user_id'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(systemDataIntegrityAuditJob.execute("")).isEqualTo("逻辑引用完整性检查通过");

        Long parentDeptId = deptService.createDept(new DeptDO()
                .setName("父级引用集成父部门")
                .setParentId(DeptDO.PARENT_ID_ROOT)
                .setSort(97)
                .setStatus(CommonStatusEnum.ENABLE.getStatus()));
        Long childDeptId = deptService.createDept(new DeptDO()
                .setName("父级引用集成子部门")
                .setParentId(parentDeptId)
                .setSort(98)
                .setStatus(CommonStatusEnum.ENABLE.getStatus()));
        assertServiceException(
                ErrorCodeConstants.DEPT_EXISTS_CHILDREN.getCode(), () -> deptService.deleteDept(parentDeptId));
        assertServiceException(
                ErrorCodeConstants.DEPT_PARENT_IS_CHILD.getCode(),
                () -> deptService.updateDept(new DeptDO().setId(parentDeptId).setParentId(childDeptId)));

        jdbcTemplate.update("UPDATE system_dept SET parent_id = 999999 WHERE id = ?", childDeptId);
        assertThatThrownBy(() -> systemDataIntegrityAuditJob.execute(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_dept.parent_id 孤儿引用 1 条");
        jdbcTemplate.update("UPDATE system_dept SET parent_id = ? WHERE id = ?", parentDeptId, childDeptId);

        jdbcTemplate.update("UPDATE system_dept SET parent_id = ? WHERE id = ?", childDeptId, parentDeptId);
        assertThatThrownBy(() -> systemDataIntegrityAuditJob.execute(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_dept.parent_id 循环层级影响 2 条");
        jdbcTemplate.update("UPDATE system_dept SET parent_id = ? WHERE id = ?", DeptDO.PARENT_ID_ROOT, parentDeptId);
        assertThat(systemDataIntegrityAuditJob.execute("")).isEqualTo("逻辑引用完整性检查通过");
        deptService.deleteDept(childDeptId);
        deptService.deleteDept(parentDeptId);

        jdbcTemplate.update("UPDATE system_dept SET leader_user_id = 999999 WHERE id = ?", leaderlessDeptId);
        try {
            assertThatThrownBy(() -> systemDataIntegrityAuditJob.execute(""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("system_dept.leader_user_id 孤儿引用 1 条");
        } finally {
            assertThat(deptMapper.clearLeaderUserId(999999L)).isEqualTo(1);
        }
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT leader_user_id FROM system_dept WHERE id = ?", Long.class, leaderlessDeptId))
                .isNull();
        deptService.deleteDept(leaderlessDeptId);
        assertThat(systemDataIntegrityAuditJob.execute("")).isEqualTo("逻辑引用完整性检查通过");

        long temporaryDeptId = 9_000_001L;
        Long originalDeptId = jdbcTemplate.queryForObject("SELECT dept_id FROM system_users WHERE id = 1", Long.class);
        jdbcTemplate.update(
                "INSERT INTO system_dept (id, name, parent_id, sort, status) VALUES (?, 'integration', 0, 1, 0)",
                temporaryDeptId);
        jdbcTemplate.update("UPDATE system_users SET dept_id = ? WHERE id = 1", temporaryDeptId);
        assertThatThrownBy(() -> deptService.deleteDept(temporaryDeptId))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ErrorCodeConstants.DEPT_EXISTS_USER.getCode());

        jdbcTemplate.update("UPDATE system_users SET dept_id = 999999 WHERE id = 1");
        try {
            assertThatThrownBy(() -> systemDataIntegrityAuditJob.execute(""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("system_users.dept_id 孤儿引用 1 条");
        } finally {
            jdbcTemplate.update("UPDATE system_users SET dept_id = ? WHERE id = 1", originalDeptId);
        }
        assertThat(systemDataIntegrityAuditJob.execute("")).isEqualTo("逻辑引用完整性检查通过");
    }

    private void verifyMenuParentReferenceIntegrity() {
        Long parentMenuId = menuService.createMenu(new MenuDO()
                .setName("父级引用集成父菜单")
                .setType(MenuTypeEnum.DIR.getType())
                .setParentId(MenuDO.ID_ROOT)
                .setSort(97)
                .setStatus(CommonStatusEnum.ENABLE.getStatus()));
        Long childMenuId = menuService.createMenu(new MenuDO()
                .setName("父级引用集成子菜单")
                .setType(MenuTypeEnum.MENU.getType())
                .setParentId(parentMenuId)
                .setSort(98)
                .setStatus(CommonStatusEnum.ENABLE.getStatus()));
        assertServiceException(
                ErrorCodeConstants.MENU_EXISTS_CHILDREN.getCode(), () -> menuService.deleteMenu(parentMenuId));
        assertServiceException(
                ErrorCodeConstants.MENU_PARENT_IS_CHILD.getCode(),
                () -> menuService.updateMenu(new MenuDO()
                        .setId(parentMenuId)
                        .setParentId(childMenuId)
                        .setType(MenuTypeEnum.DIR.getType())));
        assertServiceException(
                ErrorCodeConstants.MENU_BUTTON_EXISTS_CHILDREN.getCode(),
                () -> menuService.updateMenu(new MenuDO()
                        .setId(parentMenuId)
                        .setParentId(MenuDO.ID_ROOT)
                        .setType(MenuTypeEnum.BUTTON.getType())));

        jdbcTemplate.update("UPDATE system_menu SET parent_id = 999999 WHERE id = ?", childMenuId);
        assertThatThrownBy(() -> systemDataIntegrityAuditJob.execute(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_menu.parent_id 孤儿引用 1 条");
        jdbcTemplate.update("UPDATE system_menu SET parent_id = ? WHERE id = ?", parentMenuId, childMenuId);

        jdbcTemplate.update(
                "UPDATE system_menu SET type = ? WHERE id = ?", MenuTypeEnum.BUTTON.getType(), parentMenuId);
        assertThatThrownBy(() -> systemDataIntegrityAuditJob.execute(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_menu.parent_id 指向非目录/菜单父级 1 条");
        jdbcTemplate.update("UPDATE system_menu SET type = ? WHERE id = ?", MenuTypeEnum.DIR.getType(), parentMenuId);

        jdbcTemplate.update("UPDATE system_menu SET parent_id = ? WHERE id = ?", childMenuId, parentMenuId);
        assertThatThrownBy(() -> systemDataIntegrityAuditJob.execute(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_menu.parent_id 循环层级影响 2 条");
        jdbcTemplate.update("UPDATE system_menu SET parent_id = ? WHERE id = ?", MenuDO.ID_ROOT, parentMenuId);
        assertThat(systemDataIntegrityAuditJob.execute("")).isEqualTo("逻辑引用完整性检查通过");

        menuService.deleteMenu(childMenuId);
        menuService.deleteMenu(parentMenuId);
    }

    private void verifySmsTemplateChannelReferenceIntegrity() {
        long channelId = 9_082_001L;
        long templateId = 9_082_002L;
        jdbcTemplate.update(
                """
                INSERT INTO system_sms_channel
                    (id, signature, code, status, api_key, api_secret)
                VALUES (?, 'integration', 'aliyun', 0, 'integration-key', 'integration-secret')
                """,
                channelId);
        jdbcTemplate.update(
                """
                INSERT INTO system_sms_template
                    (id, type, status, code, name, content, params, api_template_id, channel_id, channel_code)
                VALUES (?, 1, 0, 'SMS_REFERENCE_IT', '引用完整性', '验证码 {code}', '["code"]',
                        'integration-template', ?, 'aliyun')
                """,
                templateId,
                channelId);

        assertServiceException(
                ErrorCodeConstants.SMS_CHANNEL_HAS_CHILDREN.getCode(),
                () -> smsChannelService.deleteSmsChannel(channelId));
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM system_sms_channel WHERE id = ?", channelId))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update("UPDATE system_sms_channel SET deleted = b'1' WHERE id = ?", channelId);
        assertThatThrownBy(() -> systemDataIntegrityAuditJob.execute(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_sms_template.channel_id 孤儿引用 1 条");
        jdbcTemplate.update("UPDATE system_sms_channel SET deleted = b'0' WHERE id = ?", channelId);
        assertThat(systemDataIntegrityAuditJob.execute("")).isEqualTo("逻辑引用完整性检查通过");

        jdbcTemplate.update("DELETE FROM system_sms_template WHERE id = ?", templateId);
        jdbcTemplate.update("DELETE FROM system_sms_channel WHERE id = ?", channelId);
    }

    private void verifyDictDataTypeReferenceIntegrity() {
        long typeId = 9_084_101L;
        long dataId = 9_084_102L;
        String type = "v24_service_reference";
        jdbcTemplate.update(
                "INSERT INTO system_dict_type (id, name, type, status) VALUES (?, '引用完整性', ?, 0)", typeId, type);
        jdbcTemplate.update(
                """
                INSERT INTO system_dict_data (id, sort, label, value, dict_type, status)
                VALUES (?, 1, '引用完整性', 'integration', ?, 0)
                """,
                dataId,
                type);

        assertServiceException(
                ErrorCodeConstants.DICT_TYPE_HAS_CHILDREN.getCode(), () -> dictTypeService.deleteDictType(typeId));
        jdbcTemplate.update("UPDATE system_dict_data SET deleted = b'1' WHERE id = ?", dataId);
        assertServiceException(
                ErrorCodeConstants.DICT_TYPE_CHANGE_HAS_CHILDREN.getCode(),
                () -> dictTypeService.updateDictType(new DictTypeDO()
                        .setId(typeId)
                        .setName("引用完整性")
                        .setType("v24_service_renamed")
                        .setStatus(CommonStatusEnum.ENABLE.getStatus())));
        jdbcTemplate.update("UPDATE system_dict_data SET deleted = b'0' WHERE id = ?", dataId);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM system_dict_type WHERE id = ?", typeId))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update("UPDATE system_dict_type SET deleted = b'1' WHERE id = ?", typeId);
        assertThatThrownBy(() -> systemDataIntegrityAuditJob.execute(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_dict_data.dict_type 孤儿引用 1 条");
        jdbcTemplate.update("UPDATE system_dict_type SET deleted = b'0' WHERE id = ?", typeId);
        assertThat(systemDataIntegrityAuditJob.execute("")).isEqualTo("逻辑引用完整性检查通过");

        jdbcTemplate.update("DELETE FROM system_dict_data WHERE id = ?", dataId);
        jdbcTemplate.update("DELETE FROM system_dict_type WHERE id = ?", typeId);
    }

    private void verifyRoleDataScopeDepartmentIntegrity() {
        Long deptId = deptService.createDept(new DeptDO()
                .setName("角色数据范围集成部门")
                .setParentId(100L)
                .setSort(99)
                .setStatus(CommonStatusEnum.ENABLE.getStatus()));
        Long roleId = roleService.createRole(
                new RoleDO()
                        .setName("角色数据范围集成角色")
                        .setCode("ROLE_SCOPE_INTEGRATION")
                        .setSort(99),
                RoleTypeEnum.CUSTOM.getType());

        permissionService.assignRoleDataScope(1L, roleId, DataScopeEnum.DEPT_CUSTOM.getScope(), Set.of(deptId));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT data_scope_dept_ids FROM system_role WHERE id = ?", String.class, roleId))
                .isEqualTo("[" + deptId + "]");
        assertServiceException(
                ErrorCodeConstants.DEPT_EXISTS_ROLE_DATA_SCOPE.getCode(), () -> deptService.deleteDept(deptId));
        assertServiceException(
                ErrorCodeConstants.DEPT_NOT_EXISTS.getCode(),
                () -> permissionService.assignRoleDataScope(
                        1L, roleId, DataScopeEnum.DEPT_CUSTOM.getScope(), Set.of(9_077_999L)));

        jdbcTemplate.update("UPDATE system_dept SET deleted = b'1' WHERE id = ?", deptId);
        assertThatThrownBy(() -> systemDataIntegrityAuditJob.execute(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_role.data_scope_dept_ids 孤儿引用 1 条");

        jdbcTemplate.update("UPDATE system_dept SET deleted = b'0' WHERE id = ?", deptId);
        permissionService.assignRoleDataScope(1L, roleId, DataScopeEnum.ALL.getScope(), Set.of(deptId));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT data_scope_dept_ids FROM system_role WHERE id = ?", String.class, roleId))
                .isEqualTo("[]");
        deptService.deleteDept(deptId);
        assertThat(systemDataIntegrityAuditJob.execute("")).isEqualTo("逻辑引用完整性检查通过");
    }

    private void verifySystemRelationForeignKeys() {
        assertThat(jdbcTemplate.queryForList(
                        """
                        SELECT constraint_name
                        FROM information_schema.referential_constraints
                        WHERE constraint_schema = DATABASE()
                          AND constraint_name IN (
                            'fk_role_menu_menu', 'fk_role_menu_role',
                            'fk_user_post_post', 'fk_user_post_user',
                            'fk_user_role_role', 'fk_user_role_user')
                          AND delete_rule = 'CASCADE'
                        ORDER BY constraint_name
                        """,
                        String.class))
                .containsExactly(
                        "fk_role_menu_menu",
                        "fk_role_menu_role",
                        "fk_user_post_post",
                        "fk_user_post_user",
                        "fk_user_role_role",
                        "fk_user_role_user");

        long roleId = 9_200_001L;
        long menuId = 9_200_001L;
        long relationId = 9_200_001L;
        jdbcTemplate.update(
                "INSERT INTO system_role (id, name, code, sort, data_scope, status, type) "
                        + "VALUES (?, 'integration', 'integration_role', 1, 1, 0, 2)",
                roleId);
        jdbcTemplate.update(
                "INSERT INTO system_menu (id, name, permission, type, sort, parent_id, status) "
                        + "VALUES (?, 'integration', '', 3, 1, 0, 0)",
                menuId);
        assertThat(jdbcTemplate.update(
                        "INSERT INTO system_role_menu (id, role_id, menu_id) VALUES (?, ?, ?)",
                        relationId,
                        roleId,
                        menuId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.update("DELETE FROM system_role WHERE id = ?", roleId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_role_menu WHERE id = ?", Integer.class, relationId))
                .isZero();
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO system_user_role (id, user_id, role_id) VALUES (?, 1, ?)", relationId, roleId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
