package com.basicframework.module.system.service.integrity;

import com.basicframework.framework.datapermission.core.annotation.DataPermission;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 检查 system 模块无法由物理外键表达的逻辑引用完整性。 */
@Service
@RequiredArgsConstructor
public class SystemDataIntegrityService {

    private final DeptMapper deptMapper;

    private final MenuMapper menuMapper;

    private final AdminUserMapper userMapper;

    private final RoleMapper roleMapper;

    private final DictDataMapper dictDataMapper;

    private final SmsTemplateMapper smsTemplateMapper;

    /**
     * 检查已纳管的逻辑引用；发现孤儿时失败，由 Quartz 记录异常并触发任务告警。
     *
     * @return 稳定的审计摘要
     */
    @DataPermission(enable = false)
    public String verifyLogicalReferences() {
        verifyMenuParentIntegrity();
        verifyDepartmentParentIntegrity();
        int orphanDictTypes = dictDataMapper.selectOrphanDictTypeCount();
        if (orphanDictTypes > 0) {
            throw new IllegalStateException("system_dict_data.dict_type 孤儿引用 " + orphanDictTypes + " 条");
        }
        int orphanSmsTemplateChannels = smsTemplateMapper.selectOrphanChannelCount();
        if (orphanSmsTemplateChannels > 0) {
            throw new IllegalStateException("system_sms_template.channel_id 孤儿引用 " + orphanSmsTemplateChannels + " 条");
        }
        int orphanLeaderUsers = deptMapper.selectOrphanLeaderUserCount();
        if (orphanLeaderUsers > 0) {
            throw new IllegalStateException("system_dept.leader_user_id 孤儿引用 " + orphanLeaderUsers + " 条");
        }
        int orphanUserDepts = userMapper.selectOrphanDeptCount();
        if (orphanUserDepts > 0) {
            throw new IllegalStateException("system_users.dept_id 孤儿引用 " + orphanUserDepts + " 条");
        }
        int orphanRoleDataScopeDepts = roleMapper.selectOrphanDataScopeDeptCount(DataScopeEnum.DEPT_CUSTOM.getScope());
        if (orphanRoleDataScopeDepts > 0) {
            throw new IllegalStateException("system_role.data_scope_dept_ids 孤儿引用 " + orphanRoleDataScopeDepts + " 条");
        }
        return "逻辑引用完整性检查通过";
    }

    private void verifyMenuParentIntegrity() {
        List<MenuDO> menus = menuMapper.selectListForIntegrityAudit();
        Map<Long, MenuDO> menuById = menus.stream().collect(Collectors.toMap(MenuDO::getId, menu -> menu));
        long orphanParents = menus.stream()
                .map(MenuDO::getParentId)
                .filter(parentId -> !MenuDO.ID_ROOT.equals(parentId))
                .filter(parentId -> !menuById.containsKey(parentId))
                .count();
        if (orphanParents > 0) {
            throw new IllegalStateException("system_menu.parent_id 孤儿引用 " + orphanParents + " 条");
        }
        long invalidParentTypes = menus.stream()
                .filter(menu -> !MenuDO.ID_ROOT.equals(menu.getParentId()))
                .map(menu -> menuById.get(menu.getParentId()))
                .filter(parent -> parent != null && !isMenuParentCapable(parent))
                .count();
        if (invalidParentTypes > 0) {
            throw new IllegalStateException("system_menu.parent_id 指向非目录/菜单父级 " + invalidParentTypes + " 条");
        }
        Map<Long, Long> parentById = menus.stream().collect(Collectors.toMap(MenuDO::getId, MenuDO::getParentId));
        long cyclicMenus = parentById.keySet().stream()
                .filter(id -> !reachesRoot(id, MenuDO.ID_ROOT, parentById))
                .count();
        if (cyclicMenus > 0) {
            throw new IllegalStateException("system_menu.parent_id 循环层级影响 " + cyclicMenus + " 条");
        }
    }

    private void verifyDepartmentParentIntegrity() {
        List<DeptDO> depts = deptMapper.selectListForIntegrityAudit();
        Map<Long, Long> parentById = depts.stream().collect(Collectors.toMap(DeptDO::getId, DeptDO::getParentId));
        long orphanParents = parentById.values().stream()
                .filter(parentId -> !DeptDO.PARENT_ID_ROOT.equals(parentId))
                .filter(parentId -> !parentById.containsKey(parentId))
                .count();
        if (orphanParents > 0) {
            throw new IllegalStateException("system_dept.parent_id 孤儿引用 " + orphanParents + " 条");
        }
        long cyclicDepartments = parentById.keySet().stream()
                .filter(id -> !reachesRoot(id, DeptDO.PARENT_ID_ROOT, parentById))
                .count();
        if (cyclicDepartments > 0) {
            throw new IllegalStateException("system_dept.parent_id 循环层级影响 " + cyclicDepartments + " 条");
        }
    }

    private static boolean reachesRoot(Long id, Long rootId, Map<Long, Long> parentById) {
        Set<Long> visitedIds = new HashSet<>();
        Long currentId = id;
        while (!rootId.equals(currentId)) {
            if (currentId == null || !visitedIds.add(currentId)) {
                return false;
            }
            currentId = parentById.get(currentId);
        }
        return true;
    }

    private static boolean isMenuParentCapable(MenuDO menu) {
        return MenuTypeEnum.DIR.getType().equals(menu.getType())
                || MenuTypeEnum.MENU.getType().equals(menu.getType());
    }
}
