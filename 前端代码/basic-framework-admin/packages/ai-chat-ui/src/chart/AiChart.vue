<script setup lang="ts">
/**
 * AiChart（R02）：ChartSpec → 图表 / 表格降级的唯一适配组件。
 *
 * 边界：
 *  - 对外只有 ChartSpec 与主题名；厂商（G2）类型不出现在 props/emits/暴露方法里；
 *  - 标题、类目、系列名一律文本插值（不用 v-html），恶意内容只能是文本（无脚本执行）；
 *  - 空数据、缺值、饼图多系列、类目过多 → 降级为表格并说明原因（不画"看起来像 0"的空图）；
 *  - 懒加载失败（离线/被裁剪）同样降级为表格，而不是白屏；
 *  - 卸载或规格/主题变化都销毁旧实例（destroy 幂等），宿主拿不到厂商实例。
 */
import type { ChartSpec } from '@vben/ai-contracts';

import { computed, onMounted, ref } from 'vue';

import { describeFallback, fallbackReason, toTableModel } from './fallback';
import { specToRows } from './specToRows';
import { formatLargeNumber } from './theme';
import { useChartInstance } from './useChartInstance';

const props = withDefaults(defineProps<{ spec: ChartSpec; theme?: string }>(), {
  theme: 'light',
});

const container = ref<HTMLDivElement>();
const rows = computed(() => specToRows(props.spec));
const reason = computed(() => fallbackReason(props.spec, rows.value));
const table = computed(() => toTableModel(props.spec, rows.value));

const instance = useChartInstance(
  () => container.value,
  () => props.spec,
  () => props.theme,
);

const showTable = computed(
  () => reason.value !== null || instance.failed.value,
);

defineExpose({ destroy: instance.destroy, resize: instance.resize });

// 首次渲染必须在挂载后：此时容器 ref 才存在（setup 阶段容器还没渲染）
onMounted(() => {
  void instance.render();
});
</script>

<template>
  <figure class="ai-chart">
    <figcaption v-if="spec.title" class="ai-chart__title">
      {{ spec.title }}
    </figcaption>
    <p v-if="instance.loading.value" class="ai-chart__hint">图表加载中…</p>
    <template v-if="showTable">
      <p class="ai-chart__hint" data-testid="ai-chart-fallback-reason">
        {{ describeFallback(reason) || '图表不可用，已改为表格' }}
      </p>
      <table class="ai-chart__table" data-testid="ai-chart-table">
        <thead>
          <tr>
            <th v-for="column in table.columns" :key="column">{{ column }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in table.rows" :key="index">
            <td v-for="(cell, cellIndex) in row" :key="cellIndex">
              {{
                cellIndex === 0 ? cell : formatLargeNumber(Number(cell) || null)
              }}
            </td>
          </tr>
        </tbody>
      </table>
    </template>
    <div
      v-else
      ref="container"
      class="ai-chart__canvas"
      data-testid="ai-chart-canvas"
    ></div>
  </figure>
</template>
