# 文档包验证

本目录验证的是**规划文档与协议样例**，不表示平台功能、模型效果、依赖兼容、权限实现或性能已通过。产品开发与验证仍由任务卡执行。

## 检查内容

- 文档相对链接、JSON可解析性与任务卡字段完整性。
- 102项任务的依赖存在性、无环、分期顺序，40项FR及72项AT的追踪覆盖。
- QueryPlan/ReportSpec的JSON Schema Draft 2020-12自校验、正例和反例。
- 报表引用、字段、布局和样例金额的额外语义断言。
- 核心OpenAPI 3.1规范校验；这是完整接口目录的设计子集。
- 可选的源框架HEAD及Git工作树只读复核；只有显式传入源目录才执行。

## 重现方式

使用Python及`jsonschema==4.25.1`、`openapi-spec-validator==0.7.2`。可在临时虚拟环境中安装`requirements.txt`中的工具；它们只用于文档校验，不是Java/Vue产品运行依赖。

```sh
# 从当前项目根目录，在已准备好的Python校验环境中运行
python docs/ai-platform/scripts/render-task-cards.py
python docs/ai-platform/scripts/verify-documents.py
# 如需额外核对原框架：追加 --source-root <只读原框架目录>
```

脚本只写本包verification结果；省略--source-root时不访问原框架。提供源目录时，仅运行无可选锁Git查询和读取关键文件，不调用源框架Harness、Maven、pnpm、数据库或模型。

## 结果

结果由实际执行生成于[results.json](results.json)及[report.md](report.md)。若脚本失败，以失败为准，不能使用本README描述代替结果。

外部引用已经在调查阶段按官方资料核查；本脚本不重复联网抓取，也不把资料核查当作运行时兼容实验。首次校验时安装依赖遇到代理连接错误，改用PyPI直连后成功；TLS校验未关闭。
