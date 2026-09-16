import { describe, expect, it, vi } from 'vitest';

import { DictTag } from './index';

vi.mock('@vben/hooks', () => ({
  getDictObj: vi.fn(() => []),
}));

vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    ElTag: defineComponent({
      name: 'ElTag',
      setup(_props, { slots }) {
        return () => h('span', slots.default?.());
      },
    }),
  };
});

describe('dict-tag index contract', () => {
  it('re-exports the DictTag component with its declared props', () => {
    expect(DictTag).toBeDefined();
    const declaredProps = (DictTag as { props?: object }).props ?? {};
    expect(Object.keys(declaredProps).toSorted()).toEqual([
      'icon',
      'type',
      'value',
    ]);
  });
});
