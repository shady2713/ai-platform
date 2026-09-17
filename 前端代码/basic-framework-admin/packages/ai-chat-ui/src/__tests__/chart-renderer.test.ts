import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

const chartCalls = {
  changeSize: vi.fn(),
  destroy: vi.fn(),
  options: vi.fn(),
  render: vi.fn(),
};

vi.mock('@antv/g2', () => ({
  Chart: class {
    public changeSize = chartCalls.changeSize;
    public destroy = chartCalls.destroy;
    public options = chartCalls.options;
    public render = chartCalls.render;
    public constructor(public config: Record<string, unknown>) {}
  },
}));

const { default: ChartRenderer } =
  await import('../components/ChartRenderer.vue');

const baseSpec = {
  type: 'bar' as const,
  title: '月度销售额',
  categories: ['一月', '二月'],
  series: [{ name: '销售额', data: [120, 200] }],
};

function exposed(wrapper: ReturnType<typeof mount>) {
  return wrapper.vm as unknown as { destroy(): void; resize(): void };
}

describe('chartRenderer', () => {
  beforeEach(() => {
    Object.values(chartCalls).forEach((mock) => mock.mockClear());
  });

  it('挂载时用 ChartSpec 渲染图表且不接触厂商类型以外的入参', () => {
    const wrapper = mount(ChartRenderer, { props: { spec: baseSpec } });

    expect(chartCalls.render).toHaveBeenCalledTimes(1);
    expect(chartCalls.options).toHaveBeenCalledTimes(1);
    const options = chartCalls.options.mock.calls[0]?.[0] as Record<
      string,
      unknown
    >;
    expect(options.type).toBe('interval');
    // 2 个类目 × 1 个系列 = 2 行数据
    expect(options.data).toHaveLength(2);
    expect(wrapper.text()).toContain('月度销售额');
  });

  it('标题与类目按文本渲染：恶意标题不产生元素、不执行脚本', () => {
    const wrapper = mount(ChartRenderer, {
      props: {
        spec: {
          ...baseSpec,
          title: '<img src=x onerror="globalThis.__xss = 1">',
          categories: ['<script>alert(1)</script>'],
        },
      },
    });

    expect(wrapper.find('img').exists()).toBe(false);
    expect(wrapper.find('script').exists()).toBe(false);
    expect(wrapper.text()).toContain('<img src=x onerror=');
    expect((globalThis as Record<string, unknown>).__xss).toBeUndefined();
  });

  it('卸载时销毁实例，避免 canvas 与监听器泄漏', () => {
    const wrapper = mount(ChartRenderer, { props: { spec: baseSpec } });
    wrapper.unmount();

    expect(chartCalls.destroy).toHaveBeenCalledTimes(1);
  });

  it('规格变更时先销毁旧实例再渲染新实例', () => {
    const wrapper = mount(ChartRenderer, { props: { spec: baseSpec } });
    return wrapper
      .setProps({ spec: { ...baseSpec, type: 'line' as const } })
      .then(() => {
        expect(chartCalls.destroy).toHaveBeenCalledTimes(1);
        expect(chartCalls.render).toHaveBeenCalledTimes(2);
        const options = chartCalls.options.mock.calls[1]?.[0] as Record<
          string,
          unknown
        >;
        expect(options.type).toBe('line');
      });
  });

  it('resize 使用容器尺寸调用 changeSize', () => {
    const wrapper = mount(ChartRenderer, { props: { spec: baseSpec } });
    const canvas = wrapper.get('[data-testid="ai-chart-canvas"]')
      .element as HTMLElement;

    exposed(wrapper).resize();

    expect(chartCalls.changeSize).toHaveBeenCalledWith(
      canvas.clientWidth,
      canvas.clientHeight,
    );
  });
});
