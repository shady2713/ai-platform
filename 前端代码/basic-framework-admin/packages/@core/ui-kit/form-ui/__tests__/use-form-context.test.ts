import type { VbenFormProps } from '../src/types';

import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';

import { describe, expect, it } from 'vitest';
import { z } from 'zod';

import { useFormInitial } from '../src/use-form-context';

function mountFormInitial(props: VbenFormProps) {
  let initial!: ReturnType<typeof useFormInitial>;
  const Harness = defineComponent({
    setup() {
      initial = useFormInitial(props);
      return () => h('div');
    },
  });
  const wrapper = mount(Harness, {
    slots: {
      actions: () => h('button'),
      default: () => h('span'),
    },
  });
  return { initial, wrapper };
}

describe('useFormInitial', () => {
  it('builds initial values from explicit, primitive, nested and default rules', () => {
    const { initial, wrapper } = mountFormInitial({
      schema: [
        {
          component: 'VbenInput',
          defaultValue: 'preset',
          fieldName: 'profile.name',
        },
        {
          component: 'VbenInput',
          fieldName: 'title',
          rules: z.string(),
        },
        {
          component: 'VbenInput',
          fieldName: 'age',
          rules: z.number(),
        },
        {
          component: 'VbenInput',
          fieldName: 'metadata',
          rules: z.object({ count: z.number(), label: z.string() }),
        },
        {
          component: 'VbenInput',
          fieldName: 'intersection',
          rules: z.intersection(
            z.object({ left: z.string() }),
            z.object({ right: z.number() }),
          ),
        },
        {
          component: 'VbenInput',
          fieldName: 'serverDefault',
          rules: z.string().default('ready'),
        },
      ],
    });

    expect(initial.form.values).toEqual({
      age: null,
      intersection: { left: '', right: null },
      metadata: { count: null, label: '' },
      profile: { name: 'preset' },
      serverDefault: 'ready',
      title: '',
    });
    expect(initial.delegatedSlots.value).toEqual(['actions']);
    wrapper.unmount();
  });

  it('keeps an explicitly configured value ahead of a schema default', () => {
    const { initial, wrapper } = mountFormInitial({
      schema: [
        {
          component: 'VbenInput',
          defaultValue: 'explicit',
          fieldName: 'name',
          rules: z.string().default('schema'),
        },
      ],
    });

    expect(initial.form.values.name).toBe('explicit');
    wrapper.unmount();
  });
});
