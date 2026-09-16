# 规划协议与机器可读索引

- `query-plan.schema.json` / `query-plan.example.json`：首期受控查询计划结构。
- `report-spec.schema.json` / `report-spec.example.json`：首期报表结构与数据绑定样例。
- `report-data.example.json`：对应报表的合成结果；金额使用十进制字符串。
- `openapi-core.json`：核心开放API设计草案，非已实现接口；其余目录见04，实施时从代码生成并检查漂移。
- `upstream-registry.example.json`：上游登记样例，候选和未核实字段显式标记。
- `../tasks/index.json`：任务依赖和验收事实源；任务Markdown由此派生。

样例都是合成数据。结构校验不能代替鉴权、业务口径、SQL执行和模型效果测试。实施时把正式契约放到用户工作副本现有docs/contracts体系下，遵守“一处事实源”，避免双份独立演进。
