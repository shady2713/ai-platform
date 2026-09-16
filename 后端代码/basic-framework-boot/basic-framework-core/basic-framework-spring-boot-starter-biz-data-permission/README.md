# Data Permission starter

该 starter 在 MyBatis-Plus SQL 执行前追加行级部门与当前用户条件。功能权限决定调用方能否进入
接口，数据权限决定已授权调用方能读取哪些行；两者不能互相替代。

## 表登记契约

- 业务模块通过 `DeptDataPermissionRuleCustomizer` 登记受保护表，必须调用表名与列名均为字符串
  字面量的 `addDeptColumn` / `addUserColumn` 重载。
- 表名与列名只接受简单 SQL 标识符；同一权限维度重复登记相同列是幂等操作，登记不同列会在启动期
  失败。规则返回的受保护表集合为只读视图，调用方不能在运行期移除保护范围。
- `scripts/check-data-permission.mjs` 将运行时登记与最终 Flyway schema、SQL 快照和
  `docs/contracts/data-permission-exemptions.json` 交叉核对。新增应用表未登记保护或显式豁免时，
  contracts 门禁阻断。
- 豁免只适用于全局控制面、平台托管或由专用主体条件保护的表；理由必须说明实际访问边界，不能用
  “暂不需要”等占位说明。

## 运行时边界

- 只要存在受保护表登记，应用必须装配 security starter 提供的 `CurrentUserProvider`；能力缺失时
  启动失败，不允许静默移除部门规则。
- 管理员请求从 `PermissionCommonApi` 获取部门、本人和全量范围，并缓存到当前请求身份上下文。
  空响应或缺失 `all`、`self`、`deptIds` 任一字段时关闭失败，不执行原 SQL。
- 多条规则以 `AND` 合并；部门与本人范围在单条部门规则内以带括号的 `OR` 合并，避免改变原 SQL
  优先级。
- 无认证主体时不追加部门条件；HTTP 匿名访问必须由 security starter 拒绝。确需无主体执行的
  后台维护路径必须在服务边界显式使用 `@DataPermission(enable = false)` 或
  `DataPermissionUtils.executeIgnore`，并由功能权限或任务所有权承担访问边界。

## 验证门槛

模块 JaCoCo 阻断门槛为行覆盖率 89%、分支覆盖率 84%。测试必须覆盖自动配置失败关闭、SQL 条件
组合、规则选择、缓存和异常后的 ThreadLocal 清理。
