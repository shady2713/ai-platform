# ADR 0033：剪除已移除功能的字典残留与死代码

- 状态：已接受

## 背景

功能删除（ADR 0029 短信渠道与短信登录、更早的社交登录）只清理了代码与快照，未覆盖
Flyway 权威迁移链中的字典种子：V1 仍向新库写入腾讯云短信渠道（TENCENT）与
"短信登录/社交登录"登录类型字典行。同时手工快照反向缺失活跃的"账号登录"（100）
字典行，导致快照与迁移终态双向漂移，且 check-data-lifecycle 门禁只比对表结构不比对
种子行，漂移无告警。代码侧另有少量零引用死代码（工具方法、死 VO、死常量类、死枚举值）
残留，前端存在零引用的弹窗组件与 API 封装。

## 决策

- 新增 V43 迁移按字典类型 + 值物理删除 TENCENT 渠道行与 101/103 登录类型行；
  迁移链终态为：短信渠道仅 ALIYUN，登录类型仅 100/200/202。
- 手工快照补回"账号登录"（100）字典行，使快照与迁移链终态逐行一致；
  快照基线版本号更新为 43。
- 删除全部零生产引用的死代码：后端 `Lock4jRedisKeyConstants`、`AuthMenuRespVO`、
  `LoginLogTypeEnum.LOGIN_SMS/LOGIN_SOCIAL` 及仅被自身单测引用的工具方法；
  前端 `UserSelectModal`、`DeptSelectModal`、三个零引用 API 函数与
  `buildOptionalQuantitySchema`。同步删除对应单测与前端契约测试中的相关断言。
- 适配器注册表消费的 `IconPicker`、`ApiComponent` 经复核为活跃代码（菜单表单按
  组件名使用），不在删除范围。
- `IPUtils` 初始化失败由静默降级改为抛出 `IllegalStateException`，遵循
  fail-loud 配置校验基线；登出端点补挂 `@RateLimiter` 与其他匿名端点对齐。

## 验证

contracts 门禁验证字段与生命周期台账；后端 `mvn verify`（单测 + 覆盖率棘轮 +
ArchUnit）与前端 `pnpm check`/`test:coverage` 验证删除后的编译与覆盖率；
V43 字典终态由 `RemovedCapabilityMigrationIT` 在真实 MySQL 上断言，
快照导入与迁移链的种子一致性由 `AuthenticationMigrationIT` 的基线路径断言。
