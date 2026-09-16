# useSimpleLocale

框架组件共用的轻量语言状态，默认 `zh-CN`。通过 `setSimpleLocale` 切换已登记语言， `$t` 查询词条，缺失时返回原 key。状态由 `createSharedComposable` 共享，业务应用的完整国际化使用所属应用的 locales 入口。
