package com.basicframework.module.system.dal.mysql.user;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdminUserMapper extends BaseMapperX<AdminUserDO> {

    /**
     * 管理员编辑采用完整表单语义，可选联系信息、部门和备注允许显式清空。
     * 状态不在此写入：禁用/启用只能走带特权校验的 update-status 专用链路。
     */
    default int updateManagedUser(AdminUserDO user) {
        AdminUserDO writable = new AdminUserDO()
                .setUsername(user.getUsername())
                .setNickname(user.getNickname())
                .setPostIds(user.getPostIds())
                .setSex(user.getSex())
                .setAvatar(user.getAvatar());
        return update(
                writable,
                new LambdaUpdateWrapper<AdminUserDO>()
                        .eq(AdminUserDO::getId, user.getId())
                        .set(AdminUserDO::getDeptId, user.getDeptId())
                        .set(AdminUserDO::getEmail, user.getEmail())
                        .set(AdminUserDO::getMobile, user.getMobile())
                        .set(AdminUserDO::getRemark, user.getRemark()));
    }

    /** 个人资料只能写入明确允许的字段；空联系信息表示解绑，不影响部门、岗位或凭据。 */
    default int updateUserProfile(AdminUserDO user) {
        AdminUserDO writable = new AdminUserDO()
                .setNickname(user.getNickname())
                .setSex(user.getSex())
                .setAvatar(user.getAvatar());
        return update(
                writable,
                new LambdaUpdateWrapper<AdminUserDO>()
                        .eq(AdminUserDO::getId, user.getId())
                        .set(AdminUserDO::getEmail, user.getEmail())
                        .set(AdminUserDO::getMobile, user.getMobile()));
    }

    /** 当前读并持有账号行锁至事务结束，避免 MVCC 快照放行已失效的凭据。 */
    default AdminUserDO selectByIdForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<AdminUserDO>()
                .eq(AdminUserDO::getId, id)
                .last("FOR UPDATE"));
    }

    /** 原子替换认证时读取的密码；停用或并发改密后拒绝写入。 */
    default int completePasswordChange(Long id, String expectedPassword, String newPassword, boolean requireExpired) {
        return update(
                new AdminUserDO().setPassword(newPassword).setMustChangePassword(false),
                new LambdaUpdateWrapper<AdminUserDO>()
                        .eq(AdminUserDO::getId, id)
                        .eq(AdminUserDO::getPassword, expectedPassword)
                        .eq(
                                AdminUserDO::getStatus,
                                com.basicframework.framework.common.enums.CommonStatusEnum.ENABLE.getStatus())
                        .eq(requireExpired, AdminUserDO::getMustChangePassword, true));
    }

    default AdminUserDO selectByUsername(String username) {
        return selectOne(AdminUserDO::getUsername, username);
    }

    default AdminUserDO selectByEmail(String email) {
        return selectOne(AdminUserDO::getEmail, email);
    }

    default AdminUserDO selectByMobile(String mobile) {
        return selectOne(AdminUserDO::getMobile, mobile);
    }

    default PageResult<AdminUserDO> selectPage(PageParam pageParam, AdminUserQuery query) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AdminUserDO>()
                        .likeIfPresent(AdminUserDO::getUsername, query.getUsername())
                        .likeIfPresent(AdminUserDO::getMobile, query.getMobile())
                        .eqIfPresent(AdminUserDO::getStatus, query.getStatus())
                        .betweenIfPresent(AdminUserDO::getCreateTime, query.getCreateTime())
                        .inIfPresent(AdminUserDO::getDeptId, query.getDeptIds())
                        .inIfPresent(AdminUserDO::getId, query.getUserIds())
                        .orderByDesc(AdminUserDO::getId));
    }

    default List<AdminUserDO> selectListByNickname(String nickname) {
        return selectList(new LambdaQueryWrapperX<AdminUserDO>().like(AdminUserDO::getNickname, nickname));
    }

    default List<AdminUserDO> selectListByStatus(Integer status) {
        return selectList(AdminUserDO::getStatus, status);
    }

    default List<AdminUserDO> selectListByDeptIds(Collection<Long> deptIds) {
        return selectList(AdminUserDO::getDeptId, deptIds);
    }

    default Long selectCountByDeptIds(Collection<Long> deptIds) {
        return selectCount(new LambdaQueryWrapperX<AdminUserDO>().in(AdminUserDO::getDeptId, deptIds));
    }

    /**
     * 仅当密码仍是认证时读取的哈希时写入升级值，避免覆盖并发发生的改密或重置。
     *
     * @param userId 用户编号
     * @param expectedPassword 认证时读取的密码哈希
     * @param upgradedPassword 使用当前工作因子生成的新哈希
     * @return 更新行数；零表示凭据已被并发修改或账号已不存在
     */
    default int updatePasswordIfUnchanged(Long userId, String expectedPassword, String upgradedPassword) {
        return update(
                new AdminUserDO().setPassword(upgradedPassword),
                new LambdaUpdateWrapper<AdminUserDO>()
                        .eq(AdminUserDO::getId, userId)
                        .eq(AdminUserDO::getPassword, expectedPassword));
    }

    @Select(
            """
            SELECT COUNT(*)
            FROM system_users u
            LEFT JOIN system_dept d ON d.id = u.dept_id AND d.deleted = b'0'
            WHERE u.deleted = b'0' AND u.dept_id IS NOT NULL AND d.id IS NULL
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    int selectOrphanDeptCount();
}
