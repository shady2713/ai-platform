# ADR 0005：模块边界与能力接缝

- 状态：已接受

## 上下文

`common` 不能持有业务语义，core starter 不能通过依赖业务实现或另一个 starter 的内部类型
获取运行时状态。跨模块能力需要稳定、可替换且可由架构测试验证的接缝。

## 决策

1. system 与 infra 的公开 CommonApi/DTO 分别由
   `basic-framework-module-system-api` 和 `basic-framework-module-infra-api` 薄模块承载；
   实现留在拥有方业务模块。
2. core starter 只能依赖 `*-api` 契约模块，不能依赖业务模块实现。
3. starter 之间共享运行时状态时，在最低合理层定义 SPI，由能力拥有方注册实现，
   消费方通过构造器或 `ObjectProvider` 依赖契约。当前用户身份使用 `CurrentUserProvider`。
4. 转换器、注解和工具归属产生该语义的 starter；optional/provided 依赖不能用来掩盖反向分层。
5. ArchUnit 对合法 `..api..` 契约开放，对 Mapper、DO、ServiceImpl 和其他内部包保持阻断。

如果业务模块物理拆分为独立服务，需重新评估薄 API 模块与同步调用边界。
