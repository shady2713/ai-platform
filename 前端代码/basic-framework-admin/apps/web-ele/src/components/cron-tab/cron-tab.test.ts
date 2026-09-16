import type { VueWrapper } from '@vue/test-utils';

import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CronTab } from './index';

const mocks = vi.hoisted(() => ({
  showWarningMessage: vi.fn(),
}));

vi.mock('#/utils/feedback', () => ({
  showWarningMessage: mocks.showWarningMessage,
}));

vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');

  function createStub(name: string, tag = 'div') {
    return defineComponent({
      name,
      inheritAttrs: false,
      props: {
        label: { default: undefined, type: null },
        max: { default: undefined, type: null },
        min: { default: undefined, type: null },
        modelValue: { default: undefined, type: null },
        multiple: { default: undefined, type: null },
        type: { default: undefined, type: null },
        value: { default: undefined, type: null },
        width: { default: undefined, type: null },
      },
      emits: ['input', 'update:modelValue'],
      setup(_props, { attrs, slots }) {
        return () =>
          h(tag, { ...attrs, class: `stub-${name}` }, [
            slots.label?.(),
            slots.default?.(),
            slots.append?.(),
            slots.footer?.(),
          ]);
      },
    });
  }

  return {
    ElButton: createStub('ElButton', 'button'),
    ElDialog: createStub('ElDialog'),
    ElForm: createStub('ElForm', 'form'),
    ElFormItem: createStub('ElFormItem'),
    ElInput: createStub('ElInput'),
    ElInputNumber: createStub('ElInputNumber'),
    ElOption: createStub('ElOption'),
    ElRadioButton: createStub('ElRadioButton', 'button'),
    ElRadioGroup: createStub('ElRadioGroup'),
    ElSelect: createStub('ElSelect'),
    ElTabPane: createStub('ElTabPane'),
    ElTabs: createStub('ElTabs'),
  };
});

function findGeneratorSelect(wrapper: VueWrapper) {
  const select = wrapper.findAllComponents({ name: 'ElSelect' })[0];
  if (!select) throw new Error('Expected generator select');
  return select;
}

function findFieldPanes(wrapper: VueWrapper) {
  return wrapper.findAllComponents({ name: 'CronFieldPane' });
}

async function selectGeneratorValue(wrapper: VueWrapper, value: string) {
  findGeneratorSelect(wrapper).vm.$emit('update:modelValue', value);
  await nextTick();
  await nextTick();
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('cron tab', () => {
  it('renders focused field editors, custom shortcuts, and a mobile-safe dialog', () => {
    const wrapper = mount(CronTab, {
      props: {
        shortcuts: [{ text: '工作日上午', value: '0 0 9 ? * 2-6' }],
      },
    });

    expect(findFieldPanes(wrapper)).toHaveLength(6);
    expect(wrapper.findAllComponents({ name: 'CronWeekPane' })).toHaveLength(1);
    expect(wrapper.findAllComponents({ name: 'CronTabLabel' })).toHaveLength(7);
    const options = wrapper.findAllComponents({ name: 'ElOption' });
    expect(options).toHaveLength(8);
    expect(wrapper.findComponent({ name: 'ElDialog' }).props('width')).toBe(
      'min(580px, calc(100vw - 32px))',
    );
    expect(
      options.some((option) => option.props('label') === '工作日上午'),
    ).toBe(true);

    for (const pane of findFieldPanes(wrapper)) {
      pane.vm.$emit('update:item', pane.props('item'));
    }
    const weekPane = wrapper.findComponent({ name: 'CronWeekPane' });
    weekPane.vm.$emit('update:item', weekPane.props('item'));
    wrapper
      .findComponent({ name: 'ElDialog' })
      .vm.$emit('update:modelValue', false);
  });

  it('synchronizes direct input and external model changes', async () => {
    const wrapper = mount(CronTab);
    const input = wrapper.findComponent({ name: 'ElInput' });

    input.vm.$emit('update:modelValue', '0 0 * * * ?');
    await nextTick();
    input.vm.$emit('input', '0 0 * * * ?');
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([
      '0 0 * * * ?',
    ]);

    await wrapper.setProps({ modelValue: '0 0 0 * * ?' });
    expect(input.props('modelValue')).toBe('0 0 0 * * ?');
  });

  it('applies a preset and clears the selection so it can be chosen again', async () => {
    const wrapper = mount(CronTab);

    await selectGeneratorValue(wrapper, '0 * * * * ?');
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([
      '0 * * * * ?',
    ]);
    expect(findGeneratorSelect(wrapper).props('modelValue')).toBeUndefined();

    await selectGeneratorValue(wrapper, '0 * * * * ?');
    expect(wrapper.emitted('update:modelValue')).toHaveLength(2);
  });

  it('opens valid custom expressions and keeps day and week mutually exclusive', async () => {
    const wrapper = mount(CronTab, {
      props: { modelValue: '0 15 3 * * ?' },
    });

    await selectGeneratorValue(wrapper, 'custom');
    expect(
      wrapper.findComponent({ name: 'ElDialog' }).props('modelValue'),
    ).toBe(true);
    const fieldPanes = findFieldPanes(wrapper);
    expect(fieldPanes[0]?.props('value')).toBe('0');
    expect(fieldPanes[1]?.props('value')).toBe('15');
    expect(fieldPanes[2]?.props('value')).toBe('3');

    const day = fieldPanes[3]?.props('item');
    const week = wrapper.findComponent({ name: 'CronWeekPane' }).props('item');
    if (!day || !week) throw new Error('Expected day and week models');
    week.type = '3';
    await nextTick();
    expect(day.type).toBe('5');
    day.type = '1';
    await nextTick();
    expect(week.type).toBe('5');
  });

  it('falls back to defaults when opening an invalid expression', async () => {
    const wrapper = mount(CronTab, {
      props: { modelValue: '99 0 0 * * ?' },
    });

    await selectGeneratorValue(wrapper, 'custom');

    expect(mocks.showWarningMessage).toHaveBeenCalledWith(
      'Cron 表达式错误，已转换为默认表达式',
    );
    expect(findFieldPanes(wrapper)[0]?.props('value')).toBe('*');
  });

  it('submits a complete expression and keeps an incomplete one open', async () => {
    const wrapper = mount(CronTab, {
      props: { modelValue: '0 * * * * ?' },
    });
    await selectGeneratorValue(wrapper, 'custom');
    const second = findFieldPanes(wrapper)[0]?.props('item');
    if (!second) throw new Error('Expected second model');
    second.type = '2';
    second.loop.start = 0;
    second.loop.end = 5;
    await nextTick();

    const buttons = wrapper.findAllComponents({ name: 'ElButton' });
    await buttons[1]?.trigger('click');
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([
      '0/5 * * * * ?',
    ]);
    expect(
      wrapper.findComponent({ name: 'ElDialog' }).props('modelValue'),
    ).toBe(false);

    await selectGeneratorValue(wrapper, 'custom');
    const reopenedSecond = findFieldPanes(wrapper)[0]?.props('item');
    if (!reopenedSecond) throw new Error('Expected reopened second model');
    reopenedSecond.type = '2';
    reopenedSecond.loop.end = 0;
    await nextTick();
    await buttons[1]?.trigger('click');
    expect(mocks.showWarningMessage).toHaveBeenCalledWith(
      'Cron 表达式不完整，请检查后重试',
    );
    expect(
      wrapper.findComponent({ name: 'ElDialog' }).props('modelValue'),
    ).toBe(true);

    await buttons[0]?.trigger('click');
    expect(
      wrapper.findComponent({ name: 'ElDialog' }).props('modelValue'),
    ).toBe(false);
  });

  it('renders numeric range, interval, appointment, and optional modes', async () => {
    const wrapper = mount(CronTab);
    const secondPane = findFieldPanes(wrapper)[0];
    const dayPane = findFieldPanes(wrapper)[3];
    const yearPane = findFieldPanes(wrapper)[5];
    if (!secondPane || !dayPane || !yearPane) {
      throw new Error('Expected numeric field panes');
    }
    const item = secondPane.props('item');
    const mode = secondPane.findComponent({ name: 'ElRadioGroup' });

    mode.vm.$emit('update:modelValue', '1');
    await nextTick();
    const rangeInputs = secondPane.findAllComponents({ name: 'ElInputNumber' });
    expect(rangeInputs).toHaveLength(2);
    expect(rangeInputs[0]?.props()).toMatchObject({ max: 59, min: 0 });
    rangeInputs[0]?.vm.$emit('update:modelValue', 5);
    rangeInputs[1]?.vm.$emit('update:modelValue', 10);
    expect(item.range).toEqual({ end: 10, start: 5 });

    mode.vm.$emit('update:modelValue', '2');
    await nextTick();
    const intervalInputs = secondPane.findAllComponents({
      name: 'ElInputNumber',
    });
    expect(intervalInputs[1]?.props()).toMatchObject({ max: 60, min: 1 });
    expect(secondPane.text()).toContain('秒开始，每');
    intervalInputs[0]?.vm.$emit('update:modelValue', 0);
    intervalInputs[1]?.vm.$emit('update:modelValue', 15);
    expect(item.loop).toEqual({ end: 15, start: 0 });

    mode.vm.$emit('update:modelValue', '3');
    await nextTick();
    expect(secondPane.findAllComponents({ name: 'ElOption' })).toHaveLength(12);
    secondPane
      .findComponent({ name: 'ElSelect' })
      .vm.$emit('update:modelValue', ['5', '10']);
    expect(item.appoint).toEqual(['5', '10']);
    expect(dayPane.text()).toContain('本月最后一天');
    expect(dayPane.text()).toContain('不指定');
    expect(yearPane.text()).toContain('忽略');
  });

  it('renders every specialized week editor with correct ordinal bounds', async () => {
    const wrapper = mount(CronTab);
    const weekPane = wrapper.findComponent({ name: 'CronWeekPane' });
    const item = weekPane.props('item');
    const mode = weekPane.findComponent({ name: 'ElRadioGroup' });

    mode.vm.$emit('update:modelValue', '1');
    await nextTick();
    const rangeSelects = weekPane.findAllComponents({ name: 'ElSelect' });
    expect(rangeSelects).toHaveLength(2);
    rangeSelects[0]?.vm.$emit('update:modelValue', '2');
    rangeSelects[1]?.vm.$emit('update:modelValue', '6');
    expect(item.range).toEqual({ end: '6', start: '2' });

    mode.vm.$emit('update:modelValue', '2');
    await nextTick();
    expect(weekPane.text()).toContain('每月第');
    const ordinal = weekPane.findComponent({ name: 'ElInputNumber' });
    expect(ordinal.props()).toMatchObject({ max: 5, min: 1 });
    ordinal.vm.$emit('update:modelValue', 3);
    weekPane
      .findComponent({ name: 'ElSelect' })
      .vm.$emit('update:modelValue', '4');
    expect(item.loop).toEqual({ end: '4', start: 3 });

    mode.vm.$emit('update:modelValue', '3');
    await nextTick();
    expect(weekPane.findAllComponents({ name: 'ElOption' })).toHaveLength(7);
    weekPane
      .findComponent({ name: 'ElSelect' })
      .vm.$emit('update:modelValue', ['2', '4']);
    expect(item.appoint).toEqual(['2', '4']);

    mode.vm.$emit('update:modelValue', '4');
    await nextTick();
    expect(weekPane.text()).toContain('月内最后一个星期几');
    expect(weekPane.findAllComponents({ name: 'ElOption' })).toHaveLength(7);
    weekPane
      .findComponent({ name: 'ElSelect' })
      .vm.$emit('update:modelValue', '7');
    expect(item.last).toBe('7');
  });
});
