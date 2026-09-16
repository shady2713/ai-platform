import type { Component, FunctionalComponent, VNode } from 'vue';

import type { ComponentType } from './index';

import { isVNode } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { initComponentAdapter } from './index';

const {
  defineMessageMock,
  notificationMock,
  setComponentsMock,
  translateMock,
} = vi.hoisted(() => ({
  defineMessageMock: vi.fn(),
  notificationMock: vi.fn(),
  setComponentsMock: vi.fn(),
  translateMock: vi.fn((key: string) => key),
}));

vi.mock('@vben/common-ui', () => ({
  ApiComponent: { name: 'ApiComponent' },
  globalShareState: {
    defineMessage: defineMessageMock,
    setComponents: setComponentsMock,
  },
  IconPicker: { name: 'IconPicker' },
}));

vi.mock('@vben/locales', () => ({
  $t: translateMock,
}));

vi.mock('element-plus', () => ({
  ElNotification: notificationMock,
}));

vi.mock('#/components/upload', () => ({
  FileUpload: { name: 'FileUpload' },
  ImageUpload: { name: 'ImageUpload' },
}));

type ComponentRegistry = Partial<Record<ComponentType, Component>>;
type MessageRegistry = {
  copyPreferencesSuccess: (title: string, content: string) => void;
};

describe('component adapter', () => {
  let components: ComponentRegistry;

  beforeEach(async () => {
    vi.clearAllMocks();
    await initComponentAdapter();
    components = setComponentsMock.mock.calls[0]?.[0] as ComponentRegistry;
  });

  it('registers the reusable form component catalog and message bridge', () => {
    expect(Object.keys(components)).toEqual(
      expect.arrayContaining([
        'ApiCascader',
        'ApiSelect',
        'ApiTreeSelect',
        'CheckboxGroup',
        'DatePicker',
        'DefaultButton',
        'FileUpload',
        'ImageUpload',
        'Input',
        'PrimaryButton',
        'RadioGroup',
        'RangePicker',
        'Select',
        'Textarea',
        'TimePicker',
      ]),
    );
    expect(defineMessageMock).toHaveBeenCalledOnce();

    const messages = defineMessageMock.mock.calls[0]?.[0] as MessageRegistry;
    messages.copyPreferencesSuccess('设置', '复制成功');
    expect(notificationMock).toHaveBeenCalledWith({
      duration: 0,
      message: '复制成功',
      position: 'bottom-right',
      title: '设置',
      type: 'success',
    });
  });

  it('forwards button and select attributes as component properties', () => {
    const defaultButton = renderFunctional(
      components.DefaultButton,
      { loading: true },
      { disabled: true },
    );
    const primaryButton = renderFunctional(
      components.PrimaryButton,
      {},
      { autofocus: true },
    );
    const select = renderFunctional(
      components.Select,
      { modelValue: 1 },
      { clearable: true },
    );

    expect(defaultButton.props).toMatchObject({
      disabled: true,
      loading: true,
      type: 'default',
    });
    expect(defaultButton.props).not.toHaveProperty('attrs');
    expect(primaryButton.props).toMatchObject({
      autofocus: true,
      type: 'primary',
    });
    expect(select.props).toMatchObject({ clearable: true, modelValue: 1 });
    expect(select.props).not.toHaveProperty('attrs');
  });

  it('normalizes scalar range identifiers without changing array identifiers', () => {
    const dateRange = renderFunctional(components.DatePicker, {
      id: 'created-at',
      name: 'createdAt',
      type: 'datetimerange',
    });
    const dateSingle = renderFunctional(components.DatePicker, {
      id: 'birthday',
      name: 'birthday',
      type: 'date',
    });
    const timeRange = renderFunctional(components.TimePicker, {
      id: 'period',
      isRange: true,
      name: 'period',
    });
    const rangePicker = renderFunctional(components.RangePicker, {
      id: ['start-id', 'end-id'],
      name: ['start', 'end'],
    });

    expect(dateRange.props).toMatchObject({
      id: ['created-at', 'created-at_end'],
      name: ['createdAt', 'createdAt_end'],
    });
    expect(dateSingle.props).toMatchObject({
      id: 'birthday',
      name: 'birthday',
    });
    expect(timeRange.props).toMatchObject({
      id: ['period', 'period_end'],
      name: ['period', 'period_end'],
    });
    expect(rangePicker.props).toMatchObject({
      id: ['start-id', 'end-id'],
      name: ['start', 'end'],
      type: 'datetimerange',
    });
  });
});

function renderFunctional(
  component: Component | undefined,
  props: Record<string, unknown>,
  attrs: Record<string, unknown> = {},
) {
  if (typeof component !== 'function') {
    throw new TypeError('Expected a functional component');
  }
  const functionalComponent = component as FunctionalComponent<
    Record<string, unknown>
  >;
  const result = functionalComponent(props, {
    attrs,
    emit: vi.fn(),
    slots: {},
  });
  if (!isVNode(result)) {
    throw new TypeError('Expected the adapter to return a VNode');
  }
  return result as VNode;
}
