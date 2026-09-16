import type { ComputedRef } from 'vue';

import { mount } from '@vue/test-utils';
import { defineComponent, ref } from 'vue';

import { describe, expect, it } from 'vitest';

import {
  useForwardPriorityValues,
  usePriorityValue,
  usePriorityValues,
} from '../use-priority-value';

interface PriorityValues {
  count: number;
  label: string;
}

interface HarnessValues {
  attribute: ComputedRef<unknown>;
  forwarded: ComputedRef<PriorityValues>;
  grouped: {
    count: ComputedRef<number>;
    label: ComputedRef<string>;
  };
  single: ComputedRef<string>;
}

let harnessValues!: HarnessValues;

const Harness = defineComponent({
  inheritAttrs: false,
  props: {
    count: { default: 1, type: Number },
    label: { default: 'default', type: String },
  },
  setup(props) {
    const state = ref<PriorityValues>({ count: 2, label: 'state' });
    harnessValues = {
      attribute: usePriorityValue(
        'attributeLabel',
        { attributeLabel: 'prop' },
        ref({ attributeLabel: 'state' }),
      ),
      forwarded: useForwardPriorityValues(props, state),
      grouped: usePriorityValues(props, state),
      single: usePriorityValue('label', props, state),
    };
    return {};
  },
  template: '<div />',
});

describe('usePriorityValue', () => {
  it('未显式传入 prop 时使用 state，并由批量 API 保持相同语义', async () => {
    const wrapper = mount(Harness);

    expect(harnessValues.single.value).toBe('state');
    expect(harnessValues.grouped.label.value).toBe('state');
    expect(harnessValues.grouped.count.value).toBe(2);
    expect(harnessValues.forwarded.value).toEqual({ count: 2, label: 'state' });

    await wrapper.setProps({ count: 3, label: 'prop' });

    expect(harnessValues.single.value).toBe('prop');
    expect(harnessValues.grouped.count.value).toBe(3);
    expect(harnessValues.forwarded.value).toEqual({
      count: 3,
      label: 'prop',
    });
  });

  it('插槽和 attrs 按约定优先于 props 与 state', () => {
    const slot = () => 'slot';

    const wrapper = mount(Harness, {
      attrs: { attributeLabel: 'attribute' },
      slots: { attributeLabel: slot },
    });

    expect(harnessValues.attribute.value).toBe(
      wrapper.vm.$slots.attributeLabel,
    );
  });
});
