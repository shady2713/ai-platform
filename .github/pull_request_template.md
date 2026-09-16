## 变更目的

<!-- 一句话说明本 PR 解决什么问题 / 交付什么能力。一个 PR 只做一件事。 -->

## 影响边界

- [ ] 后端（模块：____）
- [ ] 前端（包：____）
- [ ] 数据库迁移（新增 migration：____，已同步 数据库文件/basic_framework.sql）
- [ ] 工程规则 / 门禁（已同步根 AGENTS.md 与 .harness/）
- [ ] 文档 / ADR（新增或更新：____）

## 自检清单（对应 docs/development-guide.md 第 4 节）

- [ ] 本地 Harness `all` 通过（Windows `& .\.harness\verify.ps1 all`；Linux `sh .harness/verify.sh all`）
- [ ] 新增/变更的字段已登记 `docs/contracts/field-catalog.yaml`
- [ ] 新增的数据表已登记 `docs/contracts/data-lifecycle.json` 与数据权限分类
- [ ] 新增配置项有校验、默认值在 owning Properties 类中，无硬编码常量与秘密
- [ ] 新增入口已定性为 public / 认证 / 专用权限，且 Service 层有对应授权校验
- [ ] 覆盖率只升不降；变更文件的单文件下限未被下调
- [ ] 无新增 TODO / FIXME / XXX，逻辑源码未超 800 行
- [ ] 非平凡决策已记录 `docs/adr/`

## 验证证据

<!-- 贴出关键门禁命令与结果；破坏性变更请给出变红演示。 -->

## 已知取舍与遗留

<!-- 明确写出本 PR 未覆盖的边界，避免用绿色构建冒充已验证。 -->