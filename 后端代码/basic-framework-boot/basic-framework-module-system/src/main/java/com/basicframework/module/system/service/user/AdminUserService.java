package com.basicframework.module.system.service.user;

import cn.hutool.core.collection.CollUtil;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.collection.CollectionUtils;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.user.AdminUserQuery;
import com.basicframework.module.system.service.user.dto.UserImportDTO;
import com.basicframework.module.system.service.user.dto.UserImportResultDTO;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台用户 Service 接口
 *
 */
public interface AdminUserService {

    /**
     * 创建用户
     *
     * @param user 用户信息
     * @return 用户编号
     */
    Long createUser(AdminUserDO user);

    /**
     * 修改用户。目标持有启用中的超管角色时，仅允许持超管角色的操作者执行；
     * 联系信息（手机号/邮箱）属于身份证明边界，改写超管联系方式等效于接管账号。
     *
     * @param operatorUserId 操作者用户编号，用于超管目标校验；为 null 时按无特权 fail-closed 处理
     * @param updateObj 用户信息
     */
    void updateUser(Long operatorUserId, AdminUserDO updateObj);

    /**
     * 更新用户的最后登陆信息
     *
     * @param id 用户编号
     * @param loginIp 登陆 IP
     */
    void updateUserLogin(Long id, String loginIp);

    /**
     * 修改用户个人信息
     *
     * @param id 用户编号
     * @param user 用户个人信息
     */
    void updateUserProfile(Long id, AdminUserDO user);

    /**
     * 修改用户个人密码。成功后清除 must_change_password 标记。
     *
     * @param id 用户编号
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     */
    void updateUserPassword(Long id, String oldPassword, String newPassword);

    /**
     * 修改密码（管理员重置）。重置后置位 must_change_password，账号下次登录前必须自行改密。
     * 目标持有启用中的超管角色时，仅允许持超管角色的操作者执行；自操作放行。
     *
     * @param operatorUserId 操作者用户编号，用于超管目标校验；为 null 时按无特权 fail-closed 处理
     * @param id       用户编号
     * @param password 密码
     */
    void updateUserPassword(Long operatorUserId, Long id, String password);

    /**
     * 完成一次身份已被调用方验证的密码更换（首登强制改密、短信重置）：
     * 校验密码策略、更新密码、清除 must_change_password 标记并撤销全部会话。
     *
     * @param id 用户编号
     * @param newPassword 新密码
     */
    void completePasswordChange(Long id, String newPassword);

    /**
     * 以认证时密码摘要原子完成首登改密，并撤销会话。
     * @param id 用户编号
     * @param expectedPassword 认证时读取的密码摘要
     * @param newPassword 新密码
     */
    void completeExpiredPasswordChange(Long id, String expectedPassword, String newPassword);

    /**
     * 修改状态
     *
     * @param operatorUserId 操作者用户编号
     * @param id     用户编号
     * @param status 状态
     */
    void updateUserStatus(Long operatorUserId, Long id, Integer status);

    /**
     * 删除用户
     *
     * @param operatorUserId 操作者用户编号
     * @param id 用户编号
     */
    void deleteUser(Long operatorUserId, Long id);

    /**
     * 批量删除用户
     *
     * @param operatorUserId 操作者用户编号
     * @param ids 用户编号数组
     */
    void deleteUserList(Long operatorUserId, List<Long> ids);

    /**
     * 通过用户名查询用户
     *
     * @param username 用户名
     * @return 用户对象信息
     */
    AdminUserDO getUserByUsername(String username);

    /**
     * 通过手机号获取用户
     *
     * @param mobile 手机号
     * @return 用户对象信息
     */
    AdminUserDO getUserByMobile(String mobile);

    /**
     * 获得用户分页列表
     *
     * @param pageParam 分页参数
     * @param query 分页条件，deptIds/userIds 由 Service 基于部门与角色条件计算后覆盖
     * @param deptId 部门编号，同时筛选子部门
     * @param roleId 角色编号
     * @return 分页列表
     */
    PageResult<AdminUserDO> getUserPage(PageParam pageParam, AdminUserQuery query, Long deptId, Long roleId);

    /**
     * 通过用户 ID 查询用户
     *
     * @param id 用户ID
     * @return 用户对象信息
     */
    AdminUserDO getUser(Long id);

    /**
     * 在调用方事务中锁定账号行，串行化会话签发、刷新与撤销；调用方须先锁账号再写会话。
     * @param id 用户编号
     * @return 当前账号，不存在或已删除时返回 null
     */
    AdminUserDO getUserForSession(Long id);

    /**
     * 获得指定部门的用户数组
     *
     * @param deptIds 部门数组
     * @return 用户数组
     */
    List<AdminUserDO> getUserListByDeptIds(Collection<Long> deptIds);

    /**
     * 获得指定岗位的用户数组
     *
     * @param postIds 岗位数组
     * @return 用户数组
     */
    List<AdminUserDO> getUserListByPostIds(Collection<Long> postIds);

    /**
     * 获得用户列表
     *
     * @param ids 用户编号数组
     * @return 用户列表
     */
    List<AdminUserDO> getUserList(Collection<Long> ids);

    /**
     * 校验用户们是否有效。如下情况，视为无效：
     * 1. 用户编号不存在
     * 2. 用户被禁用
     *
     * @param ids 用户编号数组
     */
    void validateUserList(Collection<Long> ids);

    /**
     * 获得用户 Map
     *
     * @param ids 用户编号数组
     * @return 用户 Map
     */
    default Map<Long, AdminUserDO> getUserMap(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return new HashMap<>();
        }
        return CollectionUtils.convertMap(getUserList(ids), AdminUserDO::getId);
    }

    /**
     * 获得用户列表，基于昵称模糊匹配
     *
     * @param nickname 昵称
     * @return 用户列表
     */
    List<AdminUserDO> getUserListByNickname(String nickname);

    /**
     * 批量导入用户
     *
     * 新创建的用户为禁用状态，初始密码为 CSPRNG 随机密码（明文不可知），
     * 需管理员通过“重置密码”功能分配新密码并启用后，账号才可登录。
     *
     * 更新分支不写入状态列（状态只走 update-status 专用链路）；
     * 命中启用中超管角色的既有账号时，仅允许持超管角色的操作者更新。
     *
     * @param operatorUserId 操作者用户编号，用于超管目标校验；为 null 时按无特权 fail-closed 处理
     * @param importUsers     导入用户列表
     * @param isUpdateSupport 是否支持更新
     * @return 导入结果
     */
    UserImportResultDTO importUserList(Long operatorUserId, List<UserImportDTO> importUsers, boolean isUpdateSupport);

    /**
     * 获得指定状态的用户们
     *
     * @param status 状态
     * @return 用户们
     */
    List<AdminUserDO> getUserListByStatus(Integer status);

    /**
     * 判断密码是否匹配
     *
     * @param rawPassword 未加密的密码
     * @param encodedPassword 加密后的密码
     * @return 是否匹配
     */
    boolean isPasswordMatch(String rawPassword, String encodedPassword);

    /**
     * 在密码已经验证成功后，按需将旧工作因子的哈希升级为部署当前强度。数据库中的哈希已变化时不覆盖。
     *
     * @param id 用户编号
     * @param rawPassword 已验证成功的原始密码
     * @param expectedEncodedPassword 认证时读取的密码哈希
     * @return 未升级或成功升级后的哈希；并发凭据变更时抛出异常
     */
    String upgradePasswordEncodingIfNeeded(Long id, String rawPassword, String expectedEncodedPassword);
}
