# 图表适配层（R02）

自有 `ChartSpec`（`@vben/ai-contracts`）→ 渲染的唯一适配点。厂商（`@antv/g2`）类型只出现在 `chartOptions.ts` 与 `useChartInstance.ts` 内，**不进入** props/emits、报表存储与模型输出。

## 模块

| 文件 | 职责 |
| --- | --- |
| `specToRows.ts` | ChartSpec → 行数据（厂商无关）：类目缺失补空、值缺失保留 `null`（不补 0）、金额保留十进制原始文本 |
| `fallback.ts` | 降级判定与表格模型：空类目、无数值、饼图多系列、类目过多 → 表格 + 可读原因 |
| `chartOptions.ts` | 行数据 + 主题令牌 → G2 选项（**唯一**的厂商格式出口） |
| `theme.ts` | 深浅色令牌、长标签截断、大数千分位 |
| `useChartInstance.ts` | 懒加载（动态 import）、ResizeObserver、主题变化重渲染、destroy 幂等 |
| `AiChart.vue` | 组件：`spec` + `theme` 入参；渲染图表或降级表格；只暴露 `resize`/`destroy` |

## 用法

```vue
<script setup lang="ts">
import type { ChartSpec } from '@vben/ai-contracts';
import { AiChart } from '@vben/ai-chat-ui/src/chart/AiChart.vue';

const spec: ChartSpec = {
  type: 'bar',
  title: '区域净额',
  categories: ['华东', '华南'],
  series: [{ name: '净额', data: ['740.00', '450.00'] }], // 金额用十进制字符串
};
</script>

<template>
  <AiChart :spec="spec" theme="dark" />
</template>
```

## 行为约定（与验收对应）

- **空/null/长标签/大数**（AT-044）：缺失值不补 0；长类目截断但保留全文（`fullCategory`）；表格与大数显示用千分位，原始金额文本不变。
- **深浅色与窄屏**（AT-054）：`theme` 令牌驱动颜色与字号；容器尺寸变化由 ResizeObserver 跟随。
- **无脚本执行**：标题/类目/系列名一律文本插值（不用 `v-html`）；`ChartSpec` 的 Schema 本身不含脚本字段。
- **destroy 后重复挂载无残留**（AT-055）：卸载、规格变化、主题变化都先销毁旧实例；懒加载期间卸载不会创建实例；`destroy()` 幂等。

## 未验证项

- **真实浏览器渲染样例**：本包提供组件与组件级测试；跨源、渲染与内存泄漏的浏览器验收（含"destroy 后无监听器/图表泄漏"的内存观察）由 Q06/G5 用真实浏览器补齐，本卡不声称已完成。
