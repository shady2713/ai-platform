import { describe, expect, it } from 'vitest';

import { parseChartSpec, safeParseChartSpec } from '../index';

describe('chart-spec', () => {
  const validSpec = {
    type: 'bar' as const,
    title: '月度销售额',
    categories: ['一月', '二月'],
    series: [{ name: '销售额', data: [120, 200] }],
  };

  it('接受合法的 ChartSpec', () => {
    expect(parseChartSpec(validSpec)).toEqual(validSpec);
  });

  it('拒绝未支持图表类型', () => {
    expect(() => parseChartSpec({ ...validSpec, type: 'radar' })).toThrow();
  });

  it('拒绝系列长度与类目长度不一致', () => {
    expect(() =>
      parseChartSpec({
        ...validSpec,
        series: [{ name: 's', data: [1, 2, 3] }],
      }),
    ).toThrow('每个系列的 data 长度必须与 categories 长度一致');
  });

  it('拒绝非有限数值', () => {
    expect(() =>
      parseChartSpec({
        ...validSpec,
        series: [{ name: 's', data: [Number.NaN, 1] }],
      }),
    ).toThrow();
  });

  it('拒绝空类目与空系列', () => {
    expect(() => parseChartSpec({ ...validSpec, categories: [] })).toThrow();
    expect(() => parseChartSpec({ ...validSpec, series: [] })).toThrow();
  });

  it('安全解析失败返回 null 而不是抛错', () => {
    expect(safeParseChartSpec({ type: 'unknown' })).toBeNull();
    expect(safeParseChartSpec(validSpec)).toEqual(validSpec);
  });
});
