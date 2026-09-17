import { describe, expect, it } from 'vitest';

import { defaultTheme, parseResultBlocks, parseTheme } from '../index';

describe('result-block', () => {
  it('解析文本/图表/错误三种结果块', () => {
    const blocks = parseResultBlocks([
      { kind: 'text', text: '你好' },
      {
        kind: 'chart',
        spec: {
          type: 'line',
          categories: ['a'],
          series: [{ name: 's', data: [1] }],
        },
      },
      { kind: 'error', message: '上游超时' },
    ]);

    expect(blocks).toHaveLength(3);
    expect(blocks.map((block) => block.kind)).toEqual([
      'text',
      'chart',
      'error',
    ]);
  });

  it('拒绝未知 kind 与超长文本', () => {
    expect(() =>
      parseResultBlocks([{ kind: 'html', text: '<b>x</b>' }]),
    ).toThrow();
    expect(() =>
      parseResultBlocks([{ kind: 'text', text: 'x'.repeat(20_001) }]),
    ).toThrow();
  });

  it('拒绝非数组输入', () => {
    expect(() => parseResultBlocks({ kind: 'text', text: 'x' })).toThrow();
  });
});

describe('theme', () => {
  it('缺省回落默认主题', () => {
    expect(parseTheme({})).toEqual(defaultTheme);
  });

  it('接受覆盖字段', () => {
    const theme = parseTheme({ primaryColor: '#ff0000', radius: 12 });
    expect(theme.primaryColor).toBe('#ff0000');
    expect(theme.radius).toBe(12);
    expect(theme.fontFamily).toBe(defaultTheme.fontFamily);
  });

  it('拒绝非法的颜色与越界圆角', () => {
    expect(() => parseTheme({ primaryColor: 'red' })).toThrow();
    expect(() => parseTheme({ primaryColor: 'javascript:alert(1)' })).toThrow();
    expect(() => parseTheme({ radius: 99 })).toThrow();
  });
});
