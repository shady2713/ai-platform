# 基础框架前端

基于 `Vue 3`、`Vite`、`TypeScript`、`Element Plus` 的管理后台前端项目。

## 环境要求

- `Node.js >= 20.19`（与根 README 及 package.json engines 一致）
- `pnpm >= 10`（锁文件由 `pnpm@10.28.2` 生成）

## 快速开始

```bash
pnpm install --frozen-lockfile
pnpm dev:ele
```

默认开发地址：

- `http://localhost:5174`

## 常用命令

```bash
pnpm dev:ele
pnpm build:ele
pnpm test:unit
pnpm check
pnpm lint
pnpm test:coverage
```

## 目录说明

- `apps/web-ele`：前端应用
- `packages`：共享业务包与基础能力
- `internal`：构建配置与内部工具
- `scripts`：工程脚本

## 会话与权限边界

访问令牌仅保存在内存；页面刷新通过同源 HttpOnly Cookie 恢复会话。并发恢复共享一次请求，退出登录后不再自动尝试恢复；退出或切换账号时到达的旧登录、权限和刷新响应不得覆盖当前状态。

业务请求客户端只向 API 同源地址附带凭据。跨源请求关闭凭据发送，其 401 不进入本站的刷新、重试队列或登出流程，避免文件源认证失败清空管理端会话。

登录只有在用户及权限信息加载成功后才进入应用。退出立即清空账号数据和动态路由，即使退出接口暂时不可用也保持本地未登录状态。同一账号重新认证后重新生成当前页权限；切换账号进入首页。每次生成路由先注销上一批注册，保留静态路由。

初始密码账号通过登录页强制改密，成功后重新执行正常登录流程。密码规则以字段契约和后端校验为准。

`pnpm lint` 对 ESLint、Stylelint 执行零告警门禁。组件测试允许在同一文件声明宿主及子组件夹具，生产组件仍执行每文件一个组件规则。

完整前端 Harness 通过根目录 `scripts/run-frontend-build.mjs` 构建生产包，并检查所有产物 JavaScript 的未声明变量；第三方依赖同样受检。该静态检查不能代替真实浏览器与后端联调。

`test:coverage` 只运行测试并生成覆盖率报告；完整前端验证还包含构建与单文件覆盖率棘轮。从仓库根目录运行 Harness `frontend`，命令见[根 README](../../README.md)。

`lodash-es@4.18.0` 的上游漏导入通过 `patchedDependencies` 管理，安装时由 pnpm 按锁文件应用三条导入补丁；不修改应用 API 或原型污染防护。原因、完整性验证和移除条件见 [ADR 0041](../../docs/adr/0041-lodash-es-patch-and-built-javascript-check.md)。

## 文案语言约定

业务文案（路由标题、表单 label、提示语、校验消息）为单语中文，直接书写，不要求接入 `locales`；框架层（`@core`/基础组件）保留 vue-i18n 基建与 `$t` 用法。同一文件内禁止两种风格混用：引用既有 `$t` 词条时沿用词条，新增文案一律中文。

## 发布说明

发布前请按实际环境调整以下配置：

- `apps/web-ele/.env`
- `apps/web-ele/.env.development`
- `apps/web-ele/.env.production`
