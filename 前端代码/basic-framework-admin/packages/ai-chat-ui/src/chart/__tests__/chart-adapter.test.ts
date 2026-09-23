import type { ChartSpec } from '@vben/ai-contracts';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { describeFallback, fallbackReason, toTableModel } from '../fallback';
import { specToRows, toDisplayNumber } from '../specToRows';
import { formatLargeNumber, themeTokens, truncateLabel } from '../theme';

const chartCalls = vi.hoisted(() => ({
  changeSize: vi.fn(),
  destroy: vi.fn(),
  options: vi.fn(),
  render: vi.fn(),
}));

vi.mock('@antv/g2', () => ({
  Chart: class {
    public changeSize = chartCalls.changeSize;
    public destroy = chartCalls.destroy;
    public options = chartCalls.options;
    public render = chartCalls.render;
    public constructor(public config: Record<string, unknown>) {}
  },
}));

const { default: AiChart } = await import('../AiChart.vue');

function spec(overrides: Partial<ChartSpec> = {}): ChartSpec {
  return {
    categories: ['华东', '华南'],
    series: [{ data: ['740.00', '450.00'], name: '净额' }],
    title: '区域净额',
    type: 'bar',
    ...overrides,
  } as ChartSpec;
}

function exposed(wrapper: ReturnType<typeof mount>) {
  return wrapper.vm as unknown as { destroy(): void; resize(): void };
}

describe('ai chart adapter', () => {
  beforeEach(() => {
    Object.values(chartCalls).forEach((mock) => mock.mockClear());
  });

  it('把 ChartSpec 转成行数据：金额保留原始十进制文本，缺值不补 0', () => {
    const rows = specToRows(
      spec({ series: [{ data: ['740.00', ''], name: '净额' }] }),
    );

    expect(rows).toHaveLength(2);
    expect(rows[0]).toMatchObject({
      category: '华东',
      rawValue: '740.00',
      series: '净额',
      value: 740,
    });
    expect(rows[1]).toMatchObject({ rawValue: '', value: null });
    expect(toDisplayNumber('abc')).toBeNull();
    expect(toDisplayNumber(Number.POSITIVE_INFINITY)).toBeNull();
    expect(toDisplayNumber(0)).toBe(0);
  });

  it('渲染时把厂商选项限制在适配层：只含行数据与主题令牌', async () => {
    mount(AiChart, { props: { spec: spec(), theme: 'dark' } });
    await flushPromises();

    expect(chartCalls.render).toHaveBeenCalledTimes(1);
    const options = chartCalls.options.mock.calls[0]?.[0] as Record<
      string,
      unknown
    >;
    expect(options.type).toBe('interval');
    expect(Object.keys(options).toSorted()).toEqual(
      [
        'axis',
        'data',
        'encode',
        'legend',
        'scale',
        'style',
        'theme',
        'type',
      ].toSorted(),
    );
    expect(JSON.stringify(options)).not.toContain('vendor');
    expect((options.theme as Record<string, string>).background).toBe(
      themeTokens('dark').background,
    );
  });

  it('空/null/长标签/大数都不溢出：降级表格 + 截断 + 千分位', () => {
    // 空类目 → 降级
    expect(
      fallbackReason(
        spec({ categories: [], series: [{ data: [], name: 'x' }] }),
        [],
      ),
    ).toBe('EMPTY_CATEGORIES');
    // 全是缺失值 → 降级（不画成 0）
    expect(
      fallbackReason(
        spec({ series: [{ data: ['', ''], name: 'x' }] }),
        specToRows(spec({ series: [{ data: ['', ''], name: 'x' }] })),
      ),
    ).toBe('NO_NUMERIC_VALUE');
    // 饼图多系列 / 类目过多 → 降级
    expect(
      fallbackReason(
        spec({
          series: [
            { data: [1], name: 'a' },
            { data: [2], name: 'b' },
          ],
          type: 'pie',
        }),
        specToRows(
          spec({
            series: [
              { data: [1], name: 'a' },
              { data: [2], name: 'b' },
            ],
            type: 'pie',
          }),
        ),
      ),
    ).toBe('PIE_MULTIPLE_SERIES');

    expect(truncateLabel('很长的类目名称'.repeat(6))).toContain('…');
    expect(truncateLabel('华东')).toBe('华东');
    expect(formatLargeNumber(12_345_678_901.234)).toBe('12,345,678,901.23');
    expect(formatLargeNumber(null)).toBe('-');

    const table = toTableModel(
      spec({
        categories: ['很长的类目名称'.repeat(6), '华南'],
        series: [{ data: ['12345678901234.56', ''], name: '净额' }],
      }),
      specToRows(
        spec({
          categories: ['很长的类目名称'.repeat(6), '华南'],
          series: [{ data: ['12345678901234.56', ''], name: '净额' }],
        }),
      ),
    );
    expect(table.columns).toEqual(['类目', '净额']);
    expect(table.rows[0]?.[1]).toBe('12345678901234.56');
  });

  it('降级时渲染表格并说明原因，不加载厂商代码', () => {
    const wrapper = mount(AiChart, {
      props: { spec: spec({ categories: [], series: [] }) },
    });

    expect(wrapper.find('[data-testid="ai-chart-table"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="ai-chart-canvas"]').exists()).toBe(
      false,
    );
    expect(
      wrapper.find('[data-testid="ai-chart-fallback-reason"]').text(),
    ).toContain('表格');
    expect(chartCalls.render).not.toHaveBeenCalled();
  });

  it('降级原因覆盖全部类型且文案可读（含饼图类目过多与类目过多）', () => {
    const manyCategories = Array.from(
      { length: 70 },
      (_, index) => `类目${index}`,
    );
    const manyValues = Array.from({ length: 70 }, () => 1);
    expect(
      fallbackReason(
        spec({
          categories: manyCategories,
          series: [{ data: manyValues, name: 'x' }],
        }),
        specToRows(
          spec({
            categories: manyCategories,
            series: [{ data: manyValues, name: 'x' }],
          }),
        ),
      ),
    ).toBe('TOO_MANY_CATEGORIES');

    const pieCategories = Array.from(
      { length: 20 },
      (_, index) => `类目${index}`,
    );
    const pieValues = Array.from({ length: 20 }, () => 1);
    expect(
      fallbackReason(
        spec({
          categories: pieCategories,
          series: [{ data: pieValues, name: 'x' }],
          type: 'pie',
        }),
        specToRows(
          spec({
            categories: pieCategories,
            series: [{ data: pieValues, name: 'x' }],
            type: 'pie',
          }),
        ),
      ),
    ).toBe('PIE_TOO_MANY_CATEGORIES');

    for (const reason of [
      'EMPTY_CATEGORIES',
      'NO_NUMERIC_VALUE',
      'PIE_MULTIPLE_SERIES',
      'PIE_TOO_MANY_CATEGORIES',
      'TOO_MANY_CATEGORIES',
    ] as const) {
      expect(describeFallback(reason)).toContain('表格');
    }
    expect(describeFallback(null)).toBe('');
  });

  it('destroy 幂等且重复挂载不残留实例（AT-055）', async () => {
    const first = mount(AiChart, { props: { spec: spec() } });
    await flushPromises();
    exposed(first).destroy();
    exposed(first).destroy();
    expect(chartCalls.destroy.mock.calls.length).toBeGreaterThanOrEqual(1);

    first.unmount();
    const destroysAfterUnmount = chartCalls.destroy.mock.calls.length;

    const second = mount(AiChart, { props: { spec: spec() } });
    await flushPromises();
    exposed(second).resize();
    second.unmount();

    // 第二次挂载没有"凭空多出来的"存活实例：每次挂载都会在卸载时销毁
    expect(chartCalls.destroy.mock.calls.length).toBeGreaterThan(
      destroysAfterUnmount,
    );
    expect(chartCalls.changeSize).toHaveBeenCalled();
  });

  it('主题变化重新渲染，窄屏 resize 跟随容器', async () => {
    const wrapper = mount(AiChart, { props: { spec: spec(), theme: 'light' } });
    await flushPromises();
    chartCalls.options.mockClear();
    await wrapper.setProps({ theme: 'dark' });
    await flushPromises();
    expect(chartCalls.destroy).toHaveBeenCalled();
    expect(chartCalls.options).toHaveBeenCalled();
    const options = chartCalls.options.mock.calls.at(-1)?.[0] as Record<
      string,
      unknown
    >;
    expect((options.theme as Record<string, string>).background).toBe(
      themeTokens('dark').background,
    );
  });
});
