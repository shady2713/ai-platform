package com.basicframework.module.system.service.dept;

import static com.basicframework.module.system.testutil.ServiceExceptionAssert.assertServiceException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.dept.DeptMapper;
import com.basicframework.module.system.dal.mysql.permission.RoleMapper;
import com.basicframework.module.system.dal.mysql.user.AdminUserMapper;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import com.basicframework.module.system.enums.permission.DataScopeEnum;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeptServiceImplTest {

    @InjectMocks
    private DeptServiceImpl deptService;

    @Mock
    private DeptMapper deptMapper;

    @Mock
    private AdminUserMapper userMapper;

    @Mock
    private RoleMapper roleMapper;

    @Test
    void getChildDeptList_readsHierarchyOnceAndVisitsEachDescendantOnlyOnce() {
        DeptDO first = new DeptDO().setId(2L).setParentId(1L);
        DeptDO second = new DeptDO().setId(3L).setParentId(2L);
        DeptDO cyclicRoot = new DeptDO().setId(1L).setParentId(3L);
        DeptDO unrelated = new DeptDO().setId(5L).setParentId(4L);
        when(deptMapper.selectList()).thenReturn(List.of(first, second, cyclicRoot, unrelated));
        assertThat(deptService.getChildDeptList(List.of(1L, 1L))).containsExactly(first, second);
        verify(deptMapper).selectList();
    }

    @Test
    void getChildDeptList_emptyScopeDoesNotReadDatabase() {
        assertThat(deptService.getChildDeptList(List.of())).isEmpty();
        assertThat(deptService.getChildDeptIdList(null)).isEmpty();
        verify(deptMapper, never()).selectList();
    }

    @Test
    void getChildDeptIdList_observesReparentedHierarchyOnNextCall() {
        when(deptMapper.selectList())
                .thenReturn(
                        List.of(new DeptDO().setId(2L).setParentId(1L)),
                        List.of(new DeptDO().setId(2L).setParentId(3L)));
        assertThat(deptService.getChildDeptIdList(1L)).containsExactly(2L);
        assertThat(deptService.getChildDeptIdList(1L)).isEmpty();
    }

    @Test
    void createDept_rejectsMissingLeaderUser() {
        DeptDO dept = dept(1L, 999L);
        when(userMapper.selectById(999L)).thenReturn(null);

        assertServiceException(ErrorCodeConstants.USER_NOT_EXISTS.getCode(), () -> deptService.createDept(dept));

        verify(deptMapper, never()).insert(any(DeptDO.class));
    }

    @Test
    void createDept_acceptsExistingLeaderUser() {
        DeptDO dept = dept(1L, 7L);
        when(userMapper.selectById(7L)).thenReturn(new AdminUserDO().setId(7L));

        deptService.createDept(dept);

        verify(deptMapper).insert(dept);
    }

    @Test
    void updateDept_allowsClearingLeaderWithoutLookup() {
        DeptDO dept = dept(1L, null);
        when(deptMapper.selectByIdForUpdate(1L)).thenReturn(dept);

        deptService.updateDept(dept);

        verify(userMapper, never()).selectById(any());
        verify(deptMapper).updateById(dept);
    }

    @Test
    void createDept_locksCompleteParentChain() {
        DeptDO grandparent = dept(7L, null);
        DeptDO parent = dept(8L, null).setParentId(7L);
        DeptDO child = dept(null, null).setParentId(8L);
        when(deptMapper.selectByIdForShare(8L)).thenReturn(parent);
        when(deptMapper.selectByIdForShare(7L)).thenReturn(grandparent);

        deptService.createDept(child);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(deptMapper);
        inOrder.verify(deptMapper).selectByIdForShare(8L);
        inOrder.verify(deptMapper).selectByIdForShare(7L);
        verify(deptMapper).insert(child);
    }

    @Test
    void updateDept_rejectsParentCycleOutsideUpdatedDepartment() {
        DeptDO update = dept(1L, null).setParentId(2L);
        when(deptMapper.selectByIdForUpdate(1L)).thenReturn(dept(1L, null));
        when(deptMapper.selectByIdForShare(2L)).thenReturn(dept(2L, null).setParentId(3L));
        when(deptMapper.selectByIdForShare(3L)).thenReturn(dept(3L, null).setParentId(2L));

        assertThatThrownBy(() -> deptService.updateDept(update))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ErrorCodeConstants.DEPT_PARENT_CYCLE.getCode());

        verify(deptMapper, never()).updateById(any(DeptDO.class));
    }

    @Test
    void updateDept_rejectsMovingBelowOwnChild() {
        DeptDO update = dept(1L, null).setParentId(2L);
        when(deptMapper.selectByIdForUpdate(1L)).thenReturn(dept(1L, null));
        when(deptMapper.selectByIdForShare(2L)).thenReturn(dept(2L, null).setParentId(1L));

        assertThatThrownBy(() -> deptService.updateDept(update))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ErrorCodeConstants.DEPT_PARENT_IS_CHILD.getCode());

        verify(deptMapper, never()).updateById(any(DeptDO.class));
    }

    @Test
    void processUserDeleted_clearsLeaderReference() {
        deptService.processUserDeleted(7L);

        verify(deptMapper).clearLeaderUserId(7L);
    }

    @Test
    void deleteDept_rejectsReferencedUsers() {
        DeptDO dept = dept(1L, null);
        when(deptMapper.selectByIdForUpdate(1L)).thenReturn(dept);
        when(deptMapper.selectCountByParentId(1L)).thenReturn(0L);
        when(userMapper.selectCountByDeptIds(List.of(1L))).thenReturn(1L);

        assertThatThrownBy(() -> deptService.deleteDept(1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ErrorCodeConstants.DEPT_EXISTS_USER.getCode());

        verify(deptMapper, never()).deleteById(1L);
    }

    @Test
    void deleteDept_deletesUnreferencedLeaf() {
        DeptDO dept = dept(1L, null);
        when(deptMapper.selectByIdForUpdate(1L)).thenReturn(dept);
        when(deptMapper.selectCountByParentId(1L)).thenReturn(0L);
        when(userMapper.selectCountByDeptIds(List.of(1L))).thenReturn(0L);

        deptService.deleteDept(1L);

        verify(deptMapper).deleteById(1L);
    }

    @Test
    void deleteDept_rejectsRoleDataScopeReference() {
        DeptDO dept = dept(1L, null);
        RoleDO role = new RoleDO().setId(9L).setDataScopeDeptIds(Set.of(1L));
        when(deptMapper.selectByIdForUpdate(1L)).thenReturn(dept);
        when(roleMapper.selectListByDataScope(DataScopeEnum.DEPT_CUSTOM.getScope()))
                .thenReturn(List.of(role));

        assertThatThrownBy(() -> deptService.deleteDept(1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ErrorCodeConstants.DEPT_EXISTS_ROLE_DATA_SCOPE.getCode());

        verify(deptMapper, never()).deleteById(1L);
    }

    @Test
    void deleteDeptList_rejectsNullBeforeSortingOrLocking() {
        assertThatThrownBy(() -> deptService.deleteDeptList(java.util.Arrays.asList(1L, null)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ErrorCodeConstants.DEPT_NOT_EXISTS.getCode());

        verify(deptMapper, never()).selectByIdForUpdate(any());
    }

    @Test
    void validateDeptListForReferenceWrite_locksDistinctDepartmentsInOrder() {
        when(deptMapper.selectByIdForShare(1L)).thenReturn(enabledDept(1L));
        when(deptMapper.selectByIdForShare(2L)).thenReturn(enabledDept(2L));

        deptService.validateDeptListForReferenceWrite(List.of(2L, 1L, 2L));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(deptMapper);
        inOrder.verify(deptMapper).selectByIdForShare(1L);
        inOrder.verify(deptMapper).selectByIdForShare(2L);
    }

    @Test
    void validateDeptListForReferenceWrite_rejectsDisabledDepartment() {
        DeptDO disabled = dept(1L, null).setStatus(CommonStatusEnum.DISABLE.getStatus());
        when(deptMapper.selectByIdForShare(1L)).thenReturn(disabled);

        assertThatThrownBy(() -> deptService.validateDeptListForReferenceWrite(List.of(1L)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ErrorCodeConstants.DEPT_NOT_ENABLE.getCode());
    }

    @Test
    void createDept_defaultsMissingParentToRoot() {
        DeptDO dept = new DeptDO().setName("新部门");

        deptService.createDept(dept);

        assertThat(dept.getParentId()).isEqualTo(DeptDO.PARENT_ID_ROOT);
        verify(deptMapper).insert(dept);
    }

    @Test
    void getDeptList_empty_returnsEmptyList() {
        assertThat(deptService.getDeptList(List.of())).isEmpty();

        verify(deptMapper, never()).selectByIds(any());
    }

    @Test
    void getDeptByName_blank_returnsNull() {
        assertThat(deptService.getDeptByName("  ")).isNull();

        verify(deptMapper, never()).selectFirstOne(any(), any());
    }

    private static DeptDO dept(Long id, Long leaderUserId) {
        return new DeptDO()
                .setId(id)
                .setParentId(DeptDO.PARENT_ID_ROOT)
                .setName("研发部")
                .setLeaderUserId(leaderUserId);
    }

    private static DeptDO enabledDept(Long id) {
        return dept(id, null).setStatus(CommonStatusEnum.ENABLE.getStatus());
    }
}
