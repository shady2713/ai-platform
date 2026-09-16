import type { VNode } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  clearAllAlerts,
  vbenAlert,
  vbenConfirm,
  vbenPrompt,
} from './AlertBuilder';

interface CapturedAlertProps {
  beforeClose?: (scope: {
    isConfirm: boolean;
  }) => boolean | Promise<boolean | undefined> | undefined;
  content: (() => VNode) | string;
  icon?: string;
  onClosed: () => void;
  onConfirm: () => void;
  onOpened?: () => Promise<void> | void;
  open: boolean;
  showCancel?: boolean;
  title?: string;
}

const mocks = vi.hoisted(() => ({
  props: [] as CapturedAlertProps[],
}));

vi.mock('./alert.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    default: defineComponent({
      inheritAttrs: false,
      setup(_, { attrs }) {
        mocks.props.push(attrs as unknown as CapturedAlertProps);
        return () => h('div', { 'data-alert': '' });
      },
    }),
  };
});

function latestProps(): CapturedAlertProps {
  const props = mocks.props.at(-1);
  if (!props) throw new Error('Expected an alert to be rendered');
  return props;
}

describe('alertBuilder', () => {
  beforeEach(() => {
    clearAllAlerts();
    mocks.props.length = 0;
    document.body.innerHTML = '';
  });

  it('resolves once on confirmation and disposes its container', async () => {
    const result = vbenAlert('Saved', 'Notice');
    const props = latestProps();

    expect(props).toMatchObject({
      content: 'Saved',
      open: true,
      title: 'Notice',
    });
    props.onConfirm();
    props.onClosed();

    await expect(result).resolves.toBeUndefined();
    expect(document.querySelector('[data-alert]')).toBeNull();
  });

  it('rejects cancellation and enables cancel controls for confirms', async () => {
    const result = vbenConfirm('Delete item?');
    const props = latestProps();

    expect(props.showCancel).toBe(true);
    props.onClosed();

    await expect(result).rejects.toThrow('dialog cancelled');
    expect(document.querySelector('[data-alert]')).toBeNull();
  });

  it('normalizes every confirm overload without losing caller options', async () => {
    const objectResult = vbenConfirm({
      content: 'Object form',
      title: 'Object',
    });
    expect(latestProps()).toMatchObject({
      content: 'Object form',
      showCancel: true,
      title: 'Object',
    });
    latestProps().onConfirm();

    const optionsResult = vbenConfirm('Options form', {
      icon: 'warning',
      title: 'Options',
    });
    expect(latestProps()).toMatchObject({
      content: 'Options form',
      icon: 'warning',
      showCancel: true,
      title: 'Options',
    });
    latestProps().onConfirm();

    const titledResult = vbenConfirm('Titled form', 'Danger', {
      icon: 'warning',
    });
    expect(latestProps()).toMatchObject({
      content: 'Titled form',
      icon: 'warning',
      showCancel: true,
      title: 'Danger',
    });
    latestProps().onConfirm();

    await expect(
      Promise.all([objectResult, optionsResult, titledResult]),
    ).resolves.toEqual([undefined, undefined, undefined]);
  });

  it('settles every pending alert when clearing globally', async () => {
    const first = vbenAlert('First').catch((error: unknown) => error);
    const second = vbenAlert('Second').catch((error: unknown) => error);

    clearAllAlerts();

    await expect(first).resolves.toEqual(new Error('dialog cancelled'));
    await expect(second).resolves.toEqual(new Error('dialog cancelled'));
    expect(document.querySelector('[data-alert]')).toBeNull();
  });

  it('returns input and delegates close validation with the latest value', async () => {
    const beforeClose = vi.fn(() => true);
    const result = vbenPrompt<string>({
      beforeClose,
      content: 'Name',
      defaultValue: 'before',
    });
    const props = latestProps();
    if (typeof props.content !== 'function') {
      throw new TypeError('Expected prompt content renderer');
    }

    const content = props.content();
    const children = content.children as VNode[];
    const input = children.at(-1);
    const update = input?.props?.['onUpdate:modelValue'];
    if (typeof update !== 'function') {
      throw new TypeError('Expected prompt model update handler');
    }
    update('after');
    await expect(props.beforeClose?.({ isConfirm: true })).resolves.toBe(true);
    expect(beforeClose).toHaveBeenCalledWith({
      isConfirm: true,
      value: 'after',
    });
    await props.onOpened?.();
    props.onConfirm();

    await expect(result).resolves.toBe('after');
  });
});
