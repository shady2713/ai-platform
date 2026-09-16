# ADR 0010：按用户类型路由会话 Provider

- 状态：已接受

## 背景

管理后台框架保留 `/app-api`，供项目按需对接微信小程序、APP 和 H5。此前安全过滤器
只注入一个 `UserSessionCommonApi`，而现有实现只识别管理员会话；这会让应用端接口
要么无法认证，要么在未来扩展时错误复用管理后台会话边界。

## 决策

1. `UserSessionCommonApi#getSupportedUserType()` 是会话 Provider 的类型声明。
2. Token 过滤器启动时按用户类型建立不可变索引；同一类型重复注册立即启动失败。
3. `/admin-api` 使用 ADMIN Provider，`/app-api` 使用 MEMBER Provider；缺少 Provider 时拒绝认证，不跨类型回退。
4. system 模块只交付 ADMIN Provider。业务项目启用会员端时，由独立应用/会员模块注册 MEMBER Provider；基础框架不伪造会员实现。

## 后果

- 管理端和应用端令牌边界明确，应用端预留可以独立演进。
- 尚未接入会员模块时，受保护的 `/app-api` 请求会明确失败；公开端点仍需逐项加入 permit-all。
- 新增其他用户类型必须同时提供唯一 Provider 和认证测试。
