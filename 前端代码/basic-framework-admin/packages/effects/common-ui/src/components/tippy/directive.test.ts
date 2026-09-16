import type { Instance } from 'tippy.js';

import type { ComputedRef, Ref, VNode } from 'vue';
import type { TippyOptions } from 'vue-tippy';

import { mount } from '@vue/test-utils';
import { computed, h, nextTick, ref, withDirectives } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import useTippyDirective, { normalizeTippyOptions } from './directive';

const mocks = vi.hoisted(() => ({
  destroy: vi.fn(),
  setProps: vi.fn(),
  useTippy: vi.fn(),
}));

vi.mock('vue-tippy', () => ({
  useTippy: mocks.useTippy,
}));

function createHarness(
  bindingValue: Ref<unknown>,
  isDark: ComputedRef<boolean>,
  vnodeProps: Record<string, unknown> = {},
) {
  const directive = useTippyDirective(isDark);
  return {
    setup() {
      return () =>
        withDirectives(h('button', { title: 'native title', ...vnodeProps }), [
          [
            directive,
            bindingValue.value,
            undefined,
            { arrow: true, invalid: true, top: true },
          ],
        ]) as VNode;
    },
  };
}

describe('tippy directive', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.useTippy.mockReturnValue({
      destroy: mocks.destroy,
      setProps: mocks.setProps,
    });
  });

  it('normalizes supported values and rejects invalid bindings', () => {
    expect(normalizeTippyOptions(undefined, 'light')).toEqual({
      theme: 'light',
    });
    expect(normalizeTippyOptions('help', 'light')).toEqual({
      content: 'help',
      theme: 'light',
    });
    expect(
      normalizeTippyOptions({ content: 'help', theme: 'custom' }, 'light'),
    ).toEqual({ content: 'help', theme: 'custom' });
    expect(() => normalizeTippyOptions([], 'light')).toThrow(TypeError);
    expect(() => normalizeTippyOptions(1, 'light')).toThrow(TypeError);
  });

  it('applies modifiers, lifecycle callbacks, updates and teardown', async () => {
    const bindingValue = ref<unknown>('help');
    const dark = ref(false);
    const onShow = vi.fn();
    const rejectShow = vi.fn(() => false);
    const onShown = vi.fn();
    const onHidden = vi.fn();
    const onHide = vi.fn(() => false);
    const onMount = vi.fn();
    const wrapper = mount(
      createHarness(
        bindingValue,
        computed(() => dark.value),
        {
          onTippyHidden: onHidden,
          onTippyHide: onHide,
          onTippyMount: onMount,
          onTippyShow: [onShow, rejectShow],
          onTippyShown: onShown,
        },
      ),
    );

    expect(mocks.useTippy).toHaveBeenCalledOnce();
    const [element, options] = mocks.useTippy.mock.calls[0] as [
      HTMLElement,
      TippyOptions,
    ];
    expect(options).toMatchObject({
      arrow: true,
      content: 'help',
      placement: 'top',
      theme: 'light',
    });
    expect(element.hasAttribute('title')).toBe(false);

    const instance = {} as Instance;
    expect(options.onShow?.(instance)).toBe(false);
    options.onShown?.(instance);
    options.onHidden?.(instance);
    expect(options.onHide?.(instance)).toBe(false);
    options.onMount?.(instance);
    expect(onShow).toHaveBeenCalledWith(instance);
    expect(rejectShow).toHaveBeenCalledWith(instance);
    expect(onShown).toHaveBeenCalledWith(instance);
    expect(onHidden).toHaveBeenCalledWith(instance);
    expect(onHide).toHaveBeenCalledWith(instance);
    expect(onMount).toHaveBeenCalledWith(instance);

    dark.value = true;
    bindingValue.value = { content: 'updated', placement: 'bottom' };
    await nextTick();
    expect(mocks.setProps).toHaveBeenLastCalledWith({
      content: 'updated',
      placement: 'bottom',
      theme: '',
    });

    wrapper.unmount();
    expect(mocks.destroy).toHaveBeenCalledOnce();
  });

  it('uses the native title as fallback content', () => {
    const wrapper = mount(
      createHarness(
        ref(undefined),
        computed(() => false),
      ),
    );
    const [element, options] = mocks.useTippy.mock.calls[0] as [
      HTMLElement,
      TippyOptions,
    ];

    expect(options.content).toBe('native title');
    expect(element.hasAttribute('title')).toBe(false);
    wrapper.unmount();
  });
});
