import type { ChartSpec } from '@vben/ai-contracts';

/**
 * 报表渲染契约（R07）：ReportSpec v1 与版本数据的**前端解析口径**。
 *
 * <p>为什么在前端再解析一次：后端（R01/R04/R05/R06）已经校验过，但浏览器拿到的是字符串，
 * 渲染器不能对"看起来像报表"的数据尽力而为——键白名单、取值域、块类型都在这里再判一次，
 * 未知块类型/未知键一律拒绝（渲染期拒绝，不给注入面）。
 *
 * <p>与后端权威 Schema（`docs/contracts/ai/report-spec.schema.json`）保持一致：
 * 本文件是它的**消费者**，不新增字段语义；块类型四类（metric/text/table/chart）与 12 列栅格同值。
 *
 * <p>校验器是**手写**的（不引入新依赖）：本包只依赖 vue 与 `@vben/ai-contracts`，
 * 与 chart 适配层同一取舍。
 */

const IDENTIFIER = /^[a-z][a-z0-9_]{0,63}$/;
const DECIMAL_TEXT = /^-?\d+(?:\.\d+)?$/;
const SCRIPT_MARKER =
  /<\s*script|<\/\s*script|javascript:|<\s*style|<\s*iframe/i;

const BLOCK_TYPES = new Set(['chart', 'metric', 'table', 'text']);
const CHART_TYPES = new Set(['column', 'line', 'pie']);
const DATA_TYPES = new Set([
  'BOOLEAN',
  'DATE',
  'DATETIME',
  'DECIMAL',
  'INTEGER',
  'STRING',
]);
const FORMATS = new Set(['CURRENCY', 'DECIMAL', 'NUMBER', 'PERCENT', 'TEXT']);
const COMPLETENESS = new Set(['COMPLETE', 'FAILED', 'PARTIAL']);
const SOURCE_KINDS = new Set(['API', 'DATASET', 'DOCUMENT']);

/** 结果列（数据集声明与版本数据共用）。 */
export interface ReportResultColumn {
  dataType: string;
  field: string;
  label: string;
  unit?: string;
}

/** 指标块。 */
export interface ReportMetricBlock {
  datasetRef: string;
  format: string;
  id: string;
  metricField: string;
  rowIndex: number;
  title: string;
  type: 'metric';
  unit?: string;
}

/** 文本块。 */
export interface ReportTextBlock {
  id: string;
  text: string;
  title: string;
  type: 'text';
}

/** 表格块。 */
export interface ReportTableBlock {
  columns: { field: string; format: string; label: string }[];
  datasetRef: string;
  id: string;
  pageSize: number;
  title: string;
  type: 'table';
}

/** 图表块。 */
export interface ReportChartBlock {
  chart: {
    categoryField: string;
    chartType: string;
    legend: boolean;
    seriesField?: string;
    valueField: string;
  };
  datasetRef: string;
  id: string;
  title: string;
  type: 'chart';
}

/** 报表块（判别联合：未知 type 直接拒绝）。 */
export type ReportBlock =
  | ReportChartBlock
  | ReportMetricBlock
  | ReportTableBlock
  | ReportTextBlock;

/** 布局项（12 列栅格）。 */
export interface ReportLayoutItem {
  blockId: string;
  column: number;
  row: number;
  span: number;
}

/** ReportSpec v1。 */
export interface ReportSpec {
  blocks: ReportBlock[];
  datasetRefs: {
    columns: ReportResultColumn[];
    completeness: string;
    id: string;
    queryRef: string;
    resultRef: string;
    rowCount: number;
  }[];
  layout: { columns: number; gap: number; items: ReportLayoutItem[] };
  queryRefs: { id: string; plan: Record<string, unknown> }[];
  schemaVersion: string;
  sources: {
    description: string;
    id: string;
    kind: string;
    queryRef?: string;
    resourceId: string;
    resourceVersion: number;
  }[];
  themeRef: { revision: number; themeId: string };
  title: string;
}

/** 版本数据（`data_json` 形状）。 */
export interface ReportData {
  data: {
    blockId: string;
    points?: Record<string, unknown>[];
    rows?: Record<string, unknown>[];
    type: string;
    value?: unknown;
    verified?: boolean;
  }[];
  datasets?: {
    columns: ReportResultColumn[];
    completeness: string;
    datasetRef: string;
    rows: Record<string, unknown>[];
  }[];
  kind: string;
  notes?: string[];
  sources?: {
    completeness: string;
    datasetRef: string;
    queryRef: string;
    resultRef: string;
    rowCount: number;
  }[];
  specJson?: string;
  title?: string;
}

function fail(message: string): never {
  throw new Error(`报表数据不合法：${message}`);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** 键白名单：未知键一律拒绝（与后端"未知键即拒绝"同一口径）。 */
function object(value: unknown, allowed: string[]): Record<string, unknown> {
  if (!isPlainObject(value)) {
    return fail('必须是对象');
  }
  const allowedSet = new Set(allowed);
  for (const key of Object.keys(value)) {
    if (!allowedSet.has(key)) {
      return fail(`未知字段 ${key}`);
    }
  }
  return value;
}

function text(value: unknown, maxLength: number, required = true): string {
  if (value === undefined || value === null) {
    if (required) {
      return fail('缺少必填文本');
    }
    return '';
  }
  if (typeof value !== 'string') {
    return fail('必须是文本');
  }
  if (value.length > maxLength || (required && value.length === 0)) {
    return fail('文本长度不合法');
  }
  return value;
}

function identifier(value: unknown): string {
  if (typeof value !== 'string' || !IDENTIFIER.test(value)) {
    return fail('逻辑标识不合法');
  }
  return value;
}

function integer(value: unknown, min: number, max: number): number {
  if (
    typeof value !== 'number' ||
    !Number.isInteger(value) ||
    value < min ||
    value > max
  ) {
    return fail('整数越界');
  }
  return value;
}

function enumeration(value: unknown, allowed: Set<string>): string {
  if (typeof value !== 'string' || !allowed.has(value)) {
    return fail('取值不在允许域内');
  }
  return value;
}

function array(value: unknown, min: number, max: number): unknown[] {
  if (!Array.isArray(value) || value.length < min || value.length > max) {
    return fail('数组长度不合法');
  }
  return value;
}

function rowList(value: unknown): Record<string, unknown>[] {
  if (value === undefined || value === null) {
    return [];
  }
  return array(value, 0, 10_000).map((item) =>
    isPlainObject(item) ? item : fail('结果行必须是对象'),
  );
}

function resultColumn(value: unknown): ReportResultColumn {
  const node = object(value, ['field', 'label', 'dataType', 'unit']);
  return {
    field: identifier(node.field),
    label: text(node.label, 100),
    dataType: enumeration(node.dataType, DATA_TYPES),
    ...(node.unit === undefined ? {} : { unit: text(node.unit, 32) }),
  };
}

function block(value: unknown): ReportBlock {
  const node = object(value, [
    'id',
    'title',
    'type',
    'text',
    'datasetRef',
    'metricField',
    'rowIndex',
    'format',
    'unit',
    'columns',
    'pageSize',
    'chart',
  ]);
  const type = text(node.type, 16);
  if (!BLOCK_TYPES.has(type)) {
    return fail('未知块类型');
  }
  const base = { id: identifier(node.id), title: text(node.title, 100) };
  if (type === 'text') {
    return { ...base, type: 'text', text: text(node.text, 5000, false) };
  }
  if (type === 'metric') {
    return {
      ...base,
      type: 'metric',
      datasetRef: identifier(node.datasetRef),
      metricField: identifier(node.metricField),
      rowIndex: integer(node.rowIndex, 0, 1_000_000),
      format: enumeration(node.format, FORMATS),
      ...(node.unit === undefined ? {} : { unit: text(node.unit, 32) }),
    };
  }
  if (type === 'table') {
    return {
      ...base,
      type: 'table',
      datasetRef: identifier(node.datasetRef),
      columns: array(node.columns, 1, 50).map((column) => {
        const item = object(column, ['field', 'label', 'format']);
        return {
          field: identifier(item.field),
          label: text(item.label, 100),
          format: enumeration(item.format, FORMATS),
        };
      }),
      pageSize: integer(node.pageSize, 1, 100),
    };
  }
  const chart = object(node.chart, [
    'chartType',
    'categoryField',
    'valueField',
    'seriesField',
    'legend',
  ]);
  if (typeof chart.legend !== 'boolean') {
    return fail('legend 必须是布尔');
  }
  return {
    ...base,
    type: 'chart',
    datasetRef: identifier(node.datasetRef),
    chart: {
      chartType: enumeration(chart.chartType, CHART_TYPES),
      categoryField: identifier(chart.categoryField),
      valueField: identifier(chart.valueField),
      ...(chart.seriesField === undefined
        ? {}
        : { seriesField: identifier(chart.seriesField) }),
      legend: chart.legend,
    },
  };
}

/** 解析 ReportSpec（不合法即抛错，调用方不得把未校验数据交给渲染器）。 */
export function parseReportSpec(input: unknown): ReportSpec {
  const root = object(input, [
    'schemaVersion',
    'title',
    'themeRef',
    'layout',
    'blocks',
    'datasetRefs',
    'queryRefs',
    'sources',
  ]);
  if (root.schemaVersion !== '1.0') {
    return fail('不支持的契约版本');
  }
  const theme = object(root.themeRef, ['themeId', 'revision']);
  const layout = object(root.layout, ['columns', 'gap', 'items']);
  if (layout.columns !== 12) {
    return fail('布局必须是 12 列栅格');
  }
  return {
    schemaVersion: '1.0',
    title: text(root.title, 200),
    themeRef: {
      themeId: text(theme.themeId, 40),
      revision: integer(theme.revision, 1, 1_000_000),
    },
    layout: {
      columns: 12,
      gap: integer(layout.gap, 0, 64),
      items: array(layout.items, 1, 40).map((item) => {
        const node = object(item, ['blockId', 'row', 'column', 'span']);
        return {
          blockId: identifier(node.blockId),
          row: integer(node.row, 0, 100),
          column: integer(node.column, 0, 11),
          span: integer(node.span, 1, 12),
        };
      }),
    },
    blocks: array(root.blocks, 1, 40).map((item) => block(item)),
    datasetRefs: array(root.datasetRefs, 1, 20).map((ref) => {
      const node = object(ref, [
        'id',
        'resultRef',
        'queryRef',
        'columns',
        'rowCount',
        'completeness',
      ]);
      return {
        id: identifier(node.id),
        resultRef: text(node.resultRef, 120),
        queryRef: identifier(node.queryRef),
        columns: array(node.columns, 1, 50).map((item) => resultColumn(item)),
        rowCount: integer(node.rowCount, 0, 100_000_000),
        completeness: enumeration(node.completeness, COMPLETENESS),
      };
    }),
    queryRefs: array(root.queryRefs, 1, 20).map((ref) => {
      const node = object(ref, ['id', 'plan']);
      if (!isPlainObject(node.plan)) {
        return fail('查询计划必须是对象');
      }
      return { id: identifier(node.id), plan: node.plan };
    }),
    sources: array(root.sources, 1, 40).map((source) => {
      const node = object(source, [
        'id',
        'kind',
        'resourceId',
        'resourceVersion',
        'queryRef',
        'description',
      ]);
      return {
        id: identifier(node.id),
        kind: enumeration(node.kind, SOURCE_KINDS),
        resourceId: text(node.resourceId, 40),
        resourceVersion: integer(node.resourceVersion, 1, 1_000_000),
        ...(node.queryRef === undefined
          ? {}
          : { queryRef: identifier(node.queryRef) }),
        description: text(node.description, 500),
      };
    }),
  };
}

/** 解析版本数据（不合法即抛错）。 */
export function parseReportData(input: unknown): ReportData {
  const root = object(input, [
    'kind',
    'title',
    'specJson',
    'datasets',
    'data',
    'sources',
    'notes',
  ]);
  if (root.kind !== 'REPORT') {
    return fail('未知数据块类型');
  }
  const parsed: ReportData = {
    kind: 'REPORT',
    data: array(root.data, 0, 40).map((item) => {
      const node = object(item, [
        'blockId',
        'type',
        'verified',
        'value',
        'rows',
        'points',
      ]);
      return {
        blockId: identifier(node.blockId),
        type: text(node.type, 16),
        ...(node.verified === undefined
          ? {}
          : { verified: node.verified === true }),
        ...(node.value === undefined ? {} : { value: node.value }),
        ...(node.rows === undefined ? {} : { rows: rowList(node.rows) }),
        ...(node.points === undefined ? {} : { points: rowList(node.points) }),
      };
    }),
  };
  if (root.title !== undefined) {
    parsed.title = text(root.title, 200, false);
  }
  if (root.specJson !== undefined) {
    parsed.specJson = text(root.specJson, 262_144, false);
  }
  if (root.datasets !== undefined) {
    parsed.datasets = array(root.datasets, 0, 20).map((dataset) => {
      const node = object(dataset, [
        'datasetRef',
        'columns',
        'rows',
        'completeness',
      ]);
      return {
        datasetRef: identifier(node.datasetRef),
        columns: array(node.columns, 1, 50).map((item) => resultColumn(item)),
        rows: rowList(node.rows),
        completeness: enumeration(node.completeness, COMPLETENESS),
      };
    });
  }
  if (root.sources !== undefined) {
    parsed.sources = array(root.sources, 0, 40).map((source) => {
      const node = object(source, [
        'datasetRef',
        'queryRef',
        'resultRef',
        'rowCount',
        'completeness',
      ]);
      return {
        datasetRef: identifier(node.datasetRef),
        queryRef: identifier(node.queryRef),
        resultRef: text(node.resultRef, 120, false),
        rowCount: integer(node.rowCount, 0, 100_000_000),
        completeness: text(node.completeness, 16, false),
      };
    });
  }
  if (root.notes !== undefined) {
    parsed.notes = array(root.notes, 0, 40).map((note) =>
      text(note, 1000, false),
    );
  }
  return parsed;
}

/** 安全解析：失败返回 null（用于"渲染前降级为提示"的场景）。 */
export function safeParseReportSpec(input: unknown): null | ReportSpec {
  try {
    return parseReportSpec(input);
  } catch {
    return null;
  }
}

/** 安全解析版本数据：失败返回 null。 */
export function safeParseReportData(input: unknown): null | ReportData {
  try {
    return parseReportData(input);
  } catch {
    return null;
  }
}

/** 安全解析 JSON 文本（非法 JSON 返回 null，不抛给界面）。 */
export function parseJsonText(raw: null | string | undefined): unknown {
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    return null;
  }
}

/** 脚本片段检测（渲染前拦截；后端已拒绝，这里是前端第二道防线）。 */
export function containsScriptMarker(value: unknown): boolean {
  if (typeof value === 'string') {
    return SCRIPT_MARKER.test(value);
  }
  if (Array.isArray(value)) {
    return value.some((item) => containsScriptMarker(item));
  }
  if (isPlainObject(value)) {
    return Object.values(value).some((item) => containsScriptMarker(item));
  }
  return false;
}

/** 单元格取值 → 显示文本（只做文本转换，不执行任何内容）。 */
export function cellText(value: unknown): string {
  if (value === null || value === undefined) {
    return '';
  }
  if (typeof value === 'string') {
    return value;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  return JSON.stringify(value) ?? '';
}

/** 图表字段 → 类别文本（截断到 128，与图表契约上限一致）。 */
function categoryText(value: unknown): string {
  return cellText(value).slice(0, 128);
}

/** 图表数值：数字或十进制字符串才可用（其它类型一律不可用，不补 0）。 */
function chartValue(value: unknown): null | number | string {
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : null;
  }
  if (
    typeof value === 'string' &&
    DECIMAL_TEXT.test(value) &&
    value.length <= 64
  ) {
    return value;
  }
  return null;
}

/**
 * 图表块 → 类别与数值（厂商无关，供不引入图表库的宿主使用）。
 *
 * <p>与 {@link buildChartSpec} 同一口径：缺失值返回原因（不补 0），类别截断到 128。
 */
export function chartSeries(
  blockValue: Extract<ReportBlock, { type: 'chart' }>,
  points: Record<string, unknown>[] | undefined,
): {
  categories: string[];
  reason: null | string;
  values: (null | number | string)[];
} {
  const rows = points ?? [];
  if (rows.length === 0) {
    return { categories: [], reason: '没有可绘制的数据点', values: [] };
  }
  const categories = rows.map((row) =>
    categoryText(row[blockValue.chart.categoryField]),
  );
  const values = rows.map((row) =>
    chartValue(row[blockValue.chart.valueField]),
  );
  if (values.includes(null)) {
    return { categories, reason: '数值列存在缺失取值', values };
  }
  return { categories, reason: null, values };
}

/** 图表块 → ChartSpec（不可用时返回 null + 原因，由组件降级为表格）。 */
export function buildChartSpec(
  blockValue: Extract<ReportBlock, { type: 'chart' }>,
  points: Record<string, unknown>[] | undefined,
): { reason: null | string; spec: ChartSpec | null } {
  const rows = points ?? [];
  if (rows.length === 0) {
    return { reason: '没有可绘制的数据点', spec: null };
  }
  const categories = rows.map((row) =>
    categoryText(row[blockValue.chart.categoryField]),
  );
  const values = rows.map((row) =>
    chartValue(row[blockValue.chart.valueField]),
  );
  if (values.includes(null)) {
    // 图表契约无法表达缺失值（补 0 会把缺失说成业务上的 0）：降级为表格，不编造数字
    return { reason: '数值列存在缺失取值', spec: null };
  }
  const type =
    blockValue.chart.chartType === 'column'
      ? 'bar'
      : blockValue.chart.chartType;
  return {
    reason: null,
    spec: {
      type: type as ChartSpec['type'],
      title: blockValue.title,
      categories,
      series: [
        {
          name: blockValue.chart.valueField,
          data: values as (number | string)[],
        },
      ],
    },
  };
}

/** 数值显示：只做展示格式（金额保留十进制原始文本，不重新计算）。 */
export function formatMetric(
  value: unknown,
  format: string,
  unit?: string,
): string {
  const shown = cellText(value);
  if (shown === '') {
    return '—';
  }
  const suffix = unit ? ` ${unit}` : '';
  const numeric = Number(shown);
  if (!Number.isFinite(numeric) || format === 'TEXT') {
    // 非数字文本原样展示：不做"尽力解析"，避免把说明性文本显示成数字
    return `${shown}${suffix}`;
  }
  if (format === 'PERCENT') {
    return `${numeric}%${suffix}`;
  }
  return `${numeric.toLocaleString('zh-CN', { maximumFractionDigits: 2 })}${suffix}`;
}
