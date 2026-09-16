import type { SetupContext } from 'vue';

import { flushPromises, mount } from '@vue/test-utils';
import { h, markRaw, nextTick } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import ApiComponent from './api-component.vue';

interface ApiComponentExposed {
  getComponentRef: <T = unknown>() => T;
  getOptions: () => Record<string, unknown>[];
  getValue: () => unknown;
  updateParam: (params: Record<string, unknown>) => void;
}

const WrappedComponent = markRaw({
  inheritAttrs: false,
  setup(_: unknown, { attrs, slots }: SetupContext) {
    return () => h('div', attrs, slots.default?.());
  },
});

function exposed(wrapper: ReturnType<typeof mount>): ApiComponentExposed {
  return wrapper.vm as unknown as ApiComponentExposed;
}

describe('api component', () => {
  it('normalizes remote options and auto-selects the first item', async () => {
    const api = vi.fn().mockResolvedValue([
      {
        id: 7,
        inactive: true,
        name: 'Parent',
        nodes: [{ id: 8, inactive: false, name: 'Child' }],
      },
    ]);
    const wrapper = mount(ApiComponent, {
      props: {
        api,
        autoSelect: 'first',
        childrenField: 'nodes',
        component: WrappedComponent,
        disabledField: 'inactive',
        labelField: 'name',
        numberToString: true,
        valueField: 'id',
      },
    });

    await flushPromises();

    expect(api).toHaveBeenCalledWith({});
    expect(exposed(wrapper).getOptions()).toEqual([
      {
        children: [{ disabled: false, label: 'Child', value: '8' }],
        disabled: true,
        label: 'Parent',
        value: '7',
      },
    ]);
    expect(exposed(wrapper).getValue()).toBe('7');
    expect(wrapper.emitted('optionsChange')?.[0]?.[0]).toEqual(
      exposed(wrapper).getOptions(),
    );
  });

  it('uses fallback options when a result field is not an array', async () => {
    const fallback = [{ label: 'Fallback', value: 'fallback' }];
    const wrapper = mount(ApiComponent, {
      props: {
        api: vi.fn().mockResolvedValue({ data: { invalid: true } }),
        component: WrappedComponent,
        options: fallback,
        resultField: 'data.items',
      },
    });

    await flushPromises();

    expect(exposed(wrapper).getOptions()).toEqual(fallback);
    expect(wrapper.emitted('optionsChange')?.[0]?.[0]).toEqual(fallback);
  });

  it('emits request failures without logging the remote error payload', async () => {
    const error = new Error('remote payload must remain private');
    const consoleWarn = vi.spyOn(console, 'warn');
    const wrapper = mount(ApiComponent, {
      props: {
        api: vi.fn().mockRejectedValue(error),
        component: WrappedComponent,
      },
    });

    await flushPromises();

    expect(wrapper.emitted('fetchError')?.[0]?.[0]).toBe(error);
    expect(consoleWarn).not.toHaveBeenCalled();
    consoleWarn.mockRestore();
  });

  it('applies request hooks and forwards model and visibility events', async () => {
    const api = vi.fn().mockResolvedValue({ items: [{ id: 1 }] });
    const beforeFetch = vi.fn().mockResolvedValue({ query: 'normalized' });
    const afterFetch = vi
      .fn()
      .mockResolvedValue([{ label: 'Selected', value: 'selected' }]);
    const autoSelect = vi.fn((items: Record<string, unknown>[]) => items[0]);
    const wrapper = mount(ApiComponent, {
      props: {
        afterFetch,
        api,
        autoSelect,
        beforeFetch,
        component: WrappedComponent,
        immediate: false,
        visibleEvent: 'onVisibleChange',
      },
    });
    const child = wrapper.findComponent(WrappedComponent);
    const attrs = child.vm.$attrs as Record<string, unknown>;

    (attrs['onUpdate:modelValue'] as (value: unknown) => void)('manual');
    expect(exposed(wrapper).getValue()).toBe('manual');

    await (attrs.onVisibleChange as (visible: boolean) => Promise<void>)(true);
    await flushPromises();

    expect(beforeFetch).toHaveBeenCalledWith({});
    expect(api).toHaveBeenCalledWith({ query: 'normalized' });
    expect(afterFetch).toHaveBeenCalledWith({ items: [{ id: 1 }] });
    expect(exposed(wrapper).getOptions()).toEqual([
      { disabled: undefined, label: 'Selected', value: 'selected' },
    ]);
    expect(exposed(wrapper).getComponentRef()).toBe(child.vm);

    await wrapper.setProps({ alwaysLoad: true });
    await (attrs.onVisibleChange as (visible: boolean) => Promise<void>)(true);
    expect(api).toHaveBeenCalledTimes(2);
  });

  it('queues one trailing request while a request is in flight', async () => {
    let resolveFirst: ((value: Record<string, unknown>[]) => void) | undefined;
    const firstRequest = new Promise<Record<string, unknown>[]>((resolve) => {
      resolveFirst = resolve;
    });
    const api = vi
      .fn()
      .mockImplementationOnce(() => firstRequest)
      .mockResolvedValueOnce([{ label: 'Latest', value: 'latest' }]);
    const SlotComponent = markRaw({
      setup(_: unknown, { slots }: SetupContext) {
        return () => h('div', slots.loading?.());
      },
    });
    const wrapper = mount(ApiComponent, {
      props: {
        api,
        component: SlotComponent,
        loadingSlot: 'loading',
      },
    });

    await nextTick();
    expect(wrapper.find('.animate-spin').exists()).toBe(true);
    exposed(wrapper).updateParam({ page: 2 });
    await nextTick();
    expect(api).toHaveBeenCalledTimes(1);

    resolveFirst?.([{ label: 'Stale', value: 'stale' }]);
    await flushPromises();

    expect(api).toHaveBeenCalledTimes(2);
    expect(api).toHaveBeenLastCalledWith({ page: 2 });
    expect(exposed(wrapper).getOptions()[0]?.value).toBe('latest');
  });

  it('skips equal parameter updates and tolerates a missing API', async () => {
    const wrapper = mount(ApiComponent, {
      props: { component: WrappedComponent, immediate: false },
    });

    exposed(wrapper).updateParam({});
    await nextTick();
    exposed(wrapper).updateParam({ query: 'changed' });
    await flushPromises();

    expect(wrapper.emitted('fetchError')).toBeUndefined();
  });

  it.each([
    ['last', 'second'],
    ['one', 'only'],
  ] as const)(
    'supports the %s auto-selection strategy',
    async (autoSelect, expected) => {
      const options =
        autoSelect === 'last'
          ? [
              { label: 'First', value: 'first' },
              { label: 'Second', value: 'second' },
            ]
          : [{ label: 'Only', value: 'only' }];
      const wrapper = mount(ApiComponent, {
        props: {
          api: vi.fn().mockResolvedValue(options),
          autoSelect,
          component: WrappedComponent,
        },
      });

      await flushPromises();

      expect(exposed(wrapper).getValue()).toBe(expected);
    },
  );

  it('supports a custom auto-selection strategy', async () => {
    const autoSelect = vi.fn((items: Record<string, unknown>[]) =>
      items.at(-1),
    );
    const wrapper = mount(ApiComponent, {
      props: {
        api: vi.fn().mockResolvedValue([
          { label: 'First', value: 'first' },
          { label: 'Second', value: 'second' },
        ]),
        autoSelect,
        component: WrappedComponent,
      },
    });

    await flushPromises();

    expect(autoSelect).toHaveBeenCalledOnce();
    expect(exposed(wrapper).getValue()).toBe('second');
  });
});
