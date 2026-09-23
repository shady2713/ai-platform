# 报表渲染层（R07）

`ReportSpec` + 版本数据 → 报表预览的**唯一**渲染入口（Chat 与报表页共用）。

## 模块

| 文件 | 职责 |
| --- | --- |
| `reportSpec.ts` | ReportSpec v1 与版本数据的 zod 契约（严格模式：未知键/未知块类型/脚本片段拒绝）；块绑定数据 → 视图模型；图表块 → `ChartSpec`（含降级原因） |
| `AiReportView.vue` | 组件：`spec` + `data` + `theme` + `failureReason`；按 12 列栅格渲染 metric/text/table/chart；元信息条展示完整性、来源与失败状态 |

## 用法

```vue
<script setup lang="ts">
import { AiReportView } from '@vben/ai-chat-ui/src/report/AiReportView.vue';
import {
  parseReportData,
  parseReportSpec,
} from '@vben/ai-chat-ui/src/report/reportSpec';

const spec = parseReportSpec(JSON.parse(version.specJson)); // 校验失败即抛错
const data = parseReportData(JSON.parse(version.dataJson));
</script>

<template>
  <AiReportView
    :spec="spec"
    :data="data"
    theme="dark"
    failure-reason="上次刷新失败：来源已停用"
  />
</template>
```

## 行为约定（与验收对应）

- **四类块**（AT-043/044）：metric 取绑定数据的真实取值（缺失显示 `—`，不补 0）；text 只做文本插值；table 只渲染声明的列（结果里多出来的列不进界面，空结果显示"没有数据行"）；chart 走 `AiChart` （`column` → `bar`），图表契约无法表达缺失值时降级为点数据表格并说明原因。
- **来源/截至时间/完整性/失败状态**（R07 第 2 步）：元信息条展示完整性结论（PARTIAL 明确写"不能当作完整统计"）、逐项来源（kind + 资源 + 版本 + 描述）与失败原因文本；截至时间由调用方按需以文本传入（不在组件里造时间）。
- **深浅色与窄屏**（AT-054）：`theme` 令牌驱动颜色，`AiChart` 收到同一主题名；栅格使用 `repeat(12, minmax(0, 1fr))` + `min-width: 0` + 表格 `overflow-x: auto`，窄容器不溢出。
- **恶意内容无执行**：全部内容文本插值（无 `v-html`/`innerHTML`）；规格与数据都过 zod 严格校验，未知块类型与未知键在渲染前就被拒绝（组件显示"已拒绝渲染"提示，不猜测结构）。
