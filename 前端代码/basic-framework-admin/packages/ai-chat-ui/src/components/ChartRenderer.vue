<script setup lang="ts">
import type { ChartSpec } from '@vben/ai-contracts';

import { onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { Chart } from '@antv/g2';

/**
 * ChartRenderer：自有 ChartSpec → 图表实例的唯一适配点。
 *
 * - 厂商（G2）类型只出现在本组件内，不进入对外 props/emits 与长期存储协议；
 * - 标题与类目一律使用文本插值渲染，不使用 v-html，恶意标题只能是文本；
 * - 组件卸载与规格变更都必须销毁旧实例，避免 canvas 与监听器泄漏；
 * - 对外只暴露 resize/destroy，宿主不接触厂商实例。
 */
const props = defineProps<{ spec: ChartSpec }>();

const container = ref<HTMLDivElement>();
let chart: Chart | undefined;

function toRows(spec: ChartSpec): Array<Record<string, number | string>> {
  const rows: Array<Record<string, number | string>> = [];
  spec.series.forEach((series) => {
    series.data.forEach((value, index) => {
      // 协议中金额以十进制字符串保存（精度无损）；渲染层只做显示用数值转换，不回写存储
      const displayValue = typeof value === 'number' ? value : Number(value);
      rows.push({
        category: spec.categories[index] ?? '',
        series: series.name,
        value: displayValue,
      });
    });
  });
  return rows;
}

function toG2Options(spec: ChartSpec) {
  const rows = toRows(spec);
  if (spec.type === 'pie') {
    return {
      type: 'interval',
      coordinate: { type: 'theta' },
      data: rows.filter((row) => row.series === spec.series[0]?.name),
      encode: { y: 'value', color: 'category' },
      legend: {},
    };
  }
  return {
    type: spec.type === 'line' ? 'line' : 'interval',
    data: rows,
    encode: { x: 'category', y: 'value', color: 'series' },
    legend: {},
  };
}

function render(): void {
  const element = container.value;
  if (!element) {
    return;
  }
  chart = new Chart({ container: element, autoFit: true });
  chart.options(toG2Options(props.spec));
  chart.render();
}

function destroy(): void {
  chart?.destroy();
  chart = undefined;
}

function resize(): void {
  const element = container.value;
  if (!chart || !element) {
    return;
  }
  chart.changeSize(element.clientWidth, element.clientHeight);
}

onMounted(render);
onBeforeUnmount(destroy);
watch(
  () => props.spec,
  () => {
    destroy();
    render();
  },
  { deep: true },
);

defineExpose({ resize, destroy });
</script>

<template>
  <figure class="ai-chart-renderer">
    <figcaption v-if="spec.title" class="ai-chart-renderer__title">
      {{ spec.title }}
    </figcaption>
    <div
      ref="container"
      class="ai-chart-renderer__canvas"
      data-testid="ai-chart-canvas"
    ></div>
  </figure>
</template>
