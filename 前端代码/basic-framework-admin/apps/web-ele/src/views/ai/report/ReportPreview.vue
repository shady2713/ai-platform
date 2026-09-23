<script setup lang="ts">
/**
 * 报表预览（页面版，R07）：用 Element Plus + 内联 SVG 渲染 ReportSpec 的四类块与元信息。
 *
 * <p>与共享包 `@vben/ai-chat-ui` 的 `AiReportView` 同口径（解析、列白名单、缺失值不补 0、文本插值），
 * 但**不引入图表库**：web-ele 的产物要过"生产 JS 无未定义全局"门禁（antv canvas 渲染器引用了全局
 * `ImagePool`），因此页面用零依赖的 SVG 图表；嵌入端宿主仍用共享组件。
 */
import type {
  ReportData,
  ReportSpec,
} from '../../../../../../packages/ai-chat-ui/src/report/reportSpec';

import { computed } from 'vue';

import {
  cellText,
  chartSeries,
  formatMetric,
  parseJsonText,
  safeParseReportData,
  safeParseReportSpec,
} from '../../../../../../packages/ai-chat-ui/src/report/reportSpec';
import ReportChartSvg from './ReportChartSvg.vue';

const props = withDefaults(
  defineProps<{
    /** 版本数据（`data_json` 文本或已解析对象） */
    data?: null | ReportData | string;
    /** 失败原因文本（刷新失败时展示） */
    failureReason?: string;
    /** 报表规格（`spec_json` 文本或已解析对象） */
    spec: ReportSpec | string;
    /** 主题：light / dark */
    theme?: string;
  }>(),
  { data: null, failureReason: '', theme: 'light' },
);

const parsedSpec = computed<null | ReportSpec>(() =>
  typeof props.spec === 'string'
    ? safeParseReportSpec(parseJsonText(props.spec))
    : safeParseReportSpec(props.spec),
);

const parsedData = computed<null | ReportData>(() => {
  if (!props.data) {
    return null;
  }
  return typeof props.data === 'string'
    ? safeParseReportData(parseJsonText(props.data))
    : safeParseReportData(props.data);
});

const blockData = computed(() => {
  const map = new Map<string, ReportData['data'][number]>();
  for (const item of parsedData.value?.data ?? []) {
    map.set(item.blockId, item);
  }
  return map;
});

/** 布局顺序（行 → 列） */
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
  return { 'grid-column': `${column + 1} / span ${span}`, 'min-width': '0' };
}

function metricText(
  blockId: string,
  field: string,
  format: string,
  unit?: string,
): string {
  const node = blockData.value.get(blockId);
  if (node?.value !== undefined && node.value !== null) {
    return formatMetric(node.value, format, unit);
  }
  return formatMetric(node?.rows?.[0]?.[field], format, unit);
}

function tableRows(blockId: string): Record<string, unknown>[] {
  return blockData.value.get(blockId)?.rows ?? [];
}

function chartView(
  block: Extract<ReportSpec['blocks'][number], { type: 'chart' }>,
): {
  categories: string[];
  reason: null | string;
  values: (number | string)[];
} {
  const points = blockData.value.get(block.id)?.points;
  const series = chartSeries(block, points);
  // 缺失取值时 reason 非空、模板走降级分支；这里把可空收窄成渲染器需要的形状
  return {
    categories: series.categories,
    reason: series.reason,
    values: series.values.filter(
      (value): value is number | string => value !== null,
    ),
  };
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
    class="report-preview"
    :class="{ 'report-preview--dark': theme === 'dark' }"
    data-testid="report-preview"
  >
    <template v-if="!parsedSpec">
      <p class="report-preview__empty" data-testid="report-preview-invalid">
        报表规格不合法，已拒绝渲染（未知块类型或未知字段不会进入界面）
      </p>
    </template>
    <template v-else>
      <header class="report-preview__header">
        <h3 class="report-preview__title" data-testid="report-preview-title">
          {{ parsedSpec.title }}
        </h3>
        <dl class="report-preview__meta">
          <div class="report-preview__meta-item">
            <dt>完整性</dt>
            <dd data-testid="report-preview-completeness">
              {{ completenessText }}
            </dd>
          </div>
          <div class="report-preview__meta-item">
            <dt>数据来源</dt>
            <dd>
              <ul class="report-preview__sources">
                <li
                  v-for="source in sourceTexts"
                  :key="source"
                  data-testid="report-preview-source"
                >
                  {{ source }}
                </li>
              </ul>
            </dd>
          </div>
          <div v-if="failureReason" class="report-preview__meta-item">
            <dt>上次刷新/生成</dt>
            <dd
              class="report-preview__failure"
              data-testid="report-preview-failure"
            >
              {{ failureReason }}
            </dd>
          </div>
        </dl>
      </header>

      <p
        v-if="notes.length > 0"
        class="report-preview__notes"
        data-testid="report-preview-notes"
      >
        {{ notes.join('；') }}
      </p>

      <div class="report-preview__grid">
        <article
          v-for="entry in laidOutBlocks"
          :key="entry.block.id"
          class="report-preview__block"
          :data-block-id="entry.block.id"
          :data-block-type="entry.block.type"
          :style="gridStyle(entry.item.column, entry.item.span)"
        >
          <h4 class="report-preview__block-title">{{ entry.block.title }}</h4>

          <p
            v-if="entry.block.type === 'text'"
            class="report-preview__text"
            data-testid="report-preview-text"
          >
            {{ entry.block.text }}
          </p>

          <p
            v-else-if="entry.block.type === 'metric'"
            class="report-preview__metric"
            data-testid="report-preview-metric"
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
            class="report-preview__table-wrap"
            data-testid="report-preview-table-wrap"
          >
            <table
              class="report-preview__table"
              data-testid="report-preview-table"
            >
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
                    data-testid="report-preview-table-empty"
                  >
                    没有数据行
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-else data-testid="report-preview-chart">
            <ReportChartSvg
              v-if="chartView(entry.block).reason === null"
              :categories="chartView(entry.block).categories"
              :chart-type="entry.block.chart.chartType"
              :theme="theme"
              :values="chartView(entry.block).values"
            />
            <template v-else>
              <p
                class="report-preview__hint"
                data-testid="report-preview-chart-fallback"
              >
                图表不可用（{{ chartView(entry.block).reason }}），已改为表格
              </p>
              <div class="report-preview__table-wrap">
                <table
                  class="report-preview__table"
                  data-testid="report-preview-points-table"
                >
                  <thead>
                    <tr>
                      <th>{{ entry.block.chart.categoryField }}</th>
                      <th>{{ entry.block.chart.valueField }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="(point, index) in blockData.get(entry.block.id)
                        ?.points ?? []"
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
.report-preview {
  --report-preview-border: #e4e4e7;
  --report-preview-muted: #52525b;
  --report-preview-bg: #fff;
  --report-preview-text: #18181b;

  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  padding: 12px;
  color: var(--report-preview-text);
  background: var(--report-preview-bg);
}

.report-preview--dark {
  --report-preview-border: #3f3f46;
  --report-preview-muted: #a1a1aa;
  --report-preview-bg: #18181b;
  --report-preview-text: #f4f4f5;
}

.report-preview__header {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  align-items: baseline;
}

.report-preview__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.report-preview__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  margin: 0;
  font-size: 12px;
  color: var(--report-preview-muted);
}

.report-preview__meta-item {
  display: flex;
  gap: 4px;
}

.report-preview__meta-item dt {
  font-weight: 600;
}

.report-preview__meta-item dd {
  margin: 0;
}

.report-preview__sources {
  padding-left: 16px;
  margin: 0;
  list-style: disc;
}

.report-preview__failure {
  color: #dc2626;
}

.report-preview__notes {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--report-preview-muted);
}

.report-preview__grid {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 12px;
  margin-top: 12px;
}

.report-preview__block {
  box-sizing: border-box;
  min-width: 0;
  padding: 8px;
  border: 1px solid var(--report-preview-border);
  border-radius: 6px;
}

.report-preview__block-title {
  margin: 0 0 6px;
  font-size: 13px;
  font-weight: 600;
}

.report-preview__metric {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
}

.report-preview__text {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.report-preview__table-wrap {
  width: 100%;
  min-width: 0;
  overflow-x: auto;
}

.report-preview__table {
  width: 100%;
  font-size: 12px;
  border-collapse: collapse;
}

.report-preview__table th,
.report-preview__table td {
  padding: 4px 8px;
  text-align: left;
  overflow-wrap: anywhere;
  border-bottom: 1px solid var(--report-preview-border);
}

.report-preview__hint,
.report-preview__empty {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--report-preview-muted);
}
</style>
