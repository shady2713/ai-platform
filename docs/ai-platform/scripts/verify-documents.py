"""验证本方案；对源框架只读，所有输出仅写当前文档包。"""
from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.metadata
import json
import re
import subprocess
from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from urllib.parse import unquote

from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate as validate_openapi
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description="校验AI方案；原框架复核为可选只读检查。")
parser.add_argument("--source-root", type=Path, help="可选：指向原始只读框架，省略则仅校验文档。")
arguments = parser.parse_args()
SOURCE = arguments.source_root.resolve() if arguments.source_root else None
if SOURCE is not None and not (SOURCE / ".git").exists():
    parser.error("--source-root必须指向具有.git元数据的原始框架；不会自动创建仓库。")
EXPECTED_HEAD = "23a7edb375939a84bdfd01e8d1a68e7b016aab59"
CHECKS = []


def check(name, condition, detail=""):
    CHECKS.append({"name": name, "passed": bool(condition), "detail": detail})


def read_json(name):
    return json.loads((ROOT / name).read_text(encoding="utf-8"))


def expect_invalid(name, validator, value):
    errors = list(validator.iter_errors(value))
    check(name, bool(errors), "拒绝路径已触发" if errors else "反例被错误接受")


json_files = sorted(ROOT.rglob("*.json"))
for file in json_files:
    json.loads(file.read_text(encoding="utf-8"))
check("所有JSON可解析", True, f"{len(json_files)}份（含已有验证结果时计入）")

links = []
broken = []
for file in ROOT.rglob("*.md"):
    raw = file.read_text(encoding="utf-8")
    content = re.sub(r"```.*?```", "", raw, flags=re.S)
    for target in re.findall(r"\]\(([^)]+)\)", content):
        target = target.strip().strip("<>").split("#", 1)[0]
        if not target or re.match(r"[A-Za-z][A-Za-z0-9+.-]*:", target):
            continue
        path = (file.parent / unquote(target)).resolve()
        links.append((str(file.relative_to(ROOT)), target))
        if not path.exists():
            broken.append((str(file.relative_to(ROOT)), target))
check("文档本地链接存在", not broken, f"{len(links)}个链接；缺失={broken}")

index = read_json("tasks/index.json")
tasks = index["tasks"]
by_id = {t["id"]: t for t in tasks}
check("任务数量及ID唯一", len(tasks) == len(by_id) == 102)
check("任务卡均存在且标题一致", all((ROOT / "tasks" / (t["id"] + ".md")).read_text(encoding="utf-8").startswith(f"# {t['id']} {t['title']}") for t in tasks))
check("任务均为规划状态", all(t["status"] == "PLANNED" for t in tasks))
required_fields = ["requirements", "reads", "allowedPaths", "steps", "acceptance", "deliverables", "requiredGates", "completionEvidence", "stopConditions"]
check("任务执行字段非空", all(all(t.get(k) for k in required_fields) for t in tasks))
check("任务依赖存在且无自依赖", all(all(d in by_id and d != t["id"] for d in t["dependsOn"]) for t in tasks))
done = []
while len(done) < len(tasks):
    ready = [t["id"] for t in tasks if t["id"] not in done and all(d in done for d in t["dependsOn"])]
    if not ready:
        break
    done.extend(ready)
check("任务依赖图无环", len(done) == len(tasks), f"可拓扑排序{len(done)}项")
phase_order = {"P0": 0, "V1.0": 1, "V1.1": 2, "V1.2": 3, "V2": 4}
check("不存在依赖未来阶段", all(all(phase_order[by_id[d]["phase"]] <= phase_order[t["phase"]] for d in t["dependsOn"]) for t in tasks))
expected_fr = {f"FR-{i:02}" for i in range(1, 41)}
expected_at = {f"AT-{i:03}" for i in range(1, 73)}
check("40项需求均有任务映射", {fr for t in tasks for fr in t["requirements"]} == expected_fr)
check("72项验收均有任务映射", {case for t in tasks for case in t["acceptanceCaseIds"]} == expected_at)
prd = (ROOT / "02-product-requirements.md").read_text(encoding="utf-8")
tests_md = (ROOT / "08-testing-acceptance.md").read_text(encoding="utf-8")
check("PRD与任务需求编号一致", set(re.findall(r"\*\*(FR-\d{2}) ", prd)) == expected_fr)
check("验收目录编号一致", set(re.findall(r"\| (AT-\d{3}) \|", tests_md)) == expected_at)
check("任务允许路径未指向源绝对目录", all(all(not re.match(r"^[A-Za-z]:", p) and ".." not in Path(p).parts for p in t["allowedPaths"]) for t in tasks))

query_schema = read_json("contracts/query-plan.schema.json")
report_schema = read_json("contracts/report-spec.schema.json")
query = read_json("contracts/query-plan.example.json")
report = read_json("contracts/report-spec.example.json")
registry = Registry().with_resources([(s["$id"], Resource.from_contents(s)) for s in (query_schema, report_schema)])
for name, schema in [("QueryPlan", query_schema), ("ReportSpec", report_schema)]:
    Draft202012Validator.check_schema(schema)
    check(name + " Schema符合Draft2020-12", True)
qv = Draft202012Validator(query_schema, registry=registry, format_checker=FormatChecker())
rv = Draft202012Validator(report_schema, registry=registry, format_checker=FormatChecker())
qv.validate(query)
rv.validate(report)
check("QueryPlan与ReportSpec正例通过", True)
invalid = copy.deepcopy(query)
invalid["sql"] = "SELECT * FROM private_table"
expect_invalid("QueryPlan拒绝额外SQL字段", qv, invalid)
invalid = copy.deepcopy(query)
invalid["limit"] = 1001
expect_invalid("QueryPlan拒绝越界行数", qv, invalid)
invalid = copy.deepcopy(query)
invalid["metrics"] = ["SUM(x); DROP TABLE t"]
expect_invalid("QueryPlan拒绝表达式充当指标code", qv, invalid)
invalid = copy.deepcopy(query)
invalid["filters"][0]["operator"] = "RAW_SQL"
expect_invalid("QueryPlan拒绝未知操作符", qv, invalid)
invalid = copy.deepcopy(query)
invalid["timeRange"]["startInclusive"] = "昨天"
expect_invalid("QueryPlan拒绝未解析自然日期", qv, invalid)
invalid = copy.deepcopy(report)
invalid["html"] = "<script>alert(1)</script>"
expect_invalid("ReportSpec拒绝可执行HTML字段", rv, invalid)
invalid = copy.deepcopy(report)
invalid["blocks"][1]["chart"]["chartType"] = "custom-script"
expect_invalid("ReportSpec拒绝任意图表执行类型", rv, invalid)


def report_semantics(spec):
    blocks = {b["id"]: b for b in spec["blocks"]}
    datasets = {d["id"]: d for d in spec["datasetRefs"]}
    queries = {q["id"]: q for q in spec["queryRefs"]}
    if len(blocks) != len(spec["blocks"]) or len(datasets) != len(spec["datasetRefs"]) or len(queries) != len(spec["queryRefs"]):
        return False
    layout = spec["layout"]["items"]
    if len(layout) != len(blocks) or {x["blockId"] for x in layout} != set(blocks):
        return False
    used = set()
    for item in layout:
        if item["column"] + item["span"] > 12:
            return False
        for col in range(item["column"], item["column"] + item["span"]):
            cell = (item["row"], col)
            if cell in used:
                return False
            used.add(cell)
    for dataset in datasets.values():
        if dataset["queryRef"] not in queries:
            return False
        if len({c["field"] for c in dataset["columns"]}) != len(dataset["columns"]):
            return False
    for block in blocks.values():
        if block["type"] == "text":
            continue
        dataset_id = block.get("datasetRef", block.get("binding", {}).get("datasetRef"))
        if dataset_id not in datasets:
            return False
        columns = {c["field"] for c in datasets[dataset_id]["columns"]}
        fields = []
        if block["type"] == "chart":
            fields = [block["chart"]["categoryField"], block["chart"]["valueField"]]
            if "seriesField" in block["chart"]:
                fields.append(block["chart"]["seriesField"])
        elif block["type"] == "table":
            fields = [c["field"] for c in block["columns"]]
        elif block["type"] == "metric":
            fields = [block["binding"]["field"]]
            if block["rowIndex"] >= datasets[dataset_id]["rowCount"]:
                return False
        if not set(fields).issubset(columns):
            return False
    return True


check("报表正例引用与布局一致", report_semantics(report))
invalid = copy.deepcopy(report)
invalid["blocks"][1]["datasetRef"] = "nonexistent"
check("报表不存在数据引用被语义检查拒绝", not report_semantics(invalid))
invalid = copy.deepcopy(report)
invalid["blocks"][1]["chart"]["valueField"] = "unknown_amount"
check("报表不存在字段被语义检查拒绝", not report_semantics(invalid))
invalid = copy.deepcopy(report)
invalid["layout"]["items"][2]["column"] = 6
check("报表布局重叠被语义检查拒绝", not report_semantics(invalid))
time_range = query["timeRange"]
check("查询样例时间区间有序", datetime.fromisoformat(time_range["startInclusive"]) < datetime.fromisoformat(time_range["endExclusive"]))
data = read_json("contracts/report-data.example.json")
amounts = [Decimal(row["net_amount"]) for row in data["rows"]]
check("合成报表金额与黄金用例一致", amounts == [Decimal("450.00"), Decimal("290.00")] and sum(amounts) == Decimal("740.00"))

api = read_json("contracts/openapi-core.json")
# 离线规范校验：解析本包唯一外部ReportSpec引用及其QueryPlan引用。
# 原文件保持外部引用，校验拷贝使用等价components引用，避免联网访问设计用.invalid标识。
materialized = copy.deepcopy(api)
embedded_report = copy.deepcopy(report_schema)
embedded_query = copy.deepcopy(query_schema)
for item in (embedded_report, embedded_query):
    item.pop("$id", None)
    item.pop("$schema", None)


def replace_ref(node, source, dest):
    if isinstance(node, dict):
        if node.get("$ref") == source:
            node["$ref"] = dest
        for item in node.values():
            replace_ref(item, source, dest)
    elif isinstance(node, list):
        for item in node:
            replace_ref(item, source, dest)


replace_ref(embedded_report, query_schema["$id"], "#/components/schemas/QueryPlan")
replace_ref(materialized, "./report-spec.schema.json", "#/components/schemas/ReportSpec")
materialized["components"]["schemas"]["QueryPlan"] = embedded_query
materialized["components"]["schemas"]["ReportSpec"] = embedded_report
validate_openapi(materialized)
check("OpenAPI3.1核心规范通过", True, "外部本地Schema等价内联校验；不是完整已实现API")
ops = [op for p in api["paths"].values() for method, op in p.items() if method in {"get", "post", "put", "delete", "patch"}]
check("核心API操作ID唯一且全部声明认证", len({op["operationId"] for op in ops}) == len(ops) and all(op.get("security") for op in ops))


def git(*args):
    result = subprocess.run(["git", "-c", "core.fsmonitor=false", "--no-optional-locks", "-C", str(SOURCE), *args], check=True, capture_output=True, text=True, encoding="utf-8")
    return result.stdout.strip()


head = None
status = "NOT_REQUESTED"
source_hashes = {}
if SOURCE is not None:
    head = git("rev-parse", "HEAD")
    status = git("status", "--short", "--untracked-files=all")
    check("源框架HEAD仍为调查基线", head == EXPECTED_HEAD, head)
    check("源框架Git工作树仍无变更", status == "", status or "clean")
    files = ["AGENTS.md", "README.md", ".harness/verify.ps1", "docs/development-guide.md", "docs/contracts/field-catalog.yaml", "后端代码/basic-framework-boot/pom.xml", "后端代码/basic-framework-boot/basic-framework-dependencies/pom.xml", "前端代码/basic-framework-admin/package.json", "前端代码/basic-framework-admin/pnpm-lock.yaml", "数据库文件/basic_framework.sql"]
    source_hashes = {name: hashlib.sha256((SOURCE / name).read_bytes()).hexdigest() for name in files}
result = {
    "checkedAt": datetime.now(timezone.utc).isoformat(),
    "scope": "DOCUMENTS_AND_CONTRACT_EXAMPLES_ONLY",
    "passed": all(c["passed"] for c in CHECKS),
    "checks": CHECKS,
    "taskCount": len(tasks),
    "phaseCounts": dict(Counter(t["phase"] for t in tasks)),
    "requirements": len(expected_fr), "acceptanceScenarios": len(expected_at),
    "topologicalOrder": done,
    "validators": {name: importlib.metadata.version(name) for name in ["jsonschema", "openapi-spec-validator"]},
    "source": {"requested": SOURCE is not None, "path": str(SOURCE) if SOURCE else None, "head": head, "gitStatus": status, "currentKeyFileSha256": source_hashes, "hashNote": "仅在明确提供--source-root时读取；省略参数不会访问原电脑路径。"},
    "notVerified": ["框架完整Harness/构建", "上游运行时兼容及漏洞扫描", "实际模型效果", "业务API及SQL正确性", "权限生产实现", "浏览器交互", "性能/容量", "安装/升级/备份恢复"] + ([] if SOURCE else ["原始框架HEAD/工作树：本次未传--source-root"])
}
output = ROOT / "verification"
output.mkdir(exist_ok=True)
(output / "results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
lines = ["# 文档验证结果", "", f"执行时间（UTC）：{result['checkedAt']}", "", f"结论：{'通过' if result['passed'] else '未通过'}；{sum(c['passed'] for c in CHECKS)}/{len(CHECKS)}项检查通过。", "", "**仅验证文档包及协议样例；尚未实现平台。**", "", "| 检查 | 结果 | 说明 |", "|---|---|---|"]
for c in CHECKS:
    detail = str(c["detail"]).replace("|", "/").replace("\n", " ")
    lines.append(f"| {c['name']} | {'PASS' if c['passed'] else 'FAIL'} | {detail} |")
source_summary = f"源HEAD：`{head}`；Git status：`{status or 'clean'}`。" if SOURCE else "未传--source-root，本轮未复核原始框架，不依赖原电脑E盘。"
lines += ["", "## 数量与源代码复核", "", f"102项任务，40项FR，72项编号AT；阶段数量：{result['phaseCounts']}。", "", source_summary, "", "依赖图排序、校验器版本和可选源复核结果见[results.json](results.json)。本脚本不运行框架安装、构建、格式化、迁移或代码生成。", "", "## 未验证", "", *["- " + x for x in result["notVerified"]], "", "外部资料证据见[10](../10-decisions-sources.md)，产品验证由后续任务执行。"]
(output / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
print(json.dumps({"passed": result["passed"], "checks": len(CHECKS), "failed": [c for c in CHECKS if not c["passed"]], "tasks": len(tasks), "sourceStatus": status or "clean"}, ensure_ascii=False))
raise SystemExit(0 if result["passed"] else 1)
