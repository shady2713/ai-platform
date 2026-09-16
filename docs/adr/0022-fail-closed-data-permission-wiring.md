# ADR 0022：受保护表的数据权限装配关闭失败

## 状态

已采纳。

## 背景

数据权限契约门禁能够阻断未分类的新应用表，并把受保护表的部门或用户列与最终 schema 交叉核对。
但旧自动配置同时以 `DeptDataPermissionRuleCustomizer` 和 `CurrentUserProvider` 作为条件：业务模块已经
登记受保护表、security starter 却未装配时，整个部门规则 Bean 会被静默跳过。MyBatis 仍正常执行
原 SQL，形成配置缺失导致的行级权限 fail-open。

权限服务返回空对象或字段不完整时也缺少统一验证；无表别名的 SQL 甚至会在构造错误信息时触发
二次空指针，掩盖真正的权限状态异常。

## 决策

- 部门规则自动配置只以受保护表 customizer 是否存在作为启用条件。
- 规则 Bean 直接依赖 `CurrentUserProvider`。存在受保护表但身份能力缺失时，Spring 上下文启动失败。
- `DeptDataPermissionRule` 在缓存权限响应前校验对象及 `all`、`self`、`deptIds`，任何缺失均抛出
  带用户编号、表名和可选别名的 `IllegalStateException`，不得执行原 SQL。
- 无认证主体仍不追加部门条件；匿名 HTTP 访问由 security starter 拒绝，后台无主体任务必须显式
  关闭数据权限并承担独立访问边界。
- 新增运行时测试直接覆盖自动配置失败、SQL 条件组合、规则筛选和上下文清理；模块建立独立行与
  分支覆盖率门槛。

## 影响

误删 security starter、覆盖身份 Bean 或改变自动配置顺序会在启动期暴露，而不是在生产查询时
静默返回越权数据。权限服务返回畸形对象会中止查询。只使用数据权限 starter、但没有登记任何
受保护表的基础模块仍可启动，不被强制依赖管理后台身份体系。
