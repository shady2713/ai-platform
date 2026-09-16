# 高风险操作与授权清单

管理端高风险命令必须保留服务端功能权限、参数校验和操作审计。个人资料操作仅针对当前认证用户；
自行改密还必须验证旧密码。**前端确认不能替代服务端授权。**

## 权威清单

「哪些入口属于高风险」的**唯一来源是可执行测试**，本页不再重复方法清单，避免两处漂移：

- [system SecuritySensitiveOperationTest](../../后端代码/basic-framework-boot/basic-framework-module-system/src/test/java/com/basicframework/module/system/controller/admin/SecuritySensitiveOperationTest.java)
- [infra SecuritySensitiveOperationTest](../../后端代码/basic-framework-boot/basic-framework-module-infra/src/test/java/com/basicframework/module/infra/controller/admin/SecuritySensitiveOperationTest.java)

两个测试断言已登记的高风险方法仍受专用功能权限或当前用户认证约束。
**新增高风险入口时必须同时登记权限与测试**，漏登记会被该测试阻断。

覆盖范围（粗粒度，精确清单以测试为准）：账号生命周期与敏感导出、本人联系方式与密码、
角色与菜单权限及数据范围分配、权限资源定义、会话强制撤销、短信渠道凭证、
动态参数控制面、文件存储凭证控制面、定时任务定义与立即执行。

## 附加边界

- 涉及超级管理员角色的授予、撤销、菜单和数据范围变更，必须由持有**启用中**超级管理员角色的
  操作者执行，见 `PermissionServiceImpl` 的授权边界（ADR 0024）。
- infra 控制面操作通过 `@LogRecord` 记录名称与动作，**不输出配置值或存储凭据**。
- 账号与授权变化必须撤销相关会话（见 [威胁模型](threat-model.md) T3）。