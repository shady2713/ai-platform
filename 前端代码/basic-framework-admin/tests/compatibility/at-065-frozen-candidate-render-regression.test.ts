import type { ResultBlock } from '../../packages/ai-contracts/src/index';

/**
 * AT-065（Q08 前端切片）：冻结候选升级回归 —— 图表/消息/报表渲染 + 包体与结构。
 *
 * 冻结候选（本轮锁定的上游）：`@antv/g2` **5.4.8**（F04 锁定，升级必须重跑本用例）。
 *
 * 本用例做三件事：
 *  1. **候选锁定**：catalog 精确引脚、锁文件解析、实际安装版本三者一致（不许浮动版本）；
 *  2. **包体与结构无未解释回退**：Chat 生产产物里图表分包非空、由入口动态 import（懒加载）、
 *     首屏不预加载，且体积不超过冻结基线的一定比例——超出即失败，必须重新登记基线；
 *  3. **渲染回归（固定用例）**：冻结的 ChartSpec 样例 → 图表适配层；冻结的 ResultBlock 样例 → 消息层；
 *     v1 报表样例 → 报表层（四类块、完整性、来源、列白名单、主题）。
 *
 * 边界（诚实性）：happy-dom 没有 canvas，G2 会按适配层约定降级为表格；**真实 canvas 绘制**由
 * `tests/compatibility/at-065-browser-chart-render.pw.ts`（真实 Chromium + 同一份样例）断言，
 * 两者合起来才构成"渲染无回退"的结论。
 */
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import process from 'node:process';
import { gzipSync } from 'node:zlib';

import { mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import AiChart from '../../packages/ai-chat-ui/src/chart/AiChart.vue';
import MessageBlockView from '../../packages/ai-chat-ui/src/message/MessageBlockView.vue';
import AiReportView from '../../packages/ai-chat-ui/src/report/AiReportView.vue';
import {
  buildChartSpec,
  parseReportSpec,
} from '../../packages/ai-chat-ui/src/report/reportSpec';
import { parseResultBlocks } from '../../packages/ai-contracts/src/index';
import {
  FROZEN_CHART_SPEC,
  FROZEN_LINE_SPEC,
  REPORT_DATA_SAMPLE,
  REPORT_SPEC_SAMPLE,
} from './fixtures/at-065/render-samples';
import {
  frontendRoot,
  readJsonFile,
  readTextFile,
  repositoryRoot,
} from './support/workspace';

const FRONTEND_ROOT = frontendRoot();
const CHAT_DIST = join(FRONTEND_ROOT, 'apps/ai-chat/dist');

/** 图表库冻结版本（F04 锁定；升级要同时改 catalog/锁文件并重跑本用例）。 */
const FROZEN_ANTV_G2_VERSION = '5.4.8';

/**
 * 体积基线（字节，来自 F04 证据的实测：vendor-antv 1,309,384 / gzip 388,290；入口 76,638）。
 * 允许 +10% 的环境/依赖内噪声；超出即失败，必须由升级方重新登记基线并说明原因。
 */
const CHART_CHUNK_BASELINE_BYTES = 1_309_384;
const CHART_CHUNK_BASELINE_GZIP_BYTES = 388_290;
const ENTRY_CHUNK_BASELINE_BYTES = 76_638;
const SIZE_TOLERANCE = 1.1;

/** 图表诊断标记（压缩后仍保留；与 F04 的 verify-built-chat.mjs 同口径）。 */
const CHART_CHUNK_MARKERS = ['interval', 'theta', 'G2'];

interface ResultBlockFixture {
  [key: string]: unknown;
  kind: string;
}

/** 目录树里最新的 mtime（用于判断产物是否比源码旧）。 */
function newestMtime(directory: string): number {
  let newest = 0;
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory() && !entry.name.startsWith('.')) {
      newest = Math.max(newest, newestMtime(path));
    } else if (entry.isFile()) {
      newest = Math.max(newest, statSync(path).mtimeMs);
    }
  }
  return newest;
}

/**
 * 产物新鲜度：`dist/index.html` 缺失（冷检出，dist 被 gitignore），或 `apps/ai-chat/src` /
 * `packages/ai-chat-ui/src` 里有比产物更新的文件时，用包里既有的生产构建脚本重建一次。
 *
 * <p>与 Q06/Q07 的 globalSetup 同一口径：只跑既有构建脚本，不修改任何源码；这样本用例在
 * 冷检出下也能自证，不依赖调用方先执行构建（否则会把"没构建"误报成"兼容性回退"）。
 * 构建时把接口基址指向本地（与 Q06 浏览器夹具相同），避免两套夹具的产物互相污染。
 */
function ensureFreshChatDist(): void {
  const indexPath = join(CHAT_DIST, 'index.html');
  const newestSource = Math.max(
    newestMtime(join(FRONTEND_ROOT, 'apps/ai-chat/src')),
    newestMtime(join(FRONTEND_ROOT, 'packages/ai-chat-ui/src')),
  );
  const fresh =
    existsSync(indexPath) && newestSource <= statSync(indexPath).mtimeMs;
  if (fresh) {
    return;
  }
  execFileSync('pnpm', ['-F', '@vben/ai-chat', 'run', 'build'], {
    cwd: FRONTEND_ROOT,
    env: {
      ...process.env,
      VITE_AI_API_BASE_URL: 'http://127.0.0.1:48080/app-api/ai/v1',
    },
    stdio: 'pipe',
  });
}

describe('升级回归（AT-065）：冻结候选 AntV/G2 5.4.8 的渲染契约与包体', () => {
  it('冻结候选锁定：catalog 精确引脚、锁文件解析与安装版本三者一致', () => {
    const catalog = readTextFile(join(FRONTEND_ROOT, 'pnpm-workspace.yaml'));
    expect(catalog).toContain(`'@antv/g2': ${FROZEN_ANTV_G2_VERSION}`);

    const lockfile = readTextFile(join(FRONTEND_ROOT, 'pnpm-lock.yaml'));
    expect(lockfile).toContain(`'@antv/g2@${FROZEN_ANTV_G2_VERSION}':`);
    expect(lockfile).toMatch(
      new RegExp(
        String.raw`'@antv/g2@${FROZEN_ANTV_G2_VERSION}':\n\s+resolution: \{integrity: sha512-`,
        'u',
      ),
    );

    const installed = readJsonFile<{ name: string; version: string }>(
      join(
        FRONTEND_ROOT,
        'packages/ai-chat-ui/node_modules/@antv/g2/package.json',
      ),
    );
    expect(installed.name).toBe('@antv/g2');
    expect(installed.version).toBe(FROZEN_ANTV_G2_VERSION);
  });

  it(
    '包体与结构无未解释回退：图表分包非空、懒加载、首屏不预加载且在预算内',
    { timeout: 300_000 },
    () => {
      // 冷检出/源码更新时先重建产物（构建脚本来自包自身，不改源码）
      ensureFreshChatDist();
      const html = readTextFile(join(CHAT_DIST, 'index.html'));
      const entryMatch = html.match(/src="\/assets\/(index-[\w-]+\.js)"/u);
      if (!entryMatch?.[1]) {
        throw new Error('产物 index.html 未引用入口 chunk');
      }
      const entryName = entryMatch[1];
      const assets = readdirSync(join(CHAT_DIST, 'assets'));
      const chartChunks = assets.filter((name) =>
        /^vendor-antv-[\w-]+\.js$/u.test(name),
      );
      expect(chartChunks).toHaveLength(1);
      const chartChunk = chartChunks[0];
      if (!chartChunk) {
        throw new Error('缺少图表分包');
      }

      const chartContent = readFileSync(
        join(CHAT_DIST, 'assets', chartChunk),
        'utf8',
      );
      const chartSize = statSync(join(CHAT_DIST, 'assets', chartChunk)).size;
      expect(chartSize).toBeGreaterThan(1024);
      expect(
        chartSize,
        `图表分包从基线 ${CHART_CHUNK_BASELINE_BYTES} 字节回退到 ${chartSize} 字节（超出 ${SIZE_TOLERANCE * 100 - 100}% 容差）`,
      ).toBeLessThanOrEqual(
        Math.ceil(CHART_CHUNK_BASELINE_BYTES * SIZE_TOLERANCE),
      );
      const gzipSize = gzipSync(chartContent).length;
      expect(
        gzipSize,
        `图表分包 gzip 从基线 ${CHART_CHUNK_BASELINE_GZIP_BYTES} 字节回退到 ${gzipSize} 字节`,
      ).toBeLessThanOrEqual(
        Math.ceil(CHART_CHUNK_BASELINE_GZIP_BYTES * SIZE_TOLERANCE),
      );
      for (const marker of CHART_CHUNK_MARKERS) {
        expect(chartContent, `图表分包缺少标记 ${marker}`).toContain(marker);
      }

      // 懒加载：入口动态 import 图表分包，且首屏 HTML 不预加载它
      expect(html).not.toContain(chartChunk);
      const entryContent = readTextFile(join(CHAT_DIST, 'assets', entryName));
      expect(entryContent).toContain(`import("./${chartChunk}")`);
      expect(entryContent).not.toContain(`from"./${chartChunk}"`);
      const entrySize = statSync(join(CHAT_DIST, 'assets', entryName)).size;
      expect(
        entrySize,
        `入口 chunk 从基线 ${ENTRY_CHUNK_BASELINE_BYTES} 字节回退到 ${entrySize} 字节`,
      ).toBeLessThanOrEqual(
        Math.ceil(ENTRY_CHUNK_BASELINE_BYTES * SIZE_TOLERANCE),
      );
    },
  );

  it('图表渲染样例与 F07 冻结样例逐字段一致（回归夹具不是自造协议）', () => {
    const frozenBlock = readJsonFile<ResultBlockFixture>(
      join(
        repositoryRoot(),
        'docs/contracts/ai/samples/result-block.chart-money.valid.json',
      ),
    );
    expect(frozenBlock.kind).toBe('chart');
    expect(frozenBlock.spec).toStrictEqual(FROZEN_CHART_SPEC);
  });

  it('图表适配层：无 canvas 的 DOM 环境必须降级为表格并说明原因，不白屏、不补 0', async () => {
    const wrapper = mount(AiChart, {
      props: { spec: FROZEN_CHART_SPEC, theme: 'dark' },
    });
    // G2 是懒加载：等它在无 canvas 环境下失败并由适配层兜住（真实绘制见浏览器用例）
    await vi.waitFor(() => {
      expect(
        wrapper.find('[data-testid="ai-chart-fallback-reason"]').exists(),
      ).toBe(true);
    });
    expect(
      wrapper.get('[data-testid="ai-chart-fallback-reason"]').text(),
    ).toContain('已改为表格');
    expect(wrapper.find('[data-testid="ai-chart-canvas"]').exists()).toBe(
      false,
    );
    const table = wrapper.get('[data-testid="ai-chart-table"]').text();
    expect(table).toContain('一月');
    expect(table).toContain('二月');
    // 降级表格是**显示**路径（按显示数值格式化）；原始十进制文本的保真由上面的
    // ChartSpec 断言与报表表格断言覆盖，这里只验证"有数据、不是空图"
    expect(table).toMatch(/12,345,678,901,234\.56/u);
  });

  it('消息层：冻结 ResultBlock 样例仍按判别联合渲染（文本插值，无脚本节点）', () => {
    const textFixture = readJsonFile<ResultBlockFixture>(
      join(
        repositoryRoot(),
        'docs/contracts/ai/samples/result-block.text.valid.json',
      ),
    );
    const parsed: ResultBlock[] = parseResultBlocks([textFixture]);
    expect(parsed).toHaveLength(1);
    expect(parsed[0]?.kind).toBe('text');

    const textBlock = parsed[0];
    if (!textBlock || textBlock.kind !== 'text') {
      throw new Error('冻结样例未解析为文本块');
    }
    const wrapper = mount(MessageBlockView, { props: { block: textBlock } });
    expect(wrapper.text()).toContain('本月销售额环比增长 12%');
    expect(wrapper.html()).not.toContain('<script');

    // 冻结的图表块同样必须通过判别联合（渲染路径由上面两条覆盖）
    const chartFixture = readJsonFile<ResultBlockFixture>(
      join(
        repositoryRoot(),
        'docs/contracts/ai/samples/result-block.chart-money.valid.json',
      ),
    );
    const chartBlocks = parseResultBlocks([chartFixture]);
    expect(chartBlocks[0]?.kind).toBe('chart');
    // 未知 kind 仍拒绝（协议未被放宽）
    expect(() => parseResultBlocks([{ kind: 'unknown', text: 'x' }])).toThrow();
  });

  it('报表层：v1 样例四类块渲染不变（指标真实取值、表格列白名单、图表降级、主题与来源）', async () => {
    const spec = parseReportSpec(REPORT_SPEC_SAMPLE);
    expect(spec.title).toBe('2026年8月华东客户净销售额');

    // 图表块 → ChartSpec：column → bar，金额保留十进制文本（不做浮点改写）
    const chartBlock = spec.blocks.find((block) => block.id === 'sales_chart');
    if (!chartBlock || chartBlock.type !== 'chart') {
      throw new Error('样例缺少图表块');
    }
    const built = buildChartSpec(chartBlock, [
      { customer_name: 'alice', net_amount: '290.00' },
      { customer_name: 'bob', net_amount: '450.00' },
    ]);
    expect(built.reason).toBeNull();
    expect(built.spec?.type).toBe('bar');
    expect(built.spec?.series[0]?.data).toStrictEqual(['290.00', '450.00']);

    const wrapper = mount(AiReportView, {
      props: { data: REPORT_DATA_SAMPLE, spec, theme: 'dark' },
    });
    expect(wrapper.get('[data-testid="ai-report-title"]').text()).toBe(
      '2026年8月华东客户净销售额',
    );
    expect(wrapper.get('[data-testid="ai-report-metric"]').text()).toBe(
      '740 CNY',
    );
    expect(wrapper.get('[data-testid="ai-report-completeness"]').text()).toBe(
      '完整数据',
    );
    expect(wrapper.get('[data-testid="ai-report-source"]').text()).toContain(
      'sales_demo@v1',
    );
    const tableText = wrapper.get('[data-testid="ai-report-table"]').text();
    expect(tableText).toContain('alice');
    expect(tableText).toContain('290.00');
    expect(tableText).not.toContain('不可见列');
    expect(wrapper.get('[data-testid="ai-report"]').classes()).toContain(
      'ai-report--dark',
    );
    expect(wrapper.html()).not.toContain('<script');

    // 折线样例的降级判定同样不变（无 canvas 时表格 + 原因）
    const line = mount(AiChart, { props: { spec: FROZEN_LINE_SPEC } });
    await vi.waitFor(() => {
      expect(
        line.find('[data-testid="ai-chart-fallback-reason"]').exists(),
      ).toBe(true);
    });
    expect(
      line.get('[data-testid="ai-chart-fallback-reason"]').text(),
    ).toContain('已改为表格');
  });
});
