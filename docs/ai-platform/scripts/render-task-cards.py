"""从任务事实源生成Markdown；只写本方案目录，不访问或写入源框架。"""
from __future__ import annotations
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INDEX = json.loads((ROOT / "tasks/index.json").read_text(encoding="utf-8"))
TASKS = INDEX["tasks"]
GROUPS = {
    "F": "基础与验证", "M": "模型中心", "A": "应用身份与授权",
    "S": "AI服务", "O": "开放API与运行", "K": "知识库",
    "D": "数据与工具", "R": "智能报表", "C": "Chat与SDK",
    "Q": "质量与交付", "X": "多模态及后续扩展", "Y": "跨系统"
}
def bullets(items):
    return "\n".join("- " + item for item in items)

for task in TASKS:
    dep_links = ", ".join(f"[{dep}]({dep}.md)" for dep in task["dependsOn"]) or "无；仍需确认授权工作副本"
    reads = bullets(f"[{name}](../{name})" for name in task["reads"])
    paths = bullets(f"\u0060{p}\u0060" for p in task["allowedPaths"])
    steps = "\n".join(f"{i}. {step}。" for i, step in enumerate(task["steps"], 1))
    cases = ", ".join(task["acceptanceCaseIds"]) or "本卡定义的专项验证；纳入所属阶段验收。"
    gate_lines = "\n".join("& .\\.harness\\verify.ps1 " + g for g in task["requiredGates"])
    lo, hi = task["estimatePersonDays"]
    out = f"""# {task['id']} {task['title']}

| 项目 | 内容 |
|---|---|
| 状态 | {task['status']}：本包只完成规划 |
| 阶段 | {task['phase']} |
| 需求 | {', '.join(task['requirements'])}，见[产品需求](../02-product-requirements.md) |
| 依赖 | {dep_links} |
| 风险 | {task['risk']} |
| 估算 | {task['size']}，约 {lo:g}–{hi:g} 人工作日；超过3日先细拆子卡 |

## 1. 任务目标与输入

目标：{task['title']}。所有依赖必须有验收证据；不能仅检查类/文件存在。

{reads}

还需读取授权副本最近的AGENTS.md、已有模块README、领域契约和.harness命令入口。框架最新规则优先，差异写入交接记录。

## 2. 允许修改范围

以下相对路径均相对**另行授权的工作副本**。本次规划不授权修改 \u0060E:\\kuangjia\\2026-main\u0060。目录表示该领域范围，不代表可以批量重写整棵目录。

{paths}

补充允许：同一变更对应的src/test、同目录*.test.ts/组件测试、所属模块README、必要的Convert映射，以及新增字段/权限的现有契约台账。这不授权改变其他模块内部实现。修改依赖版本、父POM、全局请求器或Harness拓扑，必须已明确列入本卡，否则先更新任务范围。DB仅允许领取新迁移号和同步最新快照，禁止改历史迁移。

## 3. 逐步实施

先列出本卡实际要创建/修改的文件、对外方法和验收用例，然后按顺序实施：

{steps}

每一步完成后编译或运行相应focused测试；共享接口先由契约确定，禁止消费者各自猜字段。发现本卡大于3个人工作日时，按持久化/服务/协议/页面/验证拆子卡，保留需求编号和完成证据。

## 4. 验收与失败分支

{bullets(task['acceptance'])}

关联验收用例：{cases} 详见[验收目录](../08-testing-acceptance.md)。本卡未列编号的专项测试同样是完成条件。

## 5. 必须产出

{bullets(task['deliverables'])}

## 6. 工程约束

{bullets(task['implementationRules'])}

## 7. 验证执行

在授权副本根目录执行相关门禁；不得在只读源框架执行。具体focused测试类/脚本由本卡新增的真实测试决定，并在交接记录写出确切命令。

\u0060\u0060\u0060powershell
{gate_lines}
\u0060\u0060\u0060

阶段结束还要运行完整Harness。浏览器测试命令在Q06建立前不得声称已经存在；跨源/渲染/权限用例在Q06与G5必须用真实浏览器补齐，早期组件测试不能替代最终浏览器验收。环境缺口写未验证，不降低门禁。

## 8. 完成证据

{bullets(task['completionEvidence'])}

交接格式见[模型交接规范](../09-model-handoff.md)。只有实现和本卡适用验证完成才能DONE；文档、Mock、生产实现的证据分开。

## 9. 应停止依赖工作并报告的条件

{bullets(task['stopConditions'])}

停止该依赖链不表示原需求取消；清楚记录阻断条件及可继续的独立任务。不得以TODO、假数据、空实现或注释测试作为交付。
"""
    (ROOT / "tasks" / f"{task['id']}.md").write_text(out, encoding="utf-8")

counts = {}
for task in TASKS:
    counts[task["phase"]] = counts.get(task["phase"], 0) + 1
totals = [sum(t["estimatePersonDays"][i] for t in TASKS) for i in (0, 1)]
readme = f"""# 开发任务卡

共 **{len(TASKS)}** 张卡；全部PLANNED。这里只是规划，未实施任何源框架代码。
阶段数量：{json.dumps(counts, ensure_ascii=False)}。

事实源为[index.json](index.json)；卡片由[render-task-cards.py](../scripts/render-task-cards.py)生成。计划改变先更新index，再生成卡片，避免两份事实源分叉。

## 领取方法

1. 先读[开发计划](../07-development-plan.md)和[模型交接](../09-model-handoff.md)。
2. 第一个可准备任务是F01；工作副本未授权前仅做只读检查。
3. 所有dependsOn为DONE且证据可读才是READY。一次只给执行模型一张卡及它引用的契约。
4. 实际进度保存在用户指定的任务系统/交接记录；不把运行日志和进度文件写进.harness。
5. 任务估算总计约{totals[0]:g}–{totals[1]:g}人工作日，含后续能力，未含需求等待与环境审批。不能将此数除以模型并发数当作日历工期。

## 路径别名

所有路径相对授权副本，源框架仍只读。ROOT表示仓库级基线或卡片明确的子路径，不能理解为无限修改授权。DB表示新迁移及最新快照，LEDGER表示本次变更相关台账。

| 别名 | 展开路径 |
|---|---|
"""
for alias, paths in INDEX["pathAliases"].items():
    readme += f"| {alias} | " + "<br>".join(f"\u0060{p}\u0060" for p in paths) + " |\n"
for group, title in GROUPS.items():
    readme += f"\n## {group} {title}\n\n| 任务 | 阶段 | 前置 | 风险 | 估算 |\n|---|---|---|---|---|\n"
    for task in (t for t in TASKS if t["id"].startswith(group)):
        readme += f"| [{task['id']} {task['title']}]({task['id']}.md) | {task['phase']} | {', '.join(task['dependsOn']) or '—'} | {task['risk']} | {task['size']} |\n"
readme += "\n## 追踪与验证\n\n[需求→任务→验收映射](traceability.md)；[文档检查说明](../verification/README.md)。\n"
(ROOT / "tasks/README.md").write_text(readme, encoding="utf-8")
matrix = "# 需求—任务—验收追踪\n\n同一FR可以由多张任务实现；表中AT编号来自卡片的明确引用，专项验证仍以卡片为准。引用用例不表示已经执行通过。\n\n| 需求 | 任务 | 已关联验收用例 |\n|---|---|---|\n"
for req in INDEX["requirements"]:
    linked = [t for t in TASKS if req in t["requirements"]]
    cases = sorted({a for t in linked for a in t["acceptanceCaseIds"]})
    matrix += f"| {req} | " + "、".join(f"[{t['id']}]({t['id']}.md)" for t in linked) + " | " + ", ".join(cases) + " |\n"
matrix += "\n## 验收→任务反向映射\n\n| 验收 | 任务 |\n|---|---|\n"
for n in range(1, 73):
    case = f"AT-{n:03}"
    matrix += f"| {case} | " + "、".join(f"[{t['id']}]({t['id']}.md)" for t in TASKS if case in t["acceptanceCaseIds"]) + " |\n"
(ROOT / "tasks/traceability.md").write_text(matrix, encoding="utf-8")
print(json.dumps({"rendered_task_cards": len(TASKS), "phases": counts}, ensure_ascii=False))
