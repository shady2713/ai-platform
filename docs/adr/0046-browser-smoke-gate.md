# ADR 0046：浏览器端到端冒烟门禁

- 状态：已接受，待落地（受依赖锁定约束，见下）
- 日期：2026-09-10

## 背景

ADR 0041 记录了一次真实事故：单元测试、类型检查、lint、生产构建与产物检查**全部通过**，
但真实 Chromium 打开登录页白屏（lodash-es 4.18.0 缺导入）。事后以「产物 ESLint no-undef」
作为部分补救，并明确承认「真实浏览器冒烟和集成测试仍有独立价值」。

这意味着当前门禁矩阵存在**结构性盲区**：所有门禁都在代码层，没有任何一层验证
「产品在真实浏览器里能否渲染」。ADR 0041 的补救只能发现「未绑定名称」这一类缺陷，
不能证明网络、权限流程或运行时分支正确。

## 决策

1. 引入浏览器端到端冒烟门禁，覆盖最小关键路径：
   登录 → 强制改密 → 列表页渲染 → 创建/编辑 → 登出。
2. 只断言「页面渲染出来且关键请求返回成功」，**不做视觉回归**，避免 flaky。
3. 该门禁进入 nightly / release 层，而非每次 PR：PR 层保持 ≤10 分钟反馈；
   release 与 nightly 层同样阻断，不降低任何既有门禁的强度。
4. 必须使用 `@playwright/test` 的锁定版本，纳入 `pnpm-lock.yaml` 管理。

## 未落地的原因与落地步骤

本仓库的既有约定要求「新增可机检规则必须能对代表性违规变红，并接入阻断聚合」。
当前变更环境无法生成锁文件（无网络），若直接写入依赖声明会使
`lockfile-integrity` 门禁（`pnpm install --frozen-lockfile`）变红，
属于**用绿色构建冒充已验证**的反模式，因此本 ADR 只记录决策，不提交不可运行的脚本。

按顺序执行：

1. `cd 前端代码/basic-framework-admin && pnpm add -D -w @playwright/test`，提交更新后的 `pnpm-lock.yaml`。
2. 新增 `apps/web-ele/e2e/smoke.spec.ts` 与 `playwright.config.ts`（`webServer` 指向 `pnpm dev:ele`）。
3. `pnpm exec playwright install --with-deps chromium`。
4. 先验证门禁能变红：临时破坏登录页渲染，确认门禁失败；恢复后记录一次绿色运行。
5. 新增 `scripts/check-e2e-smoke.mjs` 包装执行，并按 `.harness/AGENTS.md` 的变更协议
   接入 `.harness/verify.sh` 与 `.harness/verify.ps1`（双 provider 必须对齐，
   `check-gate-wiring.mjs` 会强制这一点）。
6. 新增 nightly workflow 调用该门禁；`aggregate` 保持唯一必需检查。

## 后果

- 落地后，「全部门禁绿但产品白屏」这一类缺陷将被阻断。
- 在落地之前，本条为**已知且有据可查的缺口**：不得声称门禁矩阵覆盖了产品层可运行性。