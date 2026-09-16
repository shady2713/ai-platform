# ADR 0041: 修复 lodash-es 发布缺陷并检查生产 JavaScript

- 状态：已采纳
- 日期：2026-09-08

## 背景

前端单元测试与生产构建通过后，真实 Chromium 加载登录页仍白屏。Element Plus 初始化调用 `lodash-es@4.18.0` 的 `fromPairs`，该文件引用 `baseAssignValue` 却未导入。直接导入依赖并调用非空键值对可复现相同 ReferenceError，与 Vite 分包无关。同版本 `template` 还漏导入 `assignWith` 和 `arrayEach`，普通模板插值同样报错。

从 [npm 官方 tarball](https://registry.npmjs.org/lodash-es/-/lodash-es-4.18.0.tgz) 读取的原始文件存在同样缺陷；下载包 SHA-512 与 npm 元数据及锁文件一致：`sha512-koAgswPPA+UTaPN64Etp+PGP+WT6oqOS2NMi5yDkMaiGw9qY4VxQbQF0mtKMyr4BlTznWyzePV5UpECTJQmSUA==`。因此不属于本机安装目录损坏。

## 决策

1. 保留当前依赖版本及安全处理，以 pnpm `patchedDependencies` 登记补丁，仅增加上述三条缺失导入。补丁和摘要随锁文件管理，禁止直接修改安装目录充当修复。
2. 在实际 Element Plus 依赖解析路径下测试非空 `fromPairs`、`__proto__` 自有数据属性且对象原型不变、普通模板插值。
3. 既有生产构建入口在构建结束后，对全部产物 JavaScript 执行 ESLint `no-undef`，解析错误、未声明变量及依赖内联禁用指令均不能绕过检查。坏产物实测仅报 `baseAssignValue` 未声明。
4. 允许标准浏览器与 Worker 全局，以及产物中已核实的跨运行时探测名称和 Vue I18n 编译标记；名单和原因集中在检查脚本，不豁免任何第三方包。

## 结果与边界

无需增加浏览器依赖、修改分包策略或降低现有门禁。静态检查发现未绑定名称，但不能证明网络、权限流程或所有运行时分支正确；真实浏览器冒烟和集成测试仍有独立价值。

升级到官方已修复版本时，先以相同行为测试及真实生产页面验证，再移除补丁与 `patchedDependencies` 登记；保留行为回归和产物检查。
