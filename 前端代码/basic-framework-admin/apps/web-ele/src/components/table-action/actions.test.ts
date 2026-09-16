import { describe, expect, it, vi } from 'vitest';

import {
  getButtonProps,
  getPopConfirmProps,
  getTooltipProps,
  invokeDropdownAction,
  isActionVisible,
  resolveActions,
  resolveDropdownActions,
} from './actions';

describe('table action helpers', () => {
  it('applies permission and business visibility once', () => {
    const hasAccess = vi.fn().mockReturnValue(false);
    const ifShow = vi.fn().mockReturnValue(true);

    expect(isActionVisible({ auth: ['system:user'], ifShow }, hasAccess)).toBe(
      false,
    );
    expect(hasAccess).toHaveBeenCalledOnce();
    expect(ifShow).not.toHaveBeenCalled();

    hasAccess.mockReturnValue(true);
    expect(isActionVisible({ auth: ['system:user'], ifShow }, hasAccess)).toBe(
      true,
    );
    expect(ifShow).toHaveBeenCalledOnce();

    hasAccess.mockClear();
    expect(isActionVisible({ ifShow: false }, hasAccess)).toBe(false);
    expect(hasAccess).not.toHaveBeenCalled();
  });

  it('resolves button actions with safe defaults and confirmation handlers', () => {
    const confirm = vi.fn();
    const cancel = vi.fn();
    const actions = resolveActions(
      [
        { label: 'Edit' },
        { ifShow: false, label: 'Hidden' },
        { popConfirm: { cancel, confirm, title: 'Delete?' } },
      ],
      () => true,
    );

    expect(actions).toHaveLength(2);
    expect(actions[0]).toMatchObject({
      enable: false,
      label: 'Edit',
      type: 'primary',
    });
    expect(actions[1]).toMatchObject({
      enable: true,
      label: '',
      onCancel: cancel,
      onConfirm: confirm,
      type: 'primary',
    });
  });

  it('calculates dropdown dividers from visible actions', () => {
    const actions = resolveDropdownActions(
      [
        { label: 'First' },
        { ifShow: false, label: 'Hidden' },
        { label: 'Last' },
      ],
      true,
      () => true,
    );

    expect(actions.map(({ divider, text }) => ({ divider, text }))).toEqual([
      { divider: true, text: 'First' },
      { divider: false, text: 'Last' },
    ]);
    expect(
      resolveDropdownActions([{ label: 'Only' }], false, () => true)[0]
        ?.divider,
    ).toBe(false);
  });

  it('maps confirmation labels and handlers to element-plus props', () => {
    const confirm = vi.fn();
    const cancel = vi.fn();

    expect(
      getPopConfirmProps({
        cancel,
        cancelText: 'No',
        confirm,
        disabled: true,
        icon: 'warning',
        okText: 'Yes',
        title: 'Continue?',
      }),
    ).toEqual({
      cancelButtonText: 'No',
      confirmButtonText: 'Yes',
      disabled: true,
      onCancel: cancel,
      onConfirm: confirm,
      title: 'Continue?',
    });
    expect(getPopConfirmProps()).toEqual({});
  });

  it('does not leak action metadata or click handlers into button props', () => {
    const action = resolveActions(
      [
        {
          auth: ['system:user'],
          disabled: true,
          icon: 'edit',
          label: 'Edit',
          onClick: vi.fn(),
          tooltip: 'Edit user',
          type: 'warning',
        },
      ],
      () => true,
    )[0];

    expect(action).toBeDefined();
    if (!action) {
      throw new Error('Expected a resolved action');
    }
    expect(getButtonProps(action)).toEqual({
      disabled: true,
      type: 'warning',
    });
  });

  it('normalizes tooltip props and rejects empty tooltips', () => {
    expect(getTooltipProps('Edit user')).toEqual({ content: 'Edit user' });
    expect(getTooltipProps({ content: 'Edit user', placement: 'top' })).toEqual(
      { content: 'Edit user', placement: 'top' },
    );
    expect(getTooltipProps('')).toBeUndefined();
    expect(getTooltipProps({ placement: 'top' })).toBeUndefined();
    expect(getTooltipProps(undefined)).toBeUndefined();
  });

  it('invokes only enabled dropdown actions without confirmation', () => {
    const onClick = vi.fn();
    const action = resolveDropdownActions(
      [{ label: 'Edit', onClick }],
      true,
      () => true,
    )[0];

    if (!action) {
      throw new Error('Expected a dropdown action');
    }
    invokeDropdownAction(action);
    expect(onClick).toHaveBeenCalledOnce();

    invokeDropdownAction({ ...action, disabled: true });
    invokeDropdownAction({
      ...action,
      popConfirm: { confirm: vi.fn(), title: 'Delete?' },
    });
    invokeDropdownAction(undefined);
    expect(onClick).toHaveBeenCalledOnce();
  });
});
