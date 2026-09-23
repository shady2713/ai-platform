<script setup lang="ts">
/**
 * 报表图表（页面版，R07）：用内联 SVG 画柱状/折线/饼图，**不引入图表库**。
 *
 * 为什么不复用 `@vben/ai-chat-ui` 的 AiChart：web-ele 的产物要过"生产 JS 无未定义全局"门禁，
 * 而 antv 的 canvas 渲染器里存在对全局 `ImagePool` 的引用（上游写法），把 antv 打进 web-ele 会拉红门禁。
 * 因此页面用这份**零依赖**的 SVG 渲染器画图，共享包里的 AiChart 仍服务于嵌入端宿主（apps/ai-chat）。
 *
 * 口径与共享组件一致：类别/数值来自结果点，缺失取值不补 0（降级为表格，由父组件处理）。
 */
import { computed } from 'vue';

const props = withDefaults(
  defineProps<{
    /** 类别文本（已截断） */
    categories: string[];
    /** 图表类型：column（柱状）/ line（折线）/ pie（饼图） */
    chartType: string;
    /** 主题：light / dark */
    theme?: string;
    /** 数值（数字或十进制字符串；缺失值由父组件在渲染前拦截） */
    values: (number | string)[];
  }>(),
  { theme: 'light' },
);

const WIDTH = 320;
const HEIGHT = 160;
const PADDING = 16;

const palette = computed(() =>
  props.theme === 'dark'
    ? ['#60a5fa', '#4ade80', '#fbbf24', '#f87171', '#a78bfa']
    : ['#2563eb', '#16a34a', '#f59e0b', '#dc2626', '#7c3aed'],
);

/** 数值化（仅用于画图；原始值文本不受影响） */
const numeric = computed(() =>
  props.values.map((value) => {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }),
);

const max = computed(() =>
  Math.max(1, ...numeric.value.map((value) => Math.abs(value))),
);

/** 柱状/折线：类目在绘图区的中心 x */
function slotCenter(index: number): number {
  const slot = (WIDTH - PADDING * 2) / Math.max(1, props.categories.length);
  return PADDING + slot * (index + 0.5);
}

function barHeight(value: number): number {
  return ((HEIGHT - PADDING * 2) * Math.abs(value)) / max.value;
}

/** 折线路径（按类目顺序连线） */
const linePath = computed(() =>
  numeric.value
    .map((value, index) => {
      const x = slotCenter(index);
      const y = HEIGHT - PADDING - barHeight(value);
      return `${index === 0 ? 'M' : 'L'}${x},${y}`;
    })
    .join(' '),
);

/** 饼图扇区（按占比切分；总和为 0 时退化为整圆提示） */
const pieSectors = computed(() => {
  const total = numeric.value.reduce((sum, value) => sum + Math.abs(value), 0);
  if (total <= 0) {
    return [];
  }
  const radius = Math.min(WIDTH, HEIGHT) / 2 - PADDING;
  const cx = WIDTH / 2;
  const cy = HEIGHT / 2;
  let angle = -Math.PI / 2;
  return numeric.value.map((value, index) => {
    const sweep = (Math.abs(value) / total) * Math.PI * 2;
    const start = angle;
    const end = angle + sweep;
    angle = end;
    const x1 = cx + radius * Math.cos(start);
    const y1 = cy + radius * Math.sin(start);
    const x2 = cx + radius * Math.cos(end);
    const y2 = cy + radius * Math.sin(end);
    const largeArc = sweep > Math.PI ? 1 : 0;
    return {
      color: palette.value[index % palette.value.length] ?? '#2563eb',
      path: `M${cx},${cy} L${x1},${y1} A${radius},${radius} 0 ${largeArc} 1 ${x2},${y2} Z`,
    };
  });
});
</script>

<template>
  <figure class="report-chart" data-testid="report-chart-figure">
    <svg
      :height="HEIGHT"
      :viewBox="`0 0 ${WIDTH} ${HEIGHT}`"
      :width="WIDTH"
      data-testid="report-chart-svg"
      preserveAspectRatio="xMidYMid meet"
      role="img"
    >
      <template v-if="chartType === 'pie'">
        <path
          v-for="(sector, index) in pieSectors"
          :key="index"
          :d="sector.path"
          :fill="sector.color"
        />
      </template>
      <template v-else>
        <template v-if="chartType === 'column'">
          <rect
            v-for="(value, index) in numeric"
            :key="index"
            :fill="palette[index % palette.length]"
            :height="barHeight(value)"
            :width="
              Math.max(
                6,
                (WIDTH - PADDING * 2) / Math.max(1, categories.length) - 8,
              )
            "
            :x="
              slotCenter(index) -
              Math.max(
                6,
                (WIDTH - PADDING * 2) / Math.max(1, categories.length) - 8,
              ) /
                2
            "
            :y="HEIGHT - PADDING - barHeight(value)"
          />
        </template>
        <path
          v-else
          :d="linePath"
          fill="none"
          stroke="#2563eb"
          stroke-width="2"
        />
      </template>
    </svg>
    <figcaption class="report-chart__legend" data-testid="report-chart-legend">
      <span
        v-for="(category, index) in categories"
        :key="index"
        class="report-chart__legend-item"
      >
        {{ category }}：{{ values[index] }}
      </span>
    </figcaption>
  </figure>
</template>

<style scoped>
.report-chart {
  max-width: 100%;
  margin: 0;
  overflow-x: auto;
}

.report-chart__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin-top: 4px;
  font-size: 12px;
  color: inherit;
}
</style>
