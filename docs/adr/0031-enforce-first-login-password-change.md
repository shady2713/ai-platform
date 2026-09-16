# ADR 0031：首次登录强制修改密码从文档约定升级为运行时强制

- 状态：已接受

## 背景

安全审计确认一个"文档强、运行时弱"的缺口：

1. `数据库文件/basic_framework.sql` 中 `system_users.admin` 携带固定 BCrypt
   哈希并公开在仓库内；任何获得该交付物的部署实例初始口令即已知。
2. 此前唯一的约束是 docs/deployment.md 要求"首个登录后立即轮换管理员口令"——
   纯文档步骤，漏执行的实例直接以已知口令接入公网，代码层面没有任何
   must-change-password 机制。
3. 管理员创建账号、管理员重置他人密码分配的初始口令同属"已知口令"，此前
   同样没有任何强制轮换手段。

## 决策

1. `system_users` 增加 `must_change_password` 列（V40 迁移；合并 schema
   `数据库文件/basic_framework.sql` 同步）。迁移将种子管理员（id=1）置位；
   普通存量账号保持 `b'0'` 不受影响。
2. 登录门禁运行时强制：`AdminAuthServiceImpl.authenticate` 在密码与状态校验
   通过、失败计数清零之后检查标记，为真则记录登录日志
   （`LoginResultEnum.PASSWORD_EXPIRED`）并抛出 `AUTH_PASSWORD_EXPIRED`
   （1-002-000-019），不签发任何会话令牌。错误处理使用业务异常链路（专用错误码 + 前端按 code 分支）。
3. 新增认证前改密端点 `POST /system/auth/change-expired-password`
   （@PermitAll + @RateLimiter）：旧密码经 BCrypt 校验，未知/锁定账号走
   dummy-hash 时序防护并与旧密码错误共享 `AUTH_LOGIN_BAD_CREDENTIALS`
   响应；旧密码错误计入 `LoginProtectionService` 失败锁定；仅对
   `must_change_password=true` 的账号开放（否则
   `AUTH_PASSWORD_CHANGE_NOT_REQUIRED`）；新密码不得等于旧密码
   （`AUTH_PASSWORD_SAME_AS_OLD`），新密码复用 `@Password` 约束与
   `PasswordPolicy` 上下文弱密码检查；成功后经
   `AdminUserService.completeExpiredPasswordChange` 原子匹配旧哈希、启用状态与强制改密标记后更新密码、清除标记并撤销全部会话。失败登录日志使用独立事务保存；成功改密只记操作日志，不冒充登录成功。
4. 标记的写入侧收口在 `AdminUserServiceImpl`：`createUser` 与管理员重置密码
   （`updateUserPassword(id, password)`）一律置位；用户自己改密
   （`updateUserPassword(id, old, new)`）、短信重置与首登改密
   （`completePasswordChange`）成功后清除。
5. 前端登录页捕获 `AUTH_PASSWORD_EXPIRED` 错误码后切换为"首次登录须修改密码"
   表单，复用已输入的用户名与原密码，校验新密码策略与确认一致性；改密成功后
   以新密码直接进入正常登录流程（启用图形验证码时回填表单由用户触发）。

## 后果

- 强制改密负责轮换管理员分配的口令；种子初始化使用独立部署凭据，见部署文档。
- 所有"他人代设"的口令（管理员创建、管理员重置）都在下次登录前强制轮换；
  唯一例外是 Excel 导入账号——它们创建即禁用且携带 CSPRNG 随机口令，
  激活路径必经管理员重置密码，届时自动置位。
- 认证前改密端点扩大了匿名攻击面，因此叠加四重约束：IP 限流、失败锁定
  联动、时序防护、仅标记账号可用；新口令仍须通过统一密码策略。
- 改密成功会撤销该账号全部既有会话（复用 PASSWORD_CHANGED 撤销路径），
  防止旧口令时代遗留会话继续可用。
