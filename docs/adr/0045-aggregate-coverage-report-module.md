# ADR 0045：覆盖率聚合模块

- 状态：已采纳
- 日期：2026-09-10

## 背景

后端覆盖率棘轮原先按模块读取 JaCoCo 报告。JaCoCo 的 report 目标只分析**本模块**的
输出目录，因此：

- 执行数据本身不受影响：basic-framework-server 的集成测试在同一个 JVM 内运行，
  其 jacoco.exec 已经包含被调用到的 module-system / module-infra 类的执行记录；
- 但**报告**只覆盖 server 自己的类，module-system 的类在逐模块报告里仍是 0%。

后果是棘轮对**只有集成测试覆盖**的那部分代码给出 0% 下限。实测 UserSessionMapper
基线为 0%，而 SessionPersistenceIT 确实调用了 createSession / refreshSession，
即 rotate、selectByRefreshTokenOrFamilyHash 等默认方法已被覆盖，门禁却看不见。
该类文件的覆盖率下限因此永远停在 0：删除集成回归测试不会让任何门禁变红，
而会话撤销与刷新轮换正是本仓库最需要保护的安全资产（威胁模型 T2）。

ADR 0019 已堵住「模块无报告」的漏洞，但没有解决**跨模块执行数据归因**。

## 决策

1. 新增无生产源码的 basic-framework-coverage 模块，唯一职责是生成聚合覆盖率报告。
2. 该模块**逐个显式声明**全部生产模块依赖。jacoco:report-aggregate 只聚合当前项目的
   直接依赖（basic-framework-server 的直接依赖仅 3 个模块，实测报告只含 411 个源文件，
   缺 185 个），因此依赖必须显式且完整；不得依赖传递性。
3. 该模块是**叶子模块**：不得被任何模块依赖，否则会污染其运行时类路径。
4. check-coverage-ratchet.mjs 优先读取聚合报告，并保留 ADR 0019 的完整性兜底：
   整体缺席聚合报告的生产模块仍按 0% 计入，新增模块不会重新变成盲区。
   聚合报告缺失时回退到逐模块报告，保持向后兼容。
5. 聚合报告只携带 package + 文件名，脚本按 src/main/java 相对路径建立源码索引回查仓库路径。
   生成源码（MapStruct ConvertImpl）不在索引内，按预期忽略。

## 后果

- 后端单文件覆盖率以**唯一权威报告**为准；module-* 层的集成测试覆盖被正确计入，
  提升后的基线会真正守住这些回归测试（下限只能上调）。
- 门禁必须运行 clean：jacoco.exec 默认追加写入，未清理的陈旧执行数据会抬高读数。
  两个 Harness 门禁均已使用 clean verify，本次变更不改变该约定。
- 新增生产模块时必须同步登记到 basic-framework-coverage 的依赖列表，
  否则该模块会被完整性兜底按 0% 计入并使棘轮变红——这是刻意的 fail-closed 行为。
- 聚合报告已实测覆盖 15 个模块、600 个源文件，其中 366 个含可测行（其余为抽象类、
  纯接口与 DTO，JaCoCo 不产生 LINE 计数），与登记基线的 366 项一致。