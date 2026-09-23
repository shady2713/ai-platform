<script setup lang="ts">
/**
 * AiReportView（R07）：ReportSpec + 版本数据 → 报表预览。
 *
 * 边界与安全语义：
 *  - 只渲染**已解析**的规格与数据（调用方用 `parseReportSpec` / `parseReportData`，或在组件内用
 *    安全解析；未通过校验的数据不渲染，直接给出提示）；
 *  - 文本/标题/单元格一律文本插值（不用 `v-html`/`innerHTML`），恶意内容只能是文本；
 *  - 指标值取绑定数据里的真实取值（不重算、不补 0）；图表不可用时降级为点数据表格并说明原因；
 *  - 栅格用 CSS grid（12 列）+ `min-width: 0`/`overflow: auto`：窄容器不溢出（AT-054 口径）；
 *  - 深浅色由 `theme` 令牌驱动（与 AiChart 同一套令牌语义）。
 */
import type { ChartSpec } from '@vben/ai-contracts';

import type { ReportData, ReportSpec } from './reportSpec';

import { computed } from 'vue';

import AiChart from '../chart/AiChart.vue';
import {
  buildChartSpec,
  cellText,
  formatMetric,
  parseJsonText,
  safeParseReportData,
  safeParseReportSpec,
} from './reportSpec';

const props = withDefaults(
  defineProps<{
    /** 版本数据（`data_json` 文本或已解析对象） */
    data?: null | ReportData | string;
    /** 失败原因（刷新/生成失败时由调用方给出，界面只展示文本） */
    failureReason?: string;
    /** 报表规格（`spec_json` 文本或已解析对象） */
    spec: ReportSpec | string;
    /** 主题：light / dark（未知值回退浅色） */
    theme?: string;
  }>(),
  { data: null, failureReason: '', theme: 'light' },
);

/** 规格解析：不合法即拒绝渲染（不做"尽力而为"）。 */
const parsedSpec = computed<null | ReportSpec>(() =>
  typeof props.spec === 'string'
    ? safeParseReportSpec(parseJsonText(props.spec))
    : safeParseReportSpec(props.spec),
);

/** 数据解析：缺失或非法时按"无数据"处理（界面给出说明，不显示半成品）。 */
const parsedData = computed<null | ReportData>(() => {
  if (!props.data) {
    return null;
  }
  return typeof props.data === 'string'
    ? safeParseReportData(parseJsonText(props.data))
    : safeParseReportData(props.data);
});

/** 块数据索引（按 blockId）。 */
const blockData = computed(() => {
  const map = new Map<string, ReportData['data'][number]>();
  for (const item of parsedData.value?.data ?? []) {
    map.set(item.blockId, item);
  }
  return map;
});

/** 结果行索引（按 datasetRef）。 */
const datasetRows = computed(() => {
  const map = new Map<string, Record<string, unknown>[]>();
  for (const item of parsedData.value?.datasets ?? []) {
    map.set(item.datasetRef, item.rows);
  }
  return map;
});

/** 布局顺序（行 → 列），块按它在 12 列栅格里的位置渲染。 */
const laidOutBlocks = computed(() => {
  const spec = parsedSpec.value;
  if (!spec) {
    return [];
  }
  const blocks = new Map(spec.blocks.map((block) => [block.id, block]));
  return [...spec.layout.items]
    .toSorted(
      (left, right) => left.row - right.row || left.column - right.column,
    )
    .flatMap((item) => {
      const block = blocks.get(item.blockId);
      return block ? [{ block, item }] : [];
    });
});

function gridStyle(column: number, span: number): Record<string, string> {
  return {
    'grid-column': `${column + 1} / span ${span}`,
    'min-width': '0',
  };
}

/** 指标块：取值来自绑定数据（缺失显示占位符，不补 0）。 */
function metricText(
  blockId: string,
  field: string,
  format: string,
  unit?: string,
): string {
  const rows = blockData.value.get(blockId)?.rows ?? [];
  const value = blockData.value.get(blockId)?.value;
  if (value !== undefined && value !== null) {
    return formatMetric(value, format, unit);
  }
  const first = rows[0];
  return formatMetric(first?.[field], format, unit);
}

/** 表格块：只渲染声明的列（结果里多出来的列不进入界面）。 */
function tableRows(blockId: string): Record<string, unknown>[] {
  return blockData.value.get(blockId)?.rows ?? [];
}

/** 图表块：ChartSpec 或降级原因 + 点数据表格。 */
function chartView(
  block: Extract<ReportSpec['blocks'][number], { type: 'chart' }>,
) {
  const points =
    blockData.value.get(block.id)?.points ??
    datasetRows.value.get(block.datasetRef);
  const built: { reason: null | string; spec: ChartSpec | null } =
    buildChartSpec(block, points);
  return { ...built, points: points ?? [] };
}

const completeness = computed(
  () => parsedData.value?.datasets?.[0]?.completeness ?? 'COMPLETE',
);

const completenessText = computed(() => {
  switch (completeness.value) {
    case 'FAILED': {
      return '数据不可用（上游失败）';
    }
    case 'PARTIAL': {
      return '部分数据（被行数上限截断，不能当作完整统计）';
    }
    default: {
      return '完整数据';
    }
  }
});

/** 来源说明：来源标识 + 版本 + 描述（只展示，不构造链接）。 */
const sourceTexts = computed(() =>
  (parsedSpec.value?.sources ?? []).map(
    (source) =>
      `${source.kind} ${source.resourceId}@v${source.resourceVersion}：${source.description}`,
  ),
);

const notes = computed(() => parsedData.value?.notes ?? []);
</script>

<template>
  <section
    class="ai-report"
    :class="{ 'ai-report--dark': theme === 'dark' }"
    data-testid="ai-report"
  >
    <template v-if="!parsedSpec">
      <p class="ai-report__empty" data-testid="ai-report-invalid">
        报表规格不合法，已拒绝渲染（未知块类型或未知字段不会进入界面）
      </p>
    </template>
    <template v-else>
      <header class="ai-report__header">
        <h3 class="ai-report__title" data-testid="ai-report-title">
          {{ parsedSpec.title }}
        </h3>
        <dl class="ai-report__meta" data-testid="ai-report-meta">
          <div class="ai-report__meta-item">
            <dt>完整性</dt>
            <dd data-testid="ai-report-completeness">{{ completenessText }}</dd>
          </div>
          <div class="ai-report__meta-item">
            <dt>数据来源</dt>
            <dd>
              <ul class="ai-report__sources">
                <li
                  v-for="source in sourceTexts"
                  :key="source"
                  data-testid="ai-report-source"
                >
                  {{ source }}
                </li>
              </ul>
            </dd>
          </div>
          <div v-if="failureReason" class="ai-report__meta-item">
            <dt>上次刷新/生成</dt>
            <dd class="ai-report__failure" data-testid="ai-report-failure">
              {{ failureReason }}
            </dd>
          </div>
        </dl>
      </header>

      <p
        v-if="notes.length > 0"
        class="ai-report__notes"
        data-testid="ai-report-notes"
      >
        {{ notes.join('；') }}
      </p>

      <div class="ai-report__grid" data-testid="ai-report-grid">
        <article
          v-for="entry in laidOutBlocks"
          :key="entry.block.id"
          class="ai-report__block"
          :style="gridStyle(entry.item.column, entry.item.span)"
          :data-block-id="entry.block.id"
          :data-block-type="entry.block.type"
        >
          <h4 class="ai-report__block-title">{{ entry.block.title }}</h4>

          <p
            v-if="entry.block.type === 'text'"
            class="ai-report__text"
            data-testid="ai-report-text"
          >
            {{ entry.block.text }}
          </p>

          <p
            v-else-if="entry.block.type === 'metric'"
            class="ai-report__metric"
            data-testid="ai-report-metric"
          >
            {{
              metricText(
                entry.block.id,
                entry.block.metricField,
                entry.block.format,
                entry.block.unit,
              )
            }}
          </p>

          <div
            v-else-if="entry.block.type === 'table'"
            class="ai-report__table-wrap"
            data-testid="ai-report-table-wrap"
          >
            <table class="ai-report__table" data-testid="ai-report-table">
              <thead>
                <tr>
                  <th v-for="column in entry.block.columns" :key="column.field">
                    {{ column.label }}
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(row, index) in tableRows(entry.block.id)"
                  :key="index"
                >
                  <td v-for="column in entry.block.columns" :key="column.field">
                    {{ cellText(row[column.field]) }}
                  </td>
                </tr>
                <tr v-if="tableRows(entry.block.id).length === 0">
                  <td
                    :colspan="entry.block.columns.length"
                    data-testid="ai-report-table-empty"
                  >
                    没有数据行
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-else class="ai-report__chart" data-testid="ai-report-chart">
            <AiChart
              v-if="chartView(entry.block).spec"
              :spec="chartView(entry.block).spec!"
              :theme="theme"
            />
            <template v-else>
              <p class="ai-report__hint" data-testid="ai-report-chart-fallback">
                图表不可用（{{ chartView(entry.block).reason }}），已改为表格
              </p>
              <div class="ai-report__table-wrap">
                <table
                  class="ai-report__table"
                  data-testid="ai-report-points-table"
                >
                  <thead>
                    <tr>
                      <th>{{ entry.block.chart.categoryField }}</th>
                      <th>{{ entry.block.chart.valueField }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="(point, index) in chartView(entry.block).points"
                      :key="index"
                    >
                      <td>
                        {{ cellText(point[entry.block.chart.categoryField]) }}
                      </td>
                      <td>
                        {{ cellText(point[entry.block.chart.valueField]) }}
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </template>
          </div>
        </article>
      </div>
    </template>
  </section>
</template>

<style scoped>
.ai-report {
  --ai-report-border: #e4e4e7;
  --ai-report-muted: #52525b;
  --ai-report-bg: #fff;
  --ai-report-text: #18181b;

  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  padding: 12px;
  color: var(--ai-report-text);
  background: var(--ai-report-bg);
}

.ai-report--dark {
  --ai-report-border: #3f3f46;
  --ai-report-muted: #a1a1aa;
  --ai-report-bg: #18181b;
  --ai-report-text: #f4f4f5;
}

.ai-report__header {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  align-items: baseline;
}

.ai-report__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.ai-report__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  margin: 0;
  font-size: 12px;
  color: var(--ai-report-muted);
}

.ai-report__meta-item {
  display: flex;
  gap: 4px;
}

.ai-report__meta-item dt {
  font-weight: 600;
}

.ai-report__meta-item dd {
  margin: 0;
}

.ai-report__sources {
  padding-left: 16px;
  margin: 0;
  list-style: disc;
}

.ai-report__failure {
  color: #dc2626;
}

.ai-report__notes {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--ai-report-muted);
}

.ai-report__grid {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 12px;
  margin-top: 12px;
}

.ai-report__block {
  box-sizing: border-box;
  min-width: 0;
  padding: 8px;
  border: 1px solid var(--ai-report-border);
  border-radius: 6px;
}

.ai-report__block-title {
  margin: 0 0 6px;
  font-size: 13px;
  font-weight: 600;
}

.ai-report__metric {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
}

.ai-report__text {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.ai-report__table-wrap {
  width: 100%;
  min-width: 0;
  overflow-x: auto;
}

.ai-report__table {
  width: 100%;
  font-size: 12px;
  border-collapse: collapse;
}

.ai-report__table th,
.ai-report__table td {
  padding: 4px 8px;
  text-align: left;
  overflow-wrap: anywhere;
  border-bottom: 1px solid var(--ai-report-border);
}

.ai-report__hint,
.ai-report__empty {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--ai-report-muted);
}
</style>
