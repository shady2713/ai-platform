package com.basicframework.framework.datapermission.core.rule.dept;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.security.CurrentUserProvider;
import com.basicframework.framework.common.util.collection.CollectionUtils;
import com.basicframework.framework.datapermission.core.rule.DataPermissionRule;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import com.basicframework.framework.mybatis.core.util.MyBatisUtils;
import com.basicframework.module.system.api.permission.PermissionCommonApi;
import com.basicframework.module.system.api.permission.dto.DeptDataPermissionRespDTO;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;

/**
 * 基于部门的 {@link DataPermissionRule} 数据权限规则实现
 *
 * 注意，使用 DeptDataPermissionRule 时，需要保证表中有 dept_id 部门编号的字段，可自定义。
 *
 * 实际业务场景下，会存在一个经典的问题？当用户修改部门时，冗余的 dept_id 是否需要修改？
 * 1. 一般情况下，dept_id 不进行修改，则会导致用户看不到之前的数据。【basic-framework-server 采用该方案】
 * 2. 部分情况下，希望该用户还是能看到之前的数据，则有两种方式解决：【需要你改造该 DeptDataPermissionRule 的实现代码】
 *  1）编写洗数据的脚本，将 dept_id 修改成新部门的编号；【建议】
 *      最终过滤条件是 WHERE dept_id = ?
 *  2）洗数据的话，可能涉及的数据量较大，也可以采用 user_id 进行过滤的方式，此时需要获取到 dept_id 对应的所有 user_id 用户编号；
 *      最终过滤条件是 WHERE user_id IN (?, ?, ? ...)
 *  3）想要保证原 dept_id 和 user_id 都可以看的到，此时使用 dept_id 和 user_id 一起过滤；
 *      最终过滤条件是 WHERE dept_id = ? OR user_id IN (?, ?, ? ...)
 *
 */
@Slf4j
public class DeptDataPermissionRule implements DataPermissionRule {

    /**
     * LoginUser 的 Context 缓存 Key
     */
    protected static final String CONTEXT_KEY = DeptDataPermissionRule.class.getSimpleName();

    private static final String DEPT_COLUMN_NAME = "dept_id";
    private static final String USER_COLUMN_NAME = "user_id";
    private static final Pattern SQL_IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final PermissionCommonApi permissionApi;
    /** 当前登录用户身份接缝；存在受保护表时由自动配置强制要求。 */
    private final CurrentUserProvider currentUserProvider;

    public DeptDataPermissionRule(PermissionCommonApi permissionApi, CurrentUserProvider currentUserProvider) {
        this.permissionApi = permissionApi;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * 基于部门的表字段配置
     * 一般情况下，每个表的部门编号字段是 dept_id，通过该配置自定义。
     *
     * key：表名
     * value：字段名
     */
    private final Map<String, String> deptColumns = new HashMap<>();
    /**
     * 基于用户的表字段配置
     * 一般情况下，每个表的部门编号字段是 dept_id，通过该配置自定义。
     *
     * key：表名
     * value：字段名
     */
    private final Map<String, String> userColumns = new HashMap<>();
    /**
     * 所有表名，是 {@link #deptColumns} 和 {@link #userColumns} 的合集
     */
    private final Set<String> tableNames = new HashSet<>();

    @Override
    public Set<String> getTableNames() {
        return Collections.unmodifiableSet(tableNames);
    }

    @Override
    public Expression getExpression(String tableName, Alias tableAlias) {
        // 只有有登陆用户的情况下，才进行数据权限的处理
        Long loginUserId = currentUserProvider.getLoginUserId();
        if (loginUserId == null) {
            return null;
        }
        // 只有管理员类型的用户，才进行数据权限的处理
        if (ObjectUtil.notEqual(currentUserProvider.getLoginUserType(), UserTypeEnum.ADMIN.getValue())) {
            return null;
        }

        // 获得数据权限
        DeptDataPermissionRespDTO deptDataPermission =
                currentUserProvider.getContext(CONTEXT_KEY, DeptDataPermissionRespDTO.class);
        // 从上下文中拿不到，则调用逻辑进行获取
        if (deptDataPermission == null) {
            deptDataPermission = permissionApi.getDeptDataPermission(loginUserId);
            if (deptDataPermission == null) {
                throw invalidPermissionState(loginUserId, tableName, tableAlias, "未返回数据权限");
            }
            validatePermissionState(loginUserId, tableName, tableAlias, deptDataPermission);
            // 添加到上下文中，避免重复计算
            currentUserProvider.setContext(CONTEXT_KEY, deptDataPermission);
        } else {
            validatePermissionState(loginUserId, tableName, tableAlias, deptDataPermission);
        }

        // 情况一，如果是 ALL 可查看全部，则无需拼接条件
        if (Boolean.TRUE.equals(deptDataPermission.getAll())) {
            return null;
        }

        // 情况二，即不能查看部门，又不能查看自己，则说明 100% 无权限
        if (CollUtil.isEmpty(deptDataPermission.getDeptIds()) && Boolean.FALSE.equals(deptDataPermission.getSelf())) {
            return new EqualsTo(null, null); // WHERE null = null，可以保证返回的数据为空
        }

        // 情况三，拼接 Dept 和 User 的条件，最后组合
        Expression deptExpression = buildDeptExpression(tableName, tableAlias, deptDataPermission.getDeptIds());
        Expression userExpression =
                buildUserExpression(tableName, tableAlias, deptDataPermission.getSelf(), loginUserId);
        if (deptExpression == null && userExpression == null) {
            log.warn(
                    "[getExpression][loginUserId({}) table({}/{}) deptCount({}) self({}) 构建的条件为空]",
                    loginUserId,
                    tableName,
                    tableAlias,
                    CollUtil.size(deptDataPermission.getDeptIds()),
                    deptDataPermission.getSelf());
            return new EqualsTo(null, null); // WHERE null = null，可以保证返回的数据为空
        }
        if (deptExpression == null) {
            return userExpression;
        }
        if (userExpression == null) {
            return deptExpression;
        }
        // 目前，如果有指定部门 + 可查看自己，采用 OR 条件。即，WHERE (dept_id IN ? OR user_id = ?)
        return new ParenthesedExpressionList(new OrExpression(deptExpression, userExpression));
    }

    private static void validatePermissionState(
            Long loginUserId, String tableName, Alias tableAlias, DeptDataPermissionRespDTO dataPermission) {
        if (dataPermission.getAll() == null
                || dataPermission.getSelf() == null
                || dataPermission.getDeptIds() == null) {
            throw invalidPermissionState(loginUserId, tableName, tableAlias, "数据权限响应不完整");
        }
    }

    private static IllegalStateException invalidPermissionState(
            Long loginUserId, String tableName, Alias tableAlias, String reason) {
        String aliasName = tableAlias != null ? tableAlias.getName() : "<none>";
        return new IllegalStateException(
                "LoginUser(%d) Table(%s/%s) %s".formatted(loginUserId, tableName, aliasName, reason));
    }

    private Expression buildDeptExpression(String tableName, Alias tableAlias, Set<Long> deptIds) {
        // 如果不存在配置，则无需作为条件
        String columnName = deptColumns.get(tableName);
        if (StrUtil.isEmpty(columnName)) {
            return null;
        }
        // 如果为空，则无条件
        if (CollUtil.isEmpty(deptIds)) {
            return null;
        }
        // 拼接条件
        return new InExpression(
                MyBatisUtils.buildColumn(tableName, tableAlias, columnName),
                // Parenthesis 的目的，是提供 (1,2,3) 的 () 左右括号
                new ParenthesedExpressionList(
                        new ExpressionList<LongValue>(CollectionUtils.convertList(deptIds, LongValue::new))));
    }

    private Expression buildUserExpression(String tableName, Alias tableAlias, Boolean self, Long userId) {
        // 如果不查看自己，则无需作为条件
        if (Boolean.FALSE.equals(self)) {
            return null;
        }
        String columnName = userColumns.get(tableName);
        if (StrUtil.isEmpty(columnName)) {
            return null;
        }
        // 拼接条件
        return new EqualsTo(MyBatisUtils.buildColumn(tableName, tableAlias, columnName), new LongValue(userId));
    }

    // ==================== 添加配置 ====================

    public void addDeptColumn(Class<? extends BaseDO> entityClass) {
        addDeptColumn(entityClass, DEPT_COLUMN_NAME);
    }

    public void addDeptColumn(Class<? extends BaseDO> entityClass, String columnName) {
        String tableName = TableInfoHelper.getTableInfo(entityClass).getTableName();
        addDeptColumn(tableName, columnName);
    }

    public void addDeptColumn(String tableName, String columnName) {
        registerColumn(deptColumns, tableName, columnName, "部门");
    }

    public void addUserColumn(Class<? extends BaseDO> entityClass) {
        addUserColumn(entityClass, USER_COLUMN_NAME);
    }

    public void addUserColumn(Class<? extends BaseDO> entityClass, String columnName) {
        String tableName = TableInfoHelper.getTableInfo(entityClass).getTableName();
        addUserColumn(tableName, columnName);
    }

    public void addUserColumn(String tableName, String columnName) {
        registerColumn(userColumns, tableName, columnName, "用户");
    }

    private void registerColumn(
            Map<String, String> columns, String tableName, String columnName, String permissionDimension) {
        validateIdentifier(tableName, "表名");
        validateIdentifier(columnName, "列名");
        String registeredColumn = columns.putIfAbsent(tableName, columnName);
        if (registeredColumn != null && !registeredColumn.equals(columnName)) {
            throw new IllegalStateException("%s数据权限表 %s 已登记列 %s，不能重复登记为 %s"
                    .formatted(permissionDimension, tableName, registeredColumn, columnName));
        }
        tableNames.add(tableName);
    }

    private static void validateIdentifier(String identifier, String description) {
        if (identifier == null || !SQL_IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(description + "必须是简单 SQL 标识符");
        }
    }
}
