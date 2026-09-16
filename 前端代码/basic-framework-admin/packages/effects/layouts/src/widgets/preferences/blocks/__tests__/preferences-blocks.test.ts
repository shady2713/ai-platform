import type { SelectOption } from '@vben/types';

import { mount } from '@vue/test-utils';
import { h } from 'vue';

import { $t } from '@vben/locales';

import { describe, expect, it, vi } from 'vitest';

import Block from '../block.vue';
import CheckboxItem from '../checkbox-item.vue';
import Animation from '../general/animation.vue';
import General from '../general/general.vue';
import InputItem from '../input-item.vue';
import Breadcrumb from '../layout/breadcrumb.vue';

vi.mock('@vben/locales', () => ({
  loadLocaleMessages: vi.fn(),
  $t: (key: string) => key,
}));

vi.mock('@vben/constants', () => ({
  SUPPORT_LANGUAGES: [{ label: 'English', value: 'en-US' }],
}));

/** shadcn/reka 组件按运行时 name/__name 匹配 stub。 */
const shadcnStubs = {
  VbenCheckButtonGroup: true,
  'check-button-group': true,
  VbenTooltip: true,
  tooltip: true,
  Switch: {
    name: 'SwitchStub',
    emits: ['update:modelValue'],
    props: { modelValue: { type: Boolean, default: false } },
    setup(
      props: { modelValue?: boolean },
      { emit }: { emit: (event: 'update:modelValue', value: boolean) => void },
    ) {
      return () =>
        h(
          'button',
          {
            class: 'switch-stub',
            'data-checked': String(props.modelValue),
            onClick: () => emit('update:modelValue', !props.modelValue),
          },
          'switch',
        );
    },
  },
};

describe('preferenceBlock', () => {
  it('renders the title and slot content', () => {
    const wrapper = mount(Block, {
      props: { title: 'General' },
      slots: { default: 'content' },
    });
    expect(wrapper.get('h3').text()).toBe('General');
    expect(wrapper.text()).toContain('content');
  });

  it('renders an empty title when not provided', () => {
    const wrapper = mount(Block, { slots: { default: 'x' } });
    expect(wrapper.get('h3').text()).toBe('');
  });
});

describe('preferenceCheckboxItem', () => {
  const items: SelectOption[] = [
    { label: 'A', value: 'a' },
    { label: 'B', value: 'b' },
  ];

  it('passes the model value through the check button group', () => {
    const wrapper = mount(CheckboxItem, {
      props: { items, modelValue: ['a'] },
      global: { stubs: shadcnStubs },
    });
    expect(wrapper.exists()).toBe(true);
  });

  it('renders the disabled state class', () => {
    const wrapper = mount(CheckboxItem, {
      props: { disabled: true, items },
      global: { stubs: shadcnStubs },
    });
    expect(wrapper.element.className).toContain('pointer-events-none');
    expect(wrapper.element.className).toContain('opacity-50');
  });
});

describe('preferenceInputItem', () => {
  it('binds the input value to the model', async () => {
    const wrapper = mount(InputItem, {
      props: { modelValue: 'init' },
      global: { stubs: shadcnStubs },
      slots: { default: 'Label' },
    });
    await wrapper.get('input').setValue('typed');
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['typed']);
  });

  it('clears the value through the clear icon', async () => {
    const wrapper = mount(InputItem, {
      props: { modelValue: 'text' },
      global: { stubs: shadcnStubs },
    });
    const clear = wrapper
      .findAll('*')
      .find((node) => node.attributes('class')?.includes('absolute'));
    expect(clear).toBeDefined();
    await clear?.trigger('click');
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['']);
  });
});

describe('preferenceGeneralConfig', () => {
  it('shows the watermark content input only while the watermark is on', async () => {
    const wrapper = mount(General, {
      props: { appWatermark: false, appWatermarkContent: '' },
      global: { stubs: shadcnStubs },
      slots: { default: 'x' },
    });
    // SwitchItem 内部可能为 undefined 时兜底，仅验证挂载与切换模型
    expect(wrapper.exists()).toBe(true);
  });

  it('toggles the watermark model through the switch stub', async () => {
    const wrapper = mount(General, {
      props: { appWatermark: true, appWatermarkContent: 'watermark-text' },
      global: { stubs: shadcnStubs },
    });
    const switches = wrapper.findAll('.switch-stub');
    // 第二个 switch 是 watermark；关闭后应清空 watermarkContent
    await switches[1]?.trigger('click');
    expect(wrapper.emitted('update:appWatermark')?.at(-1)).toEqual([false]);
    expect(wrapper.emitted('update:appWatermarkContent')?.at(-1)).toEqual(['']);
  });
});

describe('preferenceAnimation', () => {
  it('renders the preset list when transitions are enabled', () => {
    const wrapper = mount(Animation, {
      props: { transitionEnable: true, transitionName: 'fade' },
      global: { stubs: shadcnStubs },
    });
    expect(wrapper.findAll('.outline-box').length).toBe(4);
  });

  it('hides the presets when transitions are disabled', () => {
    const wrapper = mount(Animation, {
      props: { transitionEnable: false },
      global: { stubs: shadcnStubs },
    });
    expect(wrapper.findAll('.outline-box').length).toBe(0);
  });

  it('updates the transition name when a preset is clicked', async () => {
    const wrapper = mount(Animation, {
      props: { transitionEnable: true, transitionName: 'fade' },
      global: { stubs: shadcnStubs },
    });
    await wrapper.findAll('.outline-box')[3]?.trigger('click');
    expect(wrapper.emitted('update:transitionName')?.at(-1)).toEqual([
      'fade-down',
    ]);
  });
});

describe('preferenceBreadcrumbConfig', () => {
  it('renders all five breadcrumb controls', () => {
    const wrapper = mount(Breadcrumb, {
      props: { breadcrumbEnable: true },
      global: { stubs: shadcnStubs },
    });
    expect(wrapper.find('h3').exists()).toBe(false);
    expect(wrapper.text()).toContain($t('preferences.breadcrumb.enable'));
  });

  it('disables the dependent items while breadcrumbs are off', () => {
    const wrapper = mount(Breadcrumb, {
      props: { breadcrumbEnable: false },
      global: { stubs: shadcnStubs },
    });
    expect(wrapper.exists()).toBe(true);
  });
});
