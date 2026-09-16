# 开发目录初始化验收摘要

日期：2026-09-16。范围：首次初始化的历史验收记录。以下状态反映首次初始化时点；此后目录、Git与迁移方式以[00](../00-project-bootstrap.md)为准，提交前复核见[仓库复核记录](repository-review.md)。

## 已完成

| 项目 | 结果 |
|---|---|
| 框架文件 | 以manifest逐项校验2,576个文件；补齐2,169个缺失文件，保留407个相同文件 |
| 基线差异 | 2,574个原文件内容不变；仅根README增加开发入口、.gitignore增加本地文件忽略规则 |
| 原框架 | HEAD仍为23a7edb375939a84bdfd01e8d1a68e7b016aab59，Git工作树无变更 |
| 独立Git | 新建main分支，无提交、无远程，未复制原.git |
| 升级依据 | docs/framework-baseline.json及.framework-baseline中的源码快照 |
| 活动方案 | 统一位于docs/ai-platform，102张任务卡及协议样例齐全 |
| 原工程门禁 | 在当前副本执行Harness contracts，退出码0 |
| 文档可迁移性 | 不传原框架路径时32项检查通过；显式只读复核原框架时34项通过 |
| 打包脚本 | 5项测试通过：私有文件过滤、路径边界、中文/字节/执行权限、禁止覆盖归档、解压后空Git仓库可识别 |

文档校验依赖仅安装在Git忽略的本机目录，未修改Java/Vue产品依赖。现有前端.env与个人工具配置原地保留；迁移包排除这些本机文件。

## 实际验证入口

```text
.harness/verify.ps1 contracts
# 初始化打包工具现已移到项目外历史归档目录
python docs/ai-platform/scripts/verify-documents.py
python docs/ai-platform/scripts/verify-documents.py --source-root <只读原框架>
```

机器信息、原始命令输出和文件比较记录保存在当前仓库`.local-state/initialization/`；该目录不进入Git或迁移包。文档验证详情见[report.md](report.md)与[results.json](results.json)。

## 尚未验证

- backend、frontend、lockfile、dependencies、integration五项门禁。
- 前后端启动、数据库初始化、模型接入、产品功能及真实端到端性能。
- 独立Linux机器上的实际构建；已提供相对路径入口和Linux命令，打包测试覆盖Git目录及启动脚本权限保存。

因此“开发目录初始化完成”不能被后续模型解释为“F01所有条件已满足”或“AI平台已实现”。接手时先完成F01剩余门禁，再按任务依赖推进。
